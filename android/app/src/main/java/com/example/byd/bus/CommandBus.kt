package com.example.byd.bus

import com.example.byd.data.ApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Timings of the pipeline; tests shrink nothing, they run on virtual time. */
data class BusTuning(
    /** Waiting commands besides the one on its way. */
    val maxQueue: Int = 3,
    /** No answer from the server for this long: wake it (Render sleeps after ~15 min) before sending. */
    val warmAfterMs: Long = 10 * 60_000L,
    /** A sleeping Render instance can take a minute to start. */
    val warmBudgetMs: Long = 75_000L,
    val warmRetryMs: Long = 3_000L,
    /** Retries when the server can't even be connected to (no network): it isn't asleep, it's unreachable. */
    val warmConnectTries: Int = 3,
    /** From an accepted job to its final answer (server queue + car). */
    val jobBudgetMs: Long = 150_000L,
    /** After the server says "unconfirmed" it checks the car once more ("confirmed_late"). */
    val unconfirmedBudgetMs: Long = 60_000L,
    val pollFastMs: Long = 1_500L,
    val pollSlowMs: Long = 3_000L,
    val pollFastForMs: Long = 20_000L,
    /** Waits before the 2nd and 3rd try of a command that provably wasn't sent. */
    val retryDelaysMs: List<Long> = listOf(1_000L, 3_000L),
    val retryWindowMs: Long = 45_000L,
    /** Old server, answer lost: looks at the car this long after the command (cumulative 5, 20, 40 s). */
    val resolveReadsMs: List<Long> = listOf(5_000L, 15_000L, 20_000L),
    val followUpTimeoutMs: Long = 30_000L,
    /** A command interrupted by the app's death longer ago than this is not checked any more. */
    val recoverMaxAgeMs: Long = 10 * 60_000L,
    val keepRecords: Int = 30,
    val keepMs: Long = 24 * 60 * 60_000L,
)

/**
 * The one path every car command takes, whoever tapped it: the app, a launcher shortcut, a
 * widget, the A/C confirm dialog, a quick-settings tile or the watch.
 *
 * - One command on its way at a time, a short FIFO behind it. A repeat tap joins the command in
 *   flight; a newer tap on the same control replaces one that hasn't gone out; a command that
 *   waited too long is dropped instead of reaching the car late.
 * - Each command has an id. The new server (X-Request-Id, `?async=1`, `/jobs/<id>`) can then
 *   never run it twice; the old 2.7 server is detected and gets the old synchronous request.
 * - It is sent again only when it provably never went out. When the answer is lost, the server
 *   (or the car's state) is asked instead: a tailgate or horn is never repeated by guesswork.
 * - Every state change is persisted, so a command cut off by the app's death is reported
 *   ("not sent", or checked and then confirmed / unconfirmed) when the app runs again.
 */
