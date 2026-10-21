package com.example.byd.data

import android.content.Context
import android.os.SystemClock
import com.example.byd.system.ChargeWatch
import com.example.byd.widget.WidgetRenderer
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException

data class CommandResult(
    val id: Long,
    val command: CarCommand,
    val error: ApiError?,
    val tookMs: Long,
    val finishedAt: Long, // SystemClock.elapsedRealtime()
    val params: Map<String, String> = emptyMap(), // what was asked, so Retry repeats exactly that
) {
    val ok: Boolean get() = error == null
}

data class CarUiState(
    val snapshot: CarSnapshot,
    val refreshing: Boolean = false,
    val online: Boolean? = null,
    val pending: Map<CarCommand, Long> = emptyMap(), // command -> start (elapsedRealtime)
    val lastResult: CommandResult? = null,
    val latencyMs: Long? = null,
    val climateSentTemp: Int? = null, // what the running A/C was started with
)

/**
 * Single source of truth for the app, the widgets and the quick-settings tiles (one process,
 * one state). Work runs in an app-wide scope, so a command finishes and updates the widgets
 * even if the screen that started it is gone.
 */
class CarRepository private constructor(context: Context) {
    private val app = context.applicationContext
    val prefs = AppPrefs(app)
    private val api = CarApi(prefs)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var doc: JSONObject = prefs.stateDoc
    private val _state = MutableStateFlow(
        CarUiState(snapshot = CarSnapshot.from(doc), climateSentTemp = prefs.climateSentTemp.takeIf { it > 0 }),
    )
    val state: StateFlow<CarUiState> = _state.asStateFlow()

    private var refreshTask: Deferred<Boolean>? = null
    private var freshTask: Deferred<Boolean>? = null
    private var climateExpiry: Job? = null

    init {
        expireClimateAt(_state.value.snapshot.climateUntil)
    }
    private val inflight = HashMap<String, Deferred<CommandResult>>()
    private var resultSeq = 0L

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

    private suspend fun doRefresh(fresh: Boolean): Boolean {
        val started = SystemClock.elapsedRealtime()
        _state.update { it.copy(refreshing = true) }
        WidgetRenderer.updateAll(app, _state.value)
        val ok = try {
            val reply = api.get("status", if (fresh) mapOf("fresh" to "1") else emptyMap())
            // A command that ran meanwhile already brought newer state; this reply would undo it.
            val overtaken = _state.value.pending.isNotEmpty() || (_state.value.lastResult?.finishedAt ?: 0L) > started
            if (!overtaken) absorb(reply.json)
            _state.update { it.copy(online = true, latencyMs = reply.tookMs) }
            reply.ok
        } catch (e: IOException) {
            _state.update { it.copy(online = false) }
            false
        } finally {
            _state.update { it.copy(refreshing = false) }
        }
        WidgetRenderer.updateAll(app, _state.value)
        return ok
    }

    // ---------------------------------------------------------------- commands

    /** Sends a command. A repeat tap while the same command is in flight joins it instead of resending. */
    suspend fun send(command: CarCommand, params: Map<String, String> = emptyMap()): CommandResult {
        val key = command.name + params.toSortedMap()
        val task = synchronized(inflight) {
            inflight[key]?.takeIf { it.isActive }
                ?: scope.async { execute(command, params) }.also { inflight[key] = it }
        }
        return task.await()
    }

    fun sendAsync(command: CarCommand, params: Map<String, String> = emptyMap()) {
        scope.launch { send(command, params) }
    }

    /** Widgets/tiles call this on tap, before the worker starts, so the "sending" state shows instantly. */
    fun markQueued(command: CarCommand) {
        _state.update { it.copy(pending = it.pending + (command to SystemClock.elapsedRealtime())) }
        WidgetRenderer.updateAll(app, _state.value)
    }

    fun markRefreshing() {
        _state.update { it.copy(refreshing = true) }
        WidgetRenderer.updateAll(app, _state.value)
    }

