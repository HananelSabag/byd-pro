package com.example.byd.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.byd.BuildConfig
import com.example.byd.bus.AndroidClock
import com.example.byd.bus.BusHooks
import com.example.byd.bus.BusLog
import com.example.byd.bus.CommandBus
import com.example.byd.bus.CommandRecord
import com.example.byd.bus.CommandRequest
import com.example.byd.bus.Outcome
import com.example.byd.bus.Outcomes
import com.example.byd.bus.PrefsRecordStore
import com.example.byd.bus.Redactor
import com.example.byd.bus.Source
import com.example.byd.bus.Stage
import com.example.byd.bus.Wire
import com.example.byd.bus.newRequestId
import com.example.byd.system.Buzz
import com.example.byd.system.ChargeWatch
import com.example.byd.system.WearSync
import com.example.byd.widget.WidgetRenderer
import com.example.byd.work.CommandService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class CarUiState(
    val snapshot: CarSnapshot,
    val refreshing: Boolean = false,
    val online: Boolean? = null,
    /** Recent commands from every source (app, widgets, tiles, watch), oldest first. */
    val records: List<CommandRecord> = emptyList(),
    val latencyMs: Long? = null,
    val climateSentTemp: Int? = null, // what the running A/C was started with
    /** The car's A/C report contradicts a command it just confirmed; it is being looked at again. */
    val climateChecking: Boolean = false,
) {
    /**
     * Heating or cooling while the A/C runs: its set temperature (the car's report, else what this
     * app started it with) against the cabin's. Null while it's off, or when nothing says.
     */
    val climateMode: ClimateMode?
        get() {
            if (snapshot.climateOn != true) return null
            val set = snapshot.climateSetC ?: climateSentTemp ?: return null
            val heating = snapshot.cabinC?.let { set > it } ?: (set >= 24)
            return if (heating) ClimateMode.HEAT else ClimateMode.COOL
        }

    /** Commands not answered yet → when they were tapped (wall clock). */
    val pending: Map<CarCommand, Long>
        get() = records.filter { !it.terminal }.associate { it.command to it.createdAt }

    /** The newest answered command (not one a newer tap replaced). */
    val lastResult: CommandRecord?
        get() = records.filter { it.terminal && it.outcome != Outcome.SUPERSEDED }.maxByOrNull { it.finishedAt ?: 0L }

    /** The newest command of [commands]: what their control shows. */
    fun latest(commands: Collection<CarCommand>): CommandRecord? =
        records.lastOrNull { it.command in commands && it.outcome != Outcome.SUPERSEDED }

    fun isPending(commands: Collection<CarCommand>): Boolean = records.any { !it.terminal && it.command in commands }

    /** A command is waiting for a sleeping server to start. */
    val warming: Boolean get() = records.any { it.stage == Stage.WARMING }
}

enum class ClimateMode { COOL, HEAT }

/**
 * Single source of truth for the app, the widgets, the quick-settings tiles and the watch (one
 * process, one state). Every command goes through [bus]; work runs in an app-wide scope, so a
 * command finishes and updates every surface even if the screen that started it is gone.
 */
class CarRepository private constructor(context: Context) {
    private val app = context.applicationContext
    val prefs = AppPrefs(app)
    private val api = CarApi(prefs)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The shareable debug log (Settings → Share debug log). */
    val log = BusLog(
        File(app.filesDir, "buslog"),
        Redactor { listOfNotNull(prefs.secret, prefs.serverUrl, prefs.serverUrl.toHttpUrlOrNull()?.host) },
    )

    private var doc: JSONObject = prefs.stateDoc
    private val _state = MutableStateFlow(
        CarUiState(snapshot = CarSnapshot.from(doc), climateSentTemp = prefs.climateSentTemp.takeIf { it > 0 }),
    )
    val state: StateFlow<CarUiState> = _state.asStateFlow()