class CommandBus(
    private val scope: CoroutineScope,
    private val transport: CarTransport,
    private val hooks: BusHooks,
    private val store: RecordStore,
    private val clock: BusClock,
    private val log: BusLogger,
    private val tuning: BusTuning = BusTuning(),
) {
    private val lock = Any()
    private val _records = MutableStateFlow<List<CommandRecord>>(emptyList())

    /** Recent commands, oldest first: the live ones and the last answers. */
    val records: StateFlow<List<CommandRecord>> = _records.asStateFlow()

    private val _idle = MutableStateFlow(true)

    /** Nothing queued, on its way, being checked or followed up. The keep-alive service waits for this. */
    val idle: StateFlow<Boolean> = _idle.asStateFlow()

    private val queue = ArrayDeque<String>()
    private var draining = false
    private var tasks = 0

    @Volatile
    var server = ServerInfo()
        private set

    // ------------------------------------------------------------------ lifecycle

    /** Loads the persisted commands. Those cut off by the app's death are settled or checked. */
    fun start() {
        val now = clock.wall()
        val loaded = try {
            store.load()
        } catch (e: Exception) {
            emptyList()
        }
        val toCheck = mutableListOf<CommandRecord>()
        val recovered = loaded.map { r ->
            when {
                r.terminal -> r
                r.stage == Stage.QUEUED || r.stage == Stage.WARMING -> {
                    log.log(r.id, "recovered ${r.command}: never sent (the app was closed)")
                    r.finish(Outcome.NOT_SENT, ApiError.INTERRUPTED, now)
                }
                now - r.updatedAt > tuning.recoverMaxAgeMs -> {
                    log.log(r.id, "recovered ${r.command}: too old to check")
                    r.finish(Outcome.UNCONFIRMED, ApiError.NOT_CONFIRMED, now)
                }
                else -> r.copy(stage = Stage.CHECKING, updatedAt = now).also { toCheck += it }
            }
        }
        synchronized(lock) { _records.value = prune(recovered, now) }
        persist()
        toCheck.forEach { r ->
            log.log(r.id, "recovered ${r.command}: was on its way, checking")
            startTask(r.id) { resolve(r.id, viaJob = r.jobAccepted) }
        }
        refreshIdle()
    }

    suspend fun awaitIdle() {
        idle.first { it }
    }

    fun record(requestId: String): CommandRecord? = _records.value.lastOrNull { it.answers(requestId) }

    /** A reply from the server outside the bus (status reads): tells whether it is up and which version. */
    fun noteReply(wire: Wire) = noteContact(wire)

    /** The result haptic of [id] has been played. */
    fun markFeedback(id: String) = update(id) { it.copy(feedback = true) }

    // ------------------------------------------------------------------ submit

    fun submit(request: CommandRequest): CommandRecord {
        val now = clock.wall()
        var kick = false
        val result = synchronized(lock) {
            val all = _records.value
            all.lastOrNull { it.answers(request.id) }?.let { return@synchronized it }

            // Same command already on its way: this tap joins it. A command being checked is
            // joined only when repeating it would undo it (tailgate, horn); otherwise it goes again.
            val key = keyOf(request.command, request.params)
            val same = all.firstOrNull {
                !it.terminal && it.key == key && (it.stage != Stage.CHECKING || CommandPolicy.isToggle(it.command))
            }
            if (same != null) {
                val joined = same.copy(joined = same.joined + request.id, joinedSources = same.joinedSources + request.source)
                replace(joined)
                log.log(same.id, "joined by ${request.source} ${short(request.id)}")
                return@synchronized joined
            }

            // A newer tap on the same control replaces one that hasn't gone out yet.
            val group = CommandPolicy.group(request.command)
            queue.toList().forEach { id ->
                val waiting = all.firstOrNull { it.id == id } ?: return@forEach
                if (waiting.stage == Stage.QUEUED && CommandPolicy.group(waiting.command) == group) {
                    queue.remove(id)
                    replace(waiting.finish(Outcome.SUPERSEDED, null, now))
                    log.log(id, "${waiting.command} replaced by ${request.command} before it went out")
                }
            }

            val record = CommandRecord(
                id = request.id,
                command = request.command,
                params = request.params,
                source = request.source,
                stage = Stage.QUEUED,
                createdAt = now,
                tappedAt = (request.tappedAt ?: now).coerceAtMost(now),
                updatedAt = now,
                expectKey = request.expectKey,
                expectValue = request.expectValue,
            )
            if (queue.size >= tuning.maxQueue) {
                record.finish(Outcome.NOT_SENT, ApiError.QUEUE_FULL, now).also {
                    _records.value = prune(_records.value + it, now)
                    log.log(it.id, "${request.source} ${request.command}: queue full, not sent")
                }
            } else {
                _records.value = prune(_records.value + record, now)
                queue.addLast(record.id)
                kick = true
                log.log(record.id, "${request.source} ${request.command}${paramsText(request.params)} queued (${queue.size} waiting)")
                record
            }
        }
        persist()
        refreshIdle()
        if (kick) kickWorker()
        return result
    }

    // ------------------------------------------------------------------ worker

    private fun kickWorker() {
        synchronized(lock) {
            if (draining) return
            draining = true
        }
        refreshIdle()
        scope.launch { drain() }
    }

    private suspend fun drain() {
        while (true) {
            val id = synchronized(lock) { queue.removeFirstOrNull().also { if (it == null) draining = false } } ?: break
            try {
                process(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log(id, "pipeline error ${e.javaClass.simpleName}")
                if (find(id)?.terminal == false) finish(id, Outcome.FAILED, ApiError.SERVER)
            }
        }
        refreshIdle()
    }

    private suspend fun process(id: String) {
        val r = find(id) ?: return
        if (r.terminal) return
        val waited = clock.wall() - r.tappedAt
        if (waited > CommandPolicy.maxDelayMs(r.command)) {
            log.log(id, "${r.command} ${waited / 1000} s after the tap: too late, not sent")
            finish(id, Outcome.EXPIRED, ApiError.TOO_LATE)
            return
        }
        if (needsWarmUp()) {
            stage(id, Stage.WARMING)
            if (!warmUp(id)) {
                finish(id, Outcome.NOT_SENT, ApiError.NETWORK)
                return
            }
        }
        val firstTry = clock.mono()
        var attempt = 0
        while (true) {
            attempt++
            update(id) { it.copy(stage = Stage.SENDING, attempts = attempt) }
            val step = if (server.kind == ServerKind.V2) sendAsync(id) else sendSync(id)
            when (step) {
                is Step.Done -> {
                    finish(id, step.outcome, step.error, step.late)
                    break
                }
                is Step.Uncertain -> {
                    stage(id, Stage.CHECKING)
                    startTask(id) { resolve(id, step.viaJob) }
                    return
                }
                is Step.NotSent -> {
                    val wait = step.retryAfterMs ?: tuning.retryDelaysMs.getOrNull(attempt - 1)
                    // A retry must not take a horn or an unlock past its moment (the server's own
                    // queue drops it after 20 s for the same reason).
                    val stillPrompt = wait != null && clock.wall() + wait - (find(id)?.tappedAt ?: 0L) <= CommandPolicy.maxDelayMs(r.command)
                    val again = CommandPolicy.autoRetry(step.error) && wait != null && stillPrompt &&
                        attempt <= tuning.retryDelaysMs.size && clock.mono() - firstTry + wait <= tuning.retryWindowMs
                    if (!again) {
                        finish(id, Outcome.NOT_SENT, step.error)
                        break
                    }
                    if (step.newRid) update(id) { it.copy(rid = "${it.id}.${attempt + 1}", jobAccepted = false) }
                    log.log(id, "not sent (${step.error}), trying again in $wait ms")
                    delay(wait!!)
                }
            }
        }
        val done = find(id)
        if (done?.outcome == Outcome.CONFIRMED) {
            startTask(id) { withTimeoutOrNull(tuning.followUpTimeoutMs) { hooks.followUp(done) } }
        }
    }

    // ------------------------------------------------------------------ server warm-up

    private fun needsWarmUp(): Boolean {
        val s = server
        val last = s.lastContact ?: return true
        return s.kind == ServerKind.UNKNOWN || clock.mono() - last > tuning.warmAfterMs
    }

    /** GET /health?v=2 until the server answers: wakes a sleeping instance and tells old from new. */
    private suspend fun warmUp(id: String): Boolean {
        val started = clock.mono()
        var connectFailures = 0
        while (true) {
            try {
                val health = transport.health()
                server = ServerInfo(if (health.v2) ServerKind.V2 else ServerKind.LEGACY, health.boot ?: server.boot, clock.mono())
                log.log(id, "server up (${if (health.v2) "v2" else "2.7"}) after ${clock.mono() - started} ms")
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Can't even connect: no network (a sleeping Render instance still accepts connections).
                if (e is NotSentException) connectFailures++
                log.log(id, "server not answering yet (${e.javaClass.simpleName})")
                val outOfTime = clock.mono() - started + tuning.warmRetryMs > tuning.warmBudgetMs
                if (outOfTime || connectFailures >= tuning.warmConnectTries) return false
                delay(tuning.warmRetryMs)
            }
        }
    }

    private fun noteContact(wire: Wire) {
        if (!wire.json) return // a proxy page: our server didn't answer
        val v2 = wire.rev != null || wire.boot != null
        val kind = when {
            v2 -> ServerKind.V2
            wire.http in 200..299 -> ServerKind.LEGACY
            else -> server.kind
        }
        val before = server
        server = ServerInfo(kind, wire.boot ?: before.boot, clock.mono())
        if (before.boot != null && wire.boot != null && before.boot != wire.boot) log.log(null, "server restarted")
    }

    // ------------------------------------------------------------------ sending

    private sealed interface Step {
        data class Done(val outcome: Outcome, val error: ApiError? = null, val late: Boolean = false) : Step

        /** Provably didn't go out. [newRid]: the server has a final job for the old id, so a retry needs a new one. */
        data class NotSent(val error: ApiError, val retryAfterMs: Long? = null, val newRid: Boolean = false) : Step

        /** May have gone out, answer unknown: look, never resend blindly. */
        data class Uncertain(val viaJob: Boolean) : Step
    }

    /** The new server: a 202 with a job right away, then the job is followed. */
    private suspend fun sendAsync(id: String): Step {
        val r = find(id) ?: return Step.Done(Outcome.FAILED, ApiError.SERVER)
        val bootBefore = server.boot
        val wire = try {
            transport.command(r.command.path, r.params, r.rid, async = true)
        } catch (e: NotSentException) {
            log.log(id, "not sent: ${e.message}")
            return Step.NotSent(ApiError.NETWORK)
        } catch (e: IOException) {
            log.log(id, "answer lost (${e.javaClass.simpleName}): asking the server about ${short(r.rid)}")
            return trackJob(id, null, lostReply = true, bootBefore = bootBefore)
        }
        noteContact(wire)
        hooks.absorb(wire)
        if (wire.job != null || wire.http == 202) {
            update(id) { it.copy(jobAccepted = true) }
            return trackJob(id, wire.job, lostReply = false, bootBefore = bootBefore)
        }
        return syncStep(wire)
    }

    /** The old server: one request that answers when the car has (or hasn't) done it. */
    private suspend fun sendSync(id: String): Step {
        val r = find(id) ?: return Step.Done(Outcome.FAILED, ApiError.SERVER)
        val wire = try {
            transport.command(r.command.path, r.params, r.rid, async = false)
        } catch (e: NotSentException) {
            log.log(id, "not sent: ${e.message}")
            return Step.NotSent(ApiError.NETWORK)
        } catch (e: IOException) {
            log.log(id, "sent, answer lost (${e.javaClass.simpleName}): checking the car instead of sending again")
            return Step.Uncertain(viaJob = false)
        }
        noteContact(wire)
        hooks.absorb(wire)
        if (wire.job != null) { // the server was upgraded meanwhile
            update(id) { it.copy(jobAccepted = true) }
            return trackJob(id, wire.job, lostReply = false, bootBefore = server.boot)
        }
        return syncStep(wire)
    }

    /**
     * A synchronous answer: the 2.7 server's, or the new server's refusals that come before a job
     * exists (401, 400 bad_request_id, 409 request_id_reused, 429 queue_full, 503 warming_up).
     */
    private fun syncStep(wire: Wire): Step {
        if (wire.ok) return Step.Done(Outcome.CONFIRMED)
        val code = wire.error
        val error = ApiError.from(wire.http, code)
        return when {
            !wire.json && wire.http == 503 -> Step.NotSent(ApiError.NETWORK) // Render: no instance to take it
            !wire.json -> Step.Uncertain(viaJob = false) // a proxy page instead of our answer: it may have run
            wire.http == 429 || code == "queue_full" -> Step.NotSent(ApiError.QUEUE_FULL, retryAfter(wire), newRid = true)
            code == "warming_up" -> Step.NotSent(ApiError.WARMING_UP, retryAfter(wire) ?: 3_000L, newRid = true)
            code == "busy" -> Step.NotSent(ApiError.BUSY, retryAfter(wire) ?: 3_000L, newRid = true)
            code == "timeout" || code == "unconfirmed" -> Step.Uncertain(viaJob = false) // the car may still do it
            wire.http == 401 || CommandPolicy.notSentCode(code) -> Step.Done(Outcome.NOT_SENT, error)
            code == "rejected" || code == "still_charging" -> Step.Done(Outcome.REJECTED, error)
            wire.sent == "false" -> Step.Done(Outcome.NOT_SENT, error) // the new server says so
            else -> Step.Done(Outcome.FAILED, error)
        }
    }

    private fun retryAfter(wire: Wire): Long? = wire.retryAfterS?.let { (it * 1000L).coerceIn(1_000L, 10_000L) }

    private sealed interface JobState {
        data class Running(val stage: Stage) : JobState

        /** Sent, not confirmed (yet): the server looks at the car again and may still say confirmed_late. */
        data class Watching(val ifNothingChanges: Step) : JobState
        data class Final(val step: Step) : JobState
    }

    /** server/API.md §4.4-4.5: state queued → running → sent → done; outcome once done. */
    private fun jobState(job: ServerJob): JobState {
        val word = (job.outcome?.takeIf { it.isNotBlank() } ?: job.state).lowercase()
        val code = job.error?.takeIf { it.isNotBlank() }
        val error = ApiError.from(200, code)
        return when (word) {
            "sent", "waiting_car" -> JobState.Running(Stage.WAITING_CAR) // it reached BYD: waiting for the car
            "confirmed" ->
                // The one confirmed-but-not-ok case: the pause was applied, the car keeps charging.
                if (code == "still_charging") JobState.Final(Step.Done(Outcome.REJECTED, ApiError.STILL_CHARGING))
                else JobState.Final(Step.Done(Outcome.CONFIRMED))
            "confirmed_late" -> JobState.Final(Step.Done(Outcome.CONFIRMED, late = true))
            "unconfirmed" -> JobState.Watching(Step.Done(Outcome.UNCONFIRMED, ApiError.NOT_CONFIRMED))
            "rejected" -> JobState.Final(Step.Done(Outcome.REJECTED, ApiError.from(200, code ?: "rejected")))
            "not_sent" -> JobState.Final(Step.NotSent(error, newRid = true))
            "failed" -> when (job.sent) {
                "false" -> JobState.Final(Step.NotSent(error, newRid = true)) // PIN, login, not supported…: retried only if transient
                "true" -> JobState.Final(Step.Done(Outcome.FAILED, error))
                else -> JobState.Watching(Step.Done(Outcome.FAILED, error)) // lost reply to the send: may still turn confirmed_late
            }
            else -> JobState.Running(Stage.SENDING) // queued, running, or a state this app doesn't know yet
        }
    }

    /**
     * Follows a job on the new server until it is final. After a lost answer ([lostReply]) the
     * server is asked first: a job it never got is sent again under the same id (it can't run
     * twice); only when the server may have lost it in a restart is a toggle left alone.
     */
    private suspend fun trackJob(id: String, first: ServerJob?, lostReply: Boolean, bootBefore: String?): Step {
        val started = clock.mono()
        var job = first
        var seen = first != null
        var unconfirmedSince: Long? = null
        while (true) {
            job?.let { j ->
                when (val s = jobState(j)) {
                    is JobState.Final -> return s.step
                    is JobState.Running -> if (find(id)?.stage != s.stage) stage(id, s.stage)
                    is JobState.Watching -> {
                        val since = unconfirmedSince ?: clock.mono().also {
                            unconfirmedSince = it
                            stage(id, Stage.CHECKING)
                        }
                        if (clock.mono() - since > tuning.unconfirmedBudgetMs) return s.ifNothingChanges
                    }
                }
            }
            if (unconfirmedSince == null && clock.mono() - started > tuning.jobBudgetMs) {
                log.log(id, "no final answer from the server in time")
                return Step.Uncertain(viaJob = false)
            }
            delay(if (clock.mono() - started < tuning.pollFastForMs) tuning.pollFastMs else tuning.pollSlowMs)
            val r = find(id) ?: return Step.Done(Outcome.FAILED, ApiError.SERVER)
            val wire = try {
                transport.job(r.rid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue // the job lives on the server: a dropped poll loses nothing
            }
            if (wire.http == 404) {
                val sameBoot = bootBefore != null && wire.boot == bootBefore
                return when {
                    seen || !lostReply -> {
                        log.log(id, "the server no longer knows the job (restarted?): checking the car")
                        Step.Uncertain(viaJob = false)
                    }
                    wire.error != "unknown_job" -> Step.Uncertain(viaJob = false) // no /jobs on this server
                    sameBoot || !CommandPolicy.neverRepeat(r.command) -> {
                        log.log(id, "the server never got it: sending again under the same id")
                        Step.NotSent(ApiError.NETWORK)
                    }
                    else -> {
                        log.log(id, "server restarted, ${r.command} may have run: not repeating it")
                        Step.Uncertain(viaJob = false)
                    }
                }
            }
            noteContact(wire)
            hooks.absorb(wire)
            wire.job?.let {
                job = it
                seen = true
                if (!r.jobAccepted) update(id) { rec -> rec.copy(jobAccepted = true) }
            }
        }
    }

    /** Sent, answer unknown: the server's job if it has one, else the car's own state decides. */
    private suspend fun resolve(id: String, viaJob: Boolean) {
        if (viaJob) {
            when (val step = trackJob(id, null, lostReply = false, bootBefore = server.boot)) {
                is Step.Done -> return finish(id, step.outcome, step.error, step.late)
                is Step.NotSent -> return finish(id, Outcome.NOT_SENT, step.error)
                is Step.Uncertain -> Unit
            }
        }
        if (find(id)?.stage != Stage.CHECKING) stage(id, Stage.CHECKING)
        for ((i, wait) in tuning.resolveReadsMs.withIndex()) {
            delay(wait)
            val answered = try {
                hooks.readStatus(fresh = i > 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            val r = find(id) ?: return
            when (hooks.matches(r)) {
                true -> {
                    log.log(id, "the car shows ${r.command} done")
                    return finish(id, Outcome.CONFIRMED, null, late = true)
                }
                null -> if (answered) break // nothing in the car's report tells
                false -> Unit
            }
        }
        finish(id, Outcome.UNCONFIRMED, ApiError.NOT_CONFIRMED)
    }

    // ------------------------------------------------------------------ bookkeeping

    private fun find(id: String): CommandRecord? = _records.value.firstOrNull { it.id == id }

    private fun replace(record: CommandRecord) {
        _records.value = _records.value.map { if (it.id == record.id) record else it }
    }

    private fun update(id: String, change: (CommandRecord) -> CommandRecord) {
        var changed = false
        synchronized(lock) {
            _records.value = _records.value.map {
                if (it.id != id) return@map it
                val next = change(it)
                if (next == it) it else next.copy(updatedAt = clock.wall()).also { changed = true }
            }
        }
        if (changed) persist()
    }

    private fun stage(id: String, stage: Stage) {
        update(id) { it.copy(stage = stage) }
        log.log(id, "→ $stage")
    }

    private fun finish(id: String, outcome: Outcome, error: ApiError?, late: Boolean = false) {
        val now = clock.wall()
        update(id) { it.finish(outcome, error, now, late) }
        val r = find(id)
        log.log(id, "${r?.command}: $outcome${error?.let { " ($it)" }.orEmpty()}${if (late) " (late)" else ""}, ${now - (r?.createdAt ?: now)} ms after the tap")
        refreshIdle()
    }

    private fun startTask(id: String, block: suspend () -> Unit) {
        synchronized(lock) { tasks++ }
        refreshIdle()
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.log(id, "check failed (${e.javaClass.simpleName})")
                if (find(id)?.terminal == false) finish(id, Outcome.UNCONFIRMED, ApiError.NOT_CONFIRMED)
            } finally {
                synchronized(lock) { tasks-- }
                refreshIdle()
            }
        }
    }

    private fun refreshIdle() {
        _idle.value = synchronized(lock) { tasks == 0 && !draining && queue.isEmpty() && _records.value.all { it.terminal } }
    }

    private fun persist() {
        try {
            store.save(_records.value)
        } catch (e: Exception) {
            log.log(null, "could not save commands (${e.javaClass.simpleName})")
        }
    }

    private fun prune(list: List<CommandRecord>, now: Long): List<CommandRecord> {
        val live = list.filter { !it.terminal }
        val done = list.filter { it.terminal && now - (it.finishedAt ?: it.updatedAt) < tuning.keepMs }
            .sortedByDescending { it.finishedAt ?: it.updatedAt }
            .take(tuning.keepRecords)
        return (live + done).sortedBy { it.createdAt }
    }

    private fun paramsText(params: Map<String, String>) =
        if (params.isEmpty()) "" else params.toSortedMap().entries.joinToString(",", " [", "]") { "${it.key}=${it.value}" }

    companion object {
        fun short(id: String): String = id.take(8)
    }
}
