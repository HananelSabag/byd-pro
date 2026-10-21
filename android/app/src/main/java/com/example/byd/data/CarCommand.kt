package com.example.byd.data

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.byd.R

/** Every command the app can send. [path] is the server route (see server/main.py ROUTES). */
enum class CarCommand(
    val path: String,
    @param:StringRes val label: Int,
    @param:StringRes val done: Int,
    @param:DrawableRes val icon: Int,
) {
    LOCK("lock", R.string.cmd_lock, R.string.done_lock, R.drawable.ic_lock),
    UNLOCK("unlock", R.string.cmd_unlock, R.string.done_unlock, R.drawable.ic_unlock),
    TRUNK("trunk-toggle", R.string.cmd_trunk, R.string.done_trunk, R.drawable.ic_trunk),
    CLIMATE_ON("ac", R.string.cmd_climate_on, R.string.done_climate_on, R.drawable.ic_snowflake),
    CLIMATE_OFF("ac-off", R.string.cmd_climate_off, R.string.done_climate_off, R.drawable.ic_power),
    LOCATE("locate", R.string.cmd_locate, R.string.done_locate, R.drawable.ic_horn),
    FLASH("flash", R.string.cmd_flash, R.string.done_flash, R.drawable.ic_flash),
    WINDOWS_CLOSE("windows-close", R.string.cmd_windows, R.string.done_windows, R.drawable.ic_window),
    WINDOWS_VENT("windows-vent", R.string.cmd_windows_vent, R.string.done_windows_vent, R.drawable.ic_window),
    SEAT("seat", R.string.cmd_seat, R.string.done_seat, R.drawable.ic_seat),
    CHARGE_NOW("charge-now", R.string.cmd_charge_now, R.string.done_charge_now, R.drawable.ic_bolt),
    CHARGE_PAUSE("charge-pause", R.string.cmd_charge_pause, R.string.done_charge_pause, R.drawable.ic_pause),
    CHARGE_SCHEDULE("charge-schedule", R.string.cmd_charge_schedule, R.string.done_charge_schedule, R.drawable.ic_clock);

    companion object {
        fun fromName(name: String?): CarCommand? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Why a request failed, mapped from the server's `error` code. A [soft] outcome isn't a failure
 * of the app or the car (the car may well have done it, or it simply can't): shown in amber.
 */
enum class ApiError(@param:StringRes val message: Int, val soft: Boolean = false) {
    NETWORK(R.string.err_network),
    NOT_CONFIRMED(R.string.err_not_confirmed, soft = true),
    STILL_CHARGING(R.string.err_still_charging, soft = true),
    TOO_LATE(R.string.err_too_late, soft = true),
    UNAUTHORIZED(R.string.err_unauthorized),
    TIMEOUT(R.string.err_timeout),
    BUSY(R.string.err_busy),
    NOT_SUPPORTED(R.string.err_not_supported),
    REJECTED(R.string.err_rejected),
    PIN(R.string.err_pin),
    LOGIN(R.string.err_login),
    BYD(R.string.err_byd),
    SERVER(R.string.err_server);

    companion object {
        fun from(httpCode: Int, code: String?): ApiError = when {
            httpCode == 401 -> UNAUTHORIZED
            code == "timeout" -> TIMEOUT
            code == "busy" -> BUSY
            code == "still_charging" -> STILL_CHARGING
            code == "not_supported" -> NOT_SUPPORTED
            code == "rejected" -> REJECTED
            code == "login_failed" -> LOGIN
            code?.startsWith("pin_") == true -> PIN
            code == "byd_unreachable" || code?.startsWith("byd_") == true -> BYD
            else -> SERVER
        }
    }
}
