package com.example.byd.bus

import com.example.byd.data.ApiError
import com.example.byd.data.CarCommand
import java.io.IOException
import java.util.UUID

/**
 * The command pipeline's data types. Nothing in this package touches Android, so the whole bus
 * runs in plain JVM unit tests with a fake clock and a fake server.
 */

/** Where a command was tapped. The device that was tapped plays the result haptic, once. */
enum class Source(val onPhone: Boolean) {
    APP(true),
    SHORTCUT(true),
    WIDGET(true),
    CONFIRM(true),
    QS_TILE(true),
    WATCH(false),
}

/** Where a command is in its life. Only [DONE] is final; [CHECKING] = sent, answer unclear, being resolved. */
enum class Stage { QUEUED, WARMING, SENDING, WAITING_CAR, CHECKING, DONE }

/** How a command ended. */
enum class Outcome {
    /** The car did it (possibly only seen afterwards: [CommandRecord.late]). */
    CONFIRMED,

    /** Sent, but neither the car nor a later look at it confirmed it. Never resent automatically. */
    UNCONFIRMED,

    /** The car or the server said no. */
    REJECTED,

    /** Provably never reached the car. */
    NOT_SENT,

    /** Went wrong after it may have been sent. */
    FAILED,

    /** Waited too long before it could be sent (e.g. an Unlock minutes after the tap): dropped. */
    EXPIRED,

    /** A newer tap on the same control replaced it before it was sent. */
    SUPERSEDED,
}

data class CommandRequest(
    val command: CarCommand,
    val params: Map<String, String> = emptyMap(),
    val source: Source,
    val id: String = newRequestId(),
    /** Wall clock of the tap (the watch sends its own); null = now. */
    val tappedAt: Long? = null,
    /** What the car should report once it's done ("locked" = true…), to resolve a lost answer. */
    val expectKey: String? = null,
    val expectValue: Boolean? = null,
)

data class CommandRecord(
    val id: String,
    val command: CarCommand,
    val params: Map<String, String>,
    val source: Source,
    val stage: Stage,
    val outcome: Outcome? = null,
    val error: ApiError? = null,
    /** Wall-clock times: records outlive the process (and a reboot). */
    val createdAt: Long,
    val tappedAt: Long,
    val updatedAt: Long,
    val finishedAt: Long? = null,
    val attempts: Int = 0,
    /** The request id the server knows this command by. Changes only when a provably unsent command is retried. */
    val rid: String = id,
    /** The server holds a job for [rid] (new server, async submit). */
    val jobAccepted: Boolean = false,
    /** Confirmed only by a check after the answer was lost or slow. */
    val late: Boolean = false,
    /** Further taps (request ids) that joined this command instead of sending it again. */
    val joined: List<String> = emptyList(),
    val joinedSources: Set<Source> = emptySet(),
    val expectKey: String? = null,
    val expectValue: Boolean? = null,
    /** The result haptic has been played. */
    val feedback: Boolean = false,
) {
    val terminal: Boolean get() = stage == Stage.DONE
    val ok: Boolean get() = outcome == Outcome.CONFIRMED
    val key: String get() = keyOf(command, params)
    val sources: Set<Source> get() = joinedSources + source

    fun answers(requestId: String): Boolean = id == requestId || requestId in joined

    fun finish(outcome: Outcome, error: ApiError?, now: Long, late: Boolean = false) =
        copy(stage = Stage.DONE, outcome = outcome, error = error, finishedAt = now, updatedAt = now, late = late)
}

fun keyOf(command: CarCommand, params: Map<String, String>): String =
    command.name + params.toSortedMap().entries.joinToString("&", prefix = "?") { "${it.key}=${it.value}" }

fun newRequestId(): String = UUID.randomUUID().toString()

// ------------------------------------------------------------------ server

enum class ServerKind { UNKNOWN, LEGACY, V2 }

/** What the bus knows about the server. [lastContact] is the bus clock's monotonic time. */
data class ServerInfo(val kind: ServerKind = ServerKind.UNKNOWN, val boot: String? = null, val lastContact: Long? = null)

/** A job as the new server reports it (GET /jobs/<id>, or in the 202 of an async submit). */
data class ServerJob(
    val id: String,
    val state: String,
    val outcome: String? = null,
    val error: String? = null,
    /** "true" / "false" / "unknown": did it reach the car. */
    val sent: String? = null,
)

/** A parsed server reply. [body] is the JSON document for the car state (opaque to the bus). */
data class Wire(
    val http: Int,
    val ok: Boolean,
    val error: String? = null,
    val requestId: String? = null,
    val job: ServerJob? = null,
    val rev: Long? = null,
    val boot: String? = null,
    val retryAfterS: Int? = null,
    val sent: String? = null,
    /** The body was JSON. A proxy's error page (Render restarting) isn't. */
    val json: Boolean = true,
    val tookMs: Long = 0,
    val body: Any? = null,
)

data class Health(val v2: Boolean, val boot: String? = null, val warm: Boolean? = null)

/** The request failed before any of it went out: it can be sent again safely. */
class NotSentException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The request may have reached the server, but no answer came back. */
class LostReplyException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The network side of the bus. The app's implementation is CarApi; tests use a fake. */
interface CarTransport {
    /** GET /health?v=2. Throws [NotSentException] / [LostReplyException] when the server can't be reached. */
    suspend fun health(): Health

    /** POST a command route with X-Request-Id; `async` asks a new server for a 202 + job. */
    suspend fun command(path: String, query: Map<String, String>, requestId: String, async: Boolean): Wire

    /** GET /jobs/<id>. */
    suspend fun job(requestId: String): Wire
}

/** What the bus asks of the app around a command. */
interface BusHooks {
    /** A reply that may carry car state. */
    fun absorb(wire: Wire)

    /** One status read (fresh = wake the car); true when it got an answer. */
    suspend fun readStatus(fresh: Boolean): Boolean

    /** Does the car show [record]'s effect now? null = can't tell. */
    fun matches(record: CommandRecord): Boolean?

    /** The look at the car after a confirmed command (the A/C in particular). May do nothing. */
    suspend fun followUp(record: CommandRecord)
}

interface RecordStore {
    fun load(): List<CommandRecord>
    fun save(records: List<CommandRecord>)
}

interface BusClock {
    /** Wall clock, epoch millis (persisted times). */
    fun wall(): Long

    /** Monotonic millis (timeouts within one process). */
    fun mono(): Long
}

fun interface BusLogger {
    fun log(id: String?, message: String)
}
