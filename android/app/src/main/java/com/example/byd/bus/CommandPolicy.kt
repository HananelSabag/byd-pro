package com.example.byd.bus

import com.example.byd.data.ApiError
import com.example.byd.data.CarCommand

/** Per-command rules of the pipeline: how late it may still go out, what a repeat would do. */
object CommandPolicy {
    /** A late Unlock, tailgate, A/C start or horn helps nobody (or opens the car with nobody there). */
    private val PROMPT_ONLY = setOf(
        CarCommand.UNLOCK, CarCommand.TRUNK, CarCommand.WINDOWS_VENT, CarCommand.CLIMATE_ON,
        CarCommand.QUICK_COOL, CarCommand.QUICK_HEAT, CarCommand.RECIRC,
        CarCommand.LOCATE, CarCommand.FLASH,
    )

    const val PROMPT_DELAY_MS = 45_000L
    const val MAX_DELAY_MS = 10 * 60_000L

    /** Longest wait between the tap and the moment the command starts going out. */
    fun maxDelayMs(command: CarCommand): Long = if (command in PROMPT_ONLY) PROMPT_DELAY_MS else MAX_DELAY_MS

    /**
     * Commands whose repeat does something different from the first: the tailgate toggles back,
     * the horn sounds twice. They are never sent again when it's unclear whether the first went out.
     */
    fun isToggle(command: CarCommand): Boolean =
        command == CarCommand.TRUNK || command == CarCommand.LOCATE || command == CarCommand.FLASH

    /**
     * Never sent again when the server may have lost it in a restart: the toggles, plus what the
     * server itself never sends twice (server/API.md §4.5: A/C start and recirc).
     */
    fun neverRepeat(command: CarCommand): Boolean = isToggle(command) || command == CarCommand.CLIMATE_ON ||
        command == CarCommand.QUICK_COOL || command == CarCommand.QUICK_HEAT || command == CarCommand.RECIRC

    /** Commands of one control. A newer tap replaces a not-yet-sent one of the same group. */
    fun group(command: CarCommand): String = when (command) {
        CarCommand.LOCK, CarCommand.UNLOCK -> "lock"
        CarCommand.CLIMATE_ON, CarCommand.CLIMATE_OFF, CarCommand.QUICK_COOL, CarCommand.QUICK_HEAT, CarCommand.RECIRC -> "climate"
        CarCommand.WINDOWS_CLOSE, CarCommand.WINDOWS_VENT -> "windows"
        CarCommand.CHARGE_NOW, CarCommand.CHARGE_PAUSE -> "charge"
        else -> command.name
    }

    /** Errors after which a provably unsent command is tried again on its own. */
    fun autoRetry(error: ApiError?): Boolean =
        error == ApiError.NETWORK || error == ApiError.BUSY || error == ApiError.QUEUE_FULL ||
            error == ApiError.BYD || error == ApiError.WARMING_UP

    /** Error codes (server/API.md §6) that mean the command never went to the car. */
    fun notSentCode(code: String?): Boolean = code != null && (
        code == "unauthorized" || code == "busy" || code == "queue_full" || code == "warming_up" || code == "bad_request" ||
            code == "bad_request_id" || code == "request_id_reused" || code == "unknown_route" || code == "unknown_action" ||
            code == "not_supported" || code == "climate_off" || code == "login_failed" || code == "no_vehicle" ||
            code.startsWith("pin_")
        )
}