    /** A widget or tile tap that waited too long to run: not sent, and the widget says so. */
    fun reportTooLate(command: CarCommand) {
        val now = SystemClock.elapsedRealtime()
        _state.update { it.copy(pending = it.pending - command, lastResult = CommandResult(++resultSeq, command, ApiError.TOO_LATE, 0, now)) }
        WidgetRenderer.updateAll(app, _state.value)
    }

    /** Climate toggle used by widgets: stop it if it is known to be running, otherwise start it. */
    fun climateToggle(): CarCommand =
        if (state.value.snapshot.climateOn == true) CarCommand.CLIMATE_OFF else CarCommand.CLIMATE_ON

    suspend fun ping(baseUrl: String): Long = api.ping(baseUrl)

    private suspend fun execute(command: CarCommand, params: Map<String, String>): CommandResult {
        val started = SystemClock.elapsedRealtime()
        _state.update { it.copy(pending = it.pending + (command to started)) }
        WidgetRenderer.updateAll(app, _state.value)

        val query = if (command == CarCommand.CLIMATE_ON) {
            mapOf(
                "temp" to prefs.climateTemp.toString(),
                "mins" to prefs.climateMinutes.toString(),
                "air" to if (prefs.climateRecirculate) "recirc" else "fresh",
            ) + params
        } else {
            params
        }
        if (command == CarCommand.CHARGE_NOW || command == CarCommand.CHARGE_PAUSE) ChargeWatch.quietForOwnCommand()
        val error = try {
            val reply = api.get(command.path, query, command = true)
            absorb(reply.json)
            reply.error
        } catch (e: InterruptedIOException) {
            if (e.message?.contains("connect", ignoreCase = true) == true) {
                ApiError.NETWORK // never reached the server
            } else {
                // Sent, but no answer in time: the car may still do it. Say so, then look again
                // (the server may still be working on it at the first look).
                scope.launch {
                    delay(5_000)
                    refresh()
                    delay(35_000)
                    refresh()
                }
                ApiError.NOT_CONFIRMED
            }
        } catch (e: IOException) {
            ApiError.NETWORK
        }

        if (error == null && command == CarCommand.CLIMATE_ON) {
            query["temp"]?.toIntOrNull()?.let { sent ->
                prefs.climateSentTemp = sent
                _state.update { it.copy(climateSentTemp = sent) }
            }
        }
        val now = SystemClock.elapsedRealtime()
        val result = CommandResult(++resultSeq, command, error, now - started, now, params)
        _state.update {
            it.copy(pending = it.pending - command, lastResult = result, online = error != ApiError.NETWORK)
        }
        WidgetRenderer.updateAll(app, _state.value)
        if (error == null && command == CarCommand.CHARGE_SCHEDULE) params["mode"]?.let { prefs.scheduleMode = it }
        if (error == null && command == CarCommand.CHARGE_SCHEDULE && prefs.chargeAlerts) {
            ChargeWatch.scheduleChanged(app, _state.value.snapshot)
        }

        scope.launch {
            delay(RESULT_SHOW_MS + 50)
            WidgetRenderer.updateAll(app, _state.value) // widgets drop the ✓ / ✗ and show the normal state again
        }
        return result
    }

    private fun absorb(json: JSONObject?) {
        if (json == null || !json.has("car")) return
        val before = _state.value.snapshot
        doc = CarSnapshot.merge(doc, json)
        prefs.stateDoc = doc
        val after = CarSnapshot.from(doc)
        _state.update { it.copy(snapshot = after) }
        if (after.climateUntil != before.climateUntil) expireClimateAt(after.climateUntil)
        if (prefs.chargeAlerts) ChargeWatch.onUpdate(app, before, after)
    }

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
        /** How long widgets keep showing a command's ✓ / ✗ before returning to normal. */
        const val RESULT_SHOW_MS = 2500L

        @Volatile
        private var instance: CarRepository? = null

        fun get(context: Context): CarRepository =
            instance ?: synchronized(this) { instance ?: CarRepository(context).also { instance = it } }
    }
}