    private val inflight = Any()
    private var refreshTask: Deferred<Boolean>? = null
    private var freshTask: Deferred<Boolean>? = null
    private var climateExpiry: Job? = null
    private var climateRecheck: Job? = null
    private var redraw: Job? = null
    private var lastFetchAt = 0L // elapsedRealtime of the last status reply
    private var lastRev = -1L
    private var lastBoot: String? = null
    private var climateHold: ClimateHold? = null
    private val handled = HashSet<String>() // answered commands whose side effects (haptic, prefs) ran

    private val hooks = object : BusHooks {
        override fun absorb(wire: Wire) = absorbWire(wire, status = false)
        override suspend fun readStatus(fresh: Boolean): Boolean = refresh(fresh)
        override fun matches(record: CommandRecord): Boolean? = showsDone(record)
        override suspend fun followUp(record: CommandRecord) {
            if (record.command !in VERIFIED) return
            delay(VERIFY_DELAY_MS) // after the server's short A/C hold, so the car's own report shows
            refresh(fresh = true)
        }
    }

    /** The one command pipeline (see [CommandBus]). */
    val bus = CommandBus(scope, api, hooks, PrefsRecordStore(app), AndroidClock, log)

    init {
        if (BuildConfig.DEBUG) log.echo = { Log.d("BydBus", it) }
        expireClimateAt(_state.value.snapshot.climateUntil)
        bus.start()
        // Answered before this process started: shown, but their haptic and side effects already ran.
        bus.records.value.filter { it.terminal }.forEach { handled += it.id }
        scope.launch { bus.records.collect(::onRecords) }
        // The watch shows what the phone knows; it gets a new copy only when that changes.
        scope.launch {
            state.map { WearSync.snapshot(app, it) }.distinctUntilChanged().collect { WearSync.publish(app, it) }
        }
    }

    // ------------------------------------------------------------------ commands

    /**
     * Sends a command from any source and keeps the app alive (a short foreground service) until
     * it, its check and the widgets' answer are done. Returns at once; the record follows in [state].
     */
    fun run(
        command: CarCommand,
        params: Map<String, String> = emptyMap(),
        source: Source,
        id: String = newRequestId(),
        tappedAt: Long? = null,
    ): CommandRecord {
        val record = submit(command, params, source, id, tappedAt)
        if (!record.terminal) CommandService.keepAlive(app)
        return record
    }

    /** Puts a command on the bus without starting the keep-alive service (tests, callers that hold one). */
    fun submit(
        command: CarCommand,
        params: Map<String, String> = emptyMap(),
        source: Source,
        id: String = newRequestId(),
        tappedAt: Long? = null,
    ): CommandRecord {
        if (command == CarCommand.CHARGE_NOW || command == CarCommand.CHARGE_PAUSE) ChargeWatch.quietForOwnCommand()
        val expect = expectation(command)
        return bus.submit(
            CommandRequest(command, withClimateParams(command, params), source, id, tappedAt, expect?.first, expect?.second),
        )
    }

    /** Climate toggle used by widgets: stop it if it is known to be running, otherwise start it. */
    fun climateToggle(): CarCommand =
        if (state.value.snapshot.climateOn == true) CarCommand.CLIMATE_OFF else CarCommand.CLIMATE_ON

    /** Quick cool / heat as a toggle (widgets, watch): stop that mode while it runs, otherwise start it. */
    fun quickToggle(mode: ClimateMode): CarCommand = when {
        state.value.climateMode == mode -> CarCommand.CLIMATE_OFF
        mode == ClimateMode.HEAT -> CarCommand.QUICK_HEAT
        else -> CarCommand.QUICK_COOL
    }

    private fun withClimateParams(command: CarCommand, params: Map<String, String>): Map<String, String> {
        val temp = when (command) {
            CarCommand.CLIMATE_ON -> prefs.climateTemp
            CarCommand.QUICK_COOL -> COOL_TEMP
            CarCommand.QUICK_HEAT -> HEAT_TEMP
            else -> return params
        }
        return mapOf(
            "temp" to temp.toString(),
            "mins" to prefs.climateMinutes.toString(),
            "air" to if (prefs.climateRecirculate) "recirc" else "fresh",
        ) + params
    }

