package com.example.byd.system

import android.content.Context
import com.example.byd.bus.CommandRecord
import com.example.byd.bus.Outcome
import com.example.byd.bus.Outcomes
import com.example.byd.bus.Source
import com.example.byd.data.CarCommand
import com.example.byd.data.CarUiState
import com.example.byd.data.ClimateMode
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the watch in step with the phone. Only the phone talks to the server: the watch sends its
 * taps here as messages ([WearListener]) and shows the state published below, which the Wearable
 * Data Layer hands to the watch whenever it changes. Neither side polls.
 *
 * Protocol 2 (app 2.8): a tap is "COMMAND|id|tapAt"; the phone acks it at once on [PATH_ACK] and
 * then publishes the command's record under that id ("records") until it is final; the watch
 * matches answers by id, never by clock. A 2.7 watch sends just "COMMAND" and reads the per-command
 * keys (pending, ok_/err_/at_), which are still published. The paths and keys are repeated in the
 * watch app (wear/…/PhoneLink.kt and WatchState.kt).
 */
object WearSync {
    const val PATH_STATE = "/byd/state"
    const val PATH_COMMAND = "/byd/command"
    const val PATH_REFRESH = "/byd/refresh"
    const val PATH_ACK = "/byd/ack"
    const val PATH_LOG_REQUEST = "/byd/log-request"
    const val PATH_LOG = "/byd/log"
    const val PROTOCOL = 2

    /** What the watch may ask for. Anything else in a message is refused. */
    val WATCH_COMMANDS = setOf(
        CarCommand.LOCK, CarCommand.UNLOCK, CarCommand.TRUNK, CarCommand.QUICK_COOL, CarCommand.QUICK_HEAT,
        CarCommand.CLIMATE_OFF,
    )

    /** How long a watch command's record stays published after its last change. */
    private const val RECORD_WINDOW_MS = 10 * 60_000L

    /** A tap from the watch: "LOCK" (2.7) or "LOCK|<id>|<tapAt>" (2.8). */
    data class WatchTap(val command: String, val id: String?, val tapAt: Long?)

