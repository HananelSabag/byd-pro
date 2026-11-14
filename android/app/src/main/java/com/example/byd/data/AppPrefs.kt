package com.example.byd.data

import android.content.Context
import androidx.core.content.edit
import com.example.byd.BuildConfig
import org.json.JSONObject

/** Small key-value settings + the cached car state, so every screen and widget opens instantly. */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("byd", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_SERVER_URL
        set(value) = prefs.edit { putString(KEY_URL, value.trim().trimEnd('/')) }

    var secret: String
        get() = prefs.getString(KEY_SECRET, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_SERVER_SECRET
        set(value) = prefs.edit { putString(KEY_SECRET, value.trim()) }

    var climateTemp: Int
        get() = prefs.getInt("climate_temp", 21)
        set(value) = prefs.edit { putInt("climate_temp", value) }

    /** Temperature of the last A/C start that the car accepted (0 = none yet). */
    var climateSentTemp: Int
        get() = prefs.getInt("climate_sent_temp", 0)
        set(value) = prefs.edit { putInt("climate_sent_temp", value) }

    /** A/C air source: recirculate (true, faster) or outside air. */
    var climateRecirculate: Boolean
        get() = prefs.getBoolean("climate_recirc", true)
        set(value) = prefs.edit { putBoolean("climate_recirc", value) }

    /** A/C run time for every start (quick cool / heat, widgets, watch): 10, 15, 20, 25 or 30. */
    var climateMinutes: Int
        get() = prefs.getInt("climate_minutes", 20)
        set(value) = prefs.edit { putInt("climate_minutes", value) }

    var chargeAlerts: Boolean
        get() = prefs.getBoolean("charge_alerts", true)
        set(value) = prefs.edit { putBoolean("charge_alerts", value) }

    /** The check every two hours, 08:00–20:00 (work/CheckWorker). */
    var autoCheck: Boolean
        get() = prefs.getBoolean("auto_check", true)
        set(value) = prefs.edit { putBoolean("auto_check", value) }

    /** What the last check found ("" = all fine), so a problem is announced once until it changes. */
    var lastCheckAlert: String
        get() = prefs.getString("last_check_alert", "") ?: ""
        set(value) = prefs.edit { putString("last_check_alert", value) }

    /** Last charging-schedule mode chosen in the app: stop_only, start_only or window. */
    var scheduleMode: String
        get() = prefs.getString("schedule_mode", "window") ?: "window"
        set(value) = prefs.edit { putString("schedule_mode", value) }

    /** App language before Android 13 ("" = the phone's); newer phones keep it per app. */
    var language: String
        get() = prefs.getString("language", "") ?: ""
        set(value) = prefs.edit { putString("language", value) }

    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(value) = prefs.edit { putBoolean("haptics", value) }

    /** Last merged server state (see [CarSnapshot.merge]). */
    var stateDoc: JSONObject
        get() = prefs.getString("state", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        set(value) = prefs.edit { putString("state", value.toString()) }

    fun resetServer() = prefs.edit { remove(KEY_URL); remove(KEY_SECRET) }

    private companion object {
        const val KEY_URL = "server_url"
        const val KEY_SECRET = "server_secret"
    }
}