    /** What the car reports once [command] is done: lets a lost answer be resolved by looking. */
    private fun expectation(command: CarCommand): Pair<String, Boolean>? = when (command) {
        CarCommand.LOCK -> "locked" to true
        CarCommand.UNLOCK -> "locked" to false
        CarCommand.CLIMATE_ON, CarCommand.QUICK_COOL, CarCommand.QUICK_HEAT -> "climate_on" to true
        CarCommand.CLIMATE_OFF -> "climate_on" to false
        CarCommand.TRUNK -> state.value.snapshot.trunkOpen?.let { "trunk_open" to !it }
        CarCommand.WINDOWS_CLOSE -> "windows_open" to false
        CarCommand.WINDOWS_VENT -> "windows_open" to true
        CarCommand.CHARGE_NOW -> "charging" to true
        CarCommand.CHARGE_PAUSE -> "charging" to false
        else -> null
    }

    private fun showsDone(record: CommandRecord): Boolean? {
        val want = record.expectValue ?: return null
        val car = state.value.snapshot
        val now = when (record.expectKey) {
            "locked" -> car.locked
            "climate_on" -> car.climateOn
            "trunk_open" -> car.trunkOpen
            "windows_open" -> car.windowsOpen
            "charging" -> car.charging
            else -> return null
        }
        return now == want
    }

    private fun onRecords(records: List<CommandRecord>) {
        _state.update { it.copy(records = records) }
        records.filter { it.terminal && it.id !in handled }.forEach {
            handled += it.id
            onAnswered(it)
        }
        WidgetRenderer.updateAll(app, _state.value)
        scheduleRedraw(records)
    }

    private fun onAnswered(r: CommandRecord) {
        if (r.ok) {
            _state.update { it.copy(online = true) }
            if (r.command in CarCommand.CLIMATE && r.command != CarCommand.RECIRC) {
                r.params["temp"]?.toIntOrNull()?.let { sent ->
                    prefs.climateSentTemp = sent
                    _state.update { it.copy(climateSentTemp = sent) }
                }
                holdClimate(on = r.command != CarCommand.CLIMATE_OFF)
            }
            if (r.command == CarCommand.CHARGE_SCHEDULE) {
                r.params["mode"]?.let { prefs.scheduleMode = it }
                if (prefs.chargeAlerts) ChargeWatch.scheduleChanged(app, _state.value.snapshot)
            }
        } else if (r.outcome == Outcome.NOT_SENT && r.error == ApiError.NETWORK) {
            _state.update { it.copy(online = false) }
        }
        // The result haptic: once per command, on the phone, when the tap was made on the phone.
        val recent = System.currentTimeMillis() - (r.finishedAt ?: 0L) < FEEDBACK_MAX_AGE_MS
        if (!r.feedback && recent && r.sources.any { it.onPhone }) {
            bus.markFeedback(r.id)
            if (prefs.haptics) Buzz.result(app, Outcomes.of(r).feel)
        }
    }

    /** Widgets show an answer for [Outcomes.SHOW_MS]; redraw them when that ends (nothing else would). */
    private fun scheduleRedraw(records: List<CommandRecord>) {
        val now = System.currentTimeMillis()
        val next = records.mapNotNull { r -> r.finishedAt?.plus(Outcomes.SHOW_MS)?.takeIf { it > now } }.minOrNull() ?: return
        redraw?.cancel()
        redraw = scope.launch {
            delay(next - now + 50)
            WidgetRenderer.updateAll(app, _state.value)
            scheduleRedraw(_state.value.records)
        }
    }

    // ------------------------------------------------------------------ status