    fun parse(payload: String): WatchTap? {
        val parts = payload.trim().split('|')
        val command = parts.firstOrNull()?.trim().orEmpty()
        if (command.isEmpty()) return null
        return WatchTap(command, parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }, parts.getOrNull(2)?.trim()?.toLongOrNull())
    }

    /** What the watch shows. Published only when this changes, so the Data Layer stays quiet. */
    data class Snapshot(
        val name: String,
        val soc: Int?,
        val rangeKm: Int?,
        val locked: Boolean?,
        val trunkOpen: Boolean?,
        val climateOn: Boolean?,
        val mode: ClimateMode?,
        val climateUntil: Long?,
        val charging: Boolean?,
        val chargeKw: Double?,
        val pending: List<String>,
        val results: Map<String, Answer>, // 2.7 watches: the last answer per command
        val records: List<WatchRecord>, // 2.8 watches: the watch's commands, by request id
        val updatedAt: Long,
        val online: Boolean?,
    )

    data class Answer(val ok: Boolean, val error: String?, val at: Long)

    /** One watch command as the watch shows it: the phone's own words and tone (see Outcomes). */
    data class WatchRecord(
        val id: String,
        val command: String,
        val stage: String,
        val outcome: String,
        val tone: String,
        val label: String,
        val message: String,
        val updated: Long,
        val terminal: Boolean,
    )

    fun snapshot(context: Context, state: CarUiState): Snapshot {
        val car = state.snapshot
        val now = System.currentTimeMillis()
        val watchCommands = state.records.filter { it.command in WATCH_COMMANDS && it.outcome != Outcome.SUPERSEDED }
        val results = watchCommands.filter { it.terminal }
            .groupBy { it.command }
            .mapValues { (_, list) -> list.maxBy { it.finishedAt ?: 0L } }
            .map { (command, r) -> command.name to Answer(r.ok, r.error?.name ?: r.outcome?.name, r.finishedAt ?: r.updatedAt) }
            .toMap()
        val records = state.records
            .filter { Source.WATCH in it.sources && now - it.updatedAt < RECORD_WINDOW_MS }
            .flatMap { r -> (listOf(r.id) + r.joined).map { id -> watchRecord(context, r, id) } }
        return Snapshot(
            name = car.name,
            soc = car.soc,
            rangeKm = car.rangeKm,
            locked = car.locked,
            trunkOpen = car.trunkOpen,
            climateOn = car.climateOn,
            mode = state.climateMode,
            climateUntil = car.climateUntil,
            charging = car.charging,
            chargeKw = car.chargingKw,
            pending = watchCommands.filter { !it.terminal }.map { it.command.name }.distinct().sorted(),
            results = results,
            records = records,
            updatedAt = car.updatedAt,
            online = state.online,
        )
    }

    private fun watchRecord(context: Context, r: CommandRecord, id: String): WatchRecord {
        val display = Outcomes.of(r)
        return WatchRecord(
            id = id,
            command = r.command.name,
            stage = r.stage.name,
            outcome = r.outcome?.name.orEmpty(),
            tone = display.tone.name,
            label = context.getString(display.label),
            message = context.getString(display.message),
            updated = r.updatedAt,
            terminal = r.terminal,
        )
    }

    @Volatile
    private var unavailable = false // no Wearable API on this phone: stop trying

    suspend fun publish(context: Context, s: Snapshot) {
        if (unavailable) return
        val request = PutDataMapRequest.create(PATH_STATE).apply {
            dataMap.putInt("proto", PROTOCOL)
            dataMap.putLong("published", System.currentTimeMillis())
            dataMap.putString("name", s.name)
            dataMap.putInt("soc", s.soc ?: -1)
            dataMap.putInt("range", s.rangeKm ?: -1)
            dataMap.putInt("locked", s.locked.code)
            dataMap.putInt("trunk", s.trunkOpen.code)
            dataMap.putInt("climate", s.climateOn.code)
            dataMap.putString("mode", s.mode?.name?.lowercase() ?: "")
            dataMap.putLong("until", s.climateUntil ?: 0L)
            dataMap.putInt("charging", s.charging.code)
            dataMap.putDouble("kw", s.chargeKw ?: 0.0)
            dataMap.putStringArray("pending", s.pending.toTypedArray())
            s.results.forEach { (command, answer) ->
                dataMap.putBoolean("ok_$command", answer.ok)
                dataMap.putString("err_$command", answer.error ?: "")
                dataMap.putLong("at_$command", answer.at)
            }
            dataMap.putDataMapArrayList(
                "records",
                ArrayList(
                    s.records.map { r ->
                        DataMap().apply {
                            putString("id", r.id)
                            putString("cmd", r.command)
                            putString("stage", r.stage)
                            putString("outcome", r.outcome)
                            putString("tone", r.tone)
                            putString("label", r.label)
                            putString("msg", r.message)
                            putLong("updated", r.updated)
                            putBoolean("final", r.terminal)
                        }
                    },
                ),
            )
            dataMap.putLong("updated", s.updatedAt)
            dataMap.putInt("online", s.online.code)
        }.asPutDataRequest().setUrgent()
        try {
            Wearable.getDataClient(context).putDataItem(request).await()
        } catch (e: Exception) {
            // ApiException API_UNAVAILABLE: no Wear OS on this phone. Anything else: try next time.
            if (e is com.google.android.gms.common.api.ApiException && e.statusCode == 17) unavailable = true
        }
    }

    /** "Got it": sent the moment a watch tap arrives, before anything else happens. Never waited on. */
    fun ack(context: Context, nodeId: String, id: String, state: String) {
        if (unavailable) return
        runCatching { Wearable.getMessageClient(context).sendMessage(nodeId, PATH_ACK, "$id|$state".toByteArray()) }
    }

    // ------------------------------------------------------------------ the watch's debug log

    @Volatile
    private var watchLog: CompletableDeferred<String>? = null

    /** Asks the watch for its log (Settings → Share debug log); null if no watch answers in time. */
    suspend fun requestWatchLog(context: Context): String? {
        if (unavailable) return null
        val answer = CompletableDeferred<String>().also { watchLog = it }
        return try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            if (nodes.isEmpty()) return null
            nodes.forEach { Wearable.getMessageClient(context).sendMessage(it.id, PATH_LOG_REQUEST, ByteArray(0)).await() }
            withTimeoutOrNull(4_000) { answer.await() }
        } catch (e: Exception) {
            null
        } finally {
            watchLog = null
        }
    }

    fun onWatchLog(text: String) {
        watchLog?.complete(text)
    }

    private val Boolean?.code: Int get() = when (this) {
        true -> 1
        false -> 0
        null -> -1
    }
}