    /**
     * Re-reads the car. Callers arriving while a read runs share it. `fresh` makes the server wake
     * the car, so a pull-to-refresh never settles for a cached read that happens to be running.
     */
    suspend fun refresh(fresh: Boolean = false): Boolean {
        val task = synchronized(inflight) {
            if (fresh) {
                freshTask?.takeIf { it.isActive } ?: scope.async { doRefresh(true) }.also { freshTask = it }
            } else {
                (freshTask?.takeIf { it.isActive } ?: refreshTask?.takeIf { it.isActive })
                    ?: scope.async { doRefresh(false) }.also { refreshTask = it }
            }
        }
        return task.await()
    }

    fun refreshAsync(fresh: Boolean = false) {
        scope.launch { refresh(fresh) }
    }

    /**
     * The app came to the front: read the car, unless the state is from the last few seconds.
     * Nothing polls in the background; this, a pull-down and the checks after a command are all.
     */
    fun refreshIfStale() {
        scope.launch { refreshUnlessRecent() }
    }

    suspend fun refreshUnlessRecent() {
        if (lastFetchAt == 0L || SystemClock.elapsedRealtime() - lastFetchAt > REFRESH_THROTTLE_MS) refresh()
    }

    fun markRefreshing() {
        _state.update { it.copy(refreshing = true) }
        WidgetRenderer.updateAll(app, _state.value)
    }

    suspend fun ping(baseUrl: String): Long = api.ping(baseUrl)

    private suspend fun doRefresh(fresh: Boolean): Boolean {
        val started = System.currentTimeMillis()
        _state.update { it.copy(refreshing = true) }
        WidgetRenderer.updateAll(app, _state.value)
        val ok = try {
            val reply = api.status(fresh)
            bus.noteReply(reply)
            // The new server numbers its states (rev); the old one doesn't, so a read that a
            // command overtook is dropped: it would undo the command's newer state.
            if (reply.rev != null || !overtaken(started)) absorbWire(reply, status = true)
            lastFetchAt = SystemClock.elapsedRealtime()
            _state.update { it.copy(online = true, latencyMs = reply.tookMs) }
            reply.ok
        } catch (e: IOException) {
            log.log(null, "status read failed (${e.javaClass.simpleName})")
            _state.update { it.copy(online = false) }
            false
        } finally {
            _state.update { it.copy(refreshing = false) }
        }
        WidgetRenderer.updateAll(app, _state.value)
        return ok
    }

    private fun overtaken(startedWall: Long): Boolean = bus.records.value.any { r ->
        r.stage == Stage.SENDING || r.stage == Stage.WAITING_CAR ||
            (r.outcome == Outcome.CONFIRMED && !r.late && (r.finishedAt ?: 0L) > startedWall)
    }

    private fun absorbWire(wire: Wire, status: Boolean) {
        val json = wire.body as? JSONObject ?: return
        // A job reply may nest the whole state document under "car".
        val response = json.optJSONObject("car")?.takeIf { it.has("car") } ?: json
        if (!response.has("car")) return
        wire.rev?.let { rev ->
            if (wire.boot == lastBoot && rev < lastRev) {
                log.log(null, "older car state ignored (rev $rev < $lastRev)")
                return
            }
            lastRev = rev
            lastBoot = wire.boot
        }
        absorb(response, mirror = status)
    }

    private fun absorb(json: JSONObject, mirror: Boolean) {
        val before = _state.value.snapshot
        doc = holdClimateAgainst(CarSnapshot.merge(doc, json, mirror))
        prefs.stateDoc = doc
        val after = CarSnapshot.from(doc)
        _state.update { it.copy(snapshot = after) }
        if (after.climateUntil != before.climateUntil) expireClimateAt(after.climateUntil)
        if (prefs.chargeAlerts) ChargeWatch.onUpdate(app, before, after)
    }

    // ------------------------------------------------------------------ A/C contradiction rule

    private data class ClimateHold(val on: Boolean, val until: Long, val strikes: Int = 0)

    private fun holdClimate(on: Boolean) {
        climateHold = ClimateHold(on, System.currentTimeMillis() + CLIMATE_HOLD_MS)
        _state.update { it.copy(climateChecking = false) }
    }

    /**
     * The car's A/C report lags: right after it confirmed a start it can still say "off". Within
     * [CLIMATE_HOLD_MS] of a confirmed A/C command, one contradicting report doesn't flip the
     * screen: the A/C shows as commanded ("checking"), the car is read again, and only a second
     * report that agrees with the first wins.
     */
    private fun holdClimateAgainst(merged: JSONObject): JSONObject {
        val hold = climateHold ?: return merged
        if (System.currentTimeMillis() > hold.until) {
            climateHold = null
            _state.update { it.copy(climateChecking = false) }
            return merged
        }
        val car = merged.optJSONObject("car") ?: return merged
        if (!car.has("climate_on") || car.isNull("climate_on")) return merged
        val reported = car.optBoolean("climate_on")
        if (reported == hold.on) {
            if (hold.strikes > 0) climateHold = hold.copy(strikes = 0)
            _state.update { it.copy(climateChecking = false) }
            return merged
        }
        if (hold.strikes + 1 >= 2) {
            log.log(null, "A/C: the car reports ${onOff(reported)} again, accepted")
            climateHold = null
            _state.update { it.copy(climateChecking = false) }
            return merged
        }
        log.log(null, "A/C: the car reports ${onOff(reported)} right after confirming ${onOff(hold.on)}: checking again")
        climateHold = hold.copy(strikes = hold.strikes + 1)
        car.put("climate_on", hold.on)
        _state.update { it.copy(climateChecking = true) }
        if (climateRecheck?.isActive != true) {
            climateRecheck = scope.launch {
                delay(CLIMATE_RECHECK_MS)
                refresh(fresh = true)
            }
        }
        return merged
    }

    private fun onOff(on: Boolean) = if (on) "on" else "off"

    /** When a known A/C run ends, the screen and widgets show it off: no car read needed. */
    private fun expireClimateAt(until: Long?) {
        climateExpiry?.cancel()
        climateExpiry = until?.let {
            scope.launch {
                delay((it - System.currentTimeMillis()).coerceAtLeast(0) + 500)
                _state.update { s ->
                    if (s.snapshot.climateUntil == it) s.copy(snapshot = s.snapshot.copy(climateReported = false)) else s
                }
                WidgetRenderer.updateAll(app, _state.value)
            }
        }
    }

    companion object {
        /** How long widgets and buttons keep showing a command's answer before returning to normal. */
        const val RESULT_SHOW_MS = Outcomes.SHOW_MS

        /** Opening the app again within this long doesn't read the car again. */
        private const val REFRESH_THROTTLE_MS = 20_000L

        /** The check after a command: after the server's short A/C hold (6 s), so the car's own report shows. */
        private const val VERIFY_DELAY_MS = 8_000L

        /** After a confirmed A/C command, one contradicting car report is checked again before it wins. */
        private const val CLIMATE_HOLD_MS = 45_000L
        private const val CLIMATE_RECHECK_MS = 15_000L

        /** A result older than this when the app sees it (it was dead meanwhile) doesn't buzz any more. */
        private const val FEEDBACK_MAX_AGE_MS = 10_000L

        /** Quick cool / heat: the ends of the ATTO 3's range. */
        const val COOL_TEMP = 15
        const val HEAT_TEMP = 31

        /** Commands whose result is worth one look at the car afterwards (not horn, lights or schedules). */
        private val VERIFIED = CarCommand.CLIMATE + setOf(
            CarCommand.LOCK, CarCommand.UNLOCK, CarCommand.TRUNK, CarCommand.WINDOWS_CLOSE, CarCommand.WINDOWS_VENT,
            CarCommand.SEAT, CarCommand.CHARGE_NOW,
        )

        @Volatile
        private var instance: CarRepository? = null

        fun get(context: Context): CarRepository =
            instance ?: synchronized(this) { instance ?: CarRepository(context).also { instance = it } }
    }
}
