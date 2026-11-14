package com.example.byd.bus

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import com.example.byd.R
import com.example.byd.data.ApiError

/** How a control looks for a command state. Each toolkit maps a tone to its colour in one place. */
enum class Tone { IDLE, BUSY, OK, WARN, ERR }

/** The result haptic. Played once per command, on the device that was tapped. */
enum class Feel { NONE, SUCCESS, SOFT, FAILURE }

/**
 * @param label short text for a button, a 1x1 widget, a tile or the watch ("Sending…", "Not sent").
 * @param message the full sentence for the app's banner and the watch's status line.
 * @param retry offer Retry: the command can be sent again without doing something twice.
 */
data class Display(
    val tone: Tone,
    @param:StringRes val label: Int,
    @param:StringRes val message: Int,
    val feel: Feel,
    val retry: Boolean,
)

/**
 * THE outcome → display table. The app (Compose), the home-screen widgets, the quick-settings
 * tiles and the watch (through WearSync) all read a command's look and feel from here, so the
 * same answer shows and buzzes the same everywhere. Colours: [colorRes] for widgets and tiles,
 * `toneColor` in ui/Components.kt for Compose, the watch's own `toneColor`.
 */
object Outcomes {
    /** How long a widget, button or tile keeps showing an answer before its normal state returns. */
    const val SHOW_MS = 2_500L

    fun of(r: CommandRecord): Display = when (r.stage) {
        Stage.QUEUED -> busy(R.string.st_queued)
        Stage.WARMING -> busy(R.string.st_waking_server, R.string.st_waking_server_long)
        Stage.SENDING -> busy(R.string.sending)
        Stage.WAITING_CAR -> busy(R.string.st_waiting_car)
        Stage.CHECKING -> busy(R.string.st_checking, R.string.err_checking)
        Stage.DONE -> done(r)
    }

    private fun busy(@StringRes label: Int, @StringRes message: Int = label) = Display(Tone.BUSY, label, message, Feel.NONE, retry = false)

    private fun done(r: CommandRecord): Display {
        val error = r.error
        val soft = error?.soft == true
        return when (r.outcome) {
            Outcome.CONFIRMED -> Display(Tone.OK, R.string.done_short, r.command.done, Feel.SUCCESS, retry = false)
            Outcome.UNCONFIRMED -> Display(Tone.WARN, R.string.res_unconfirmed, R.string.err_not_confirmed, Feel.SOFT, retry = false)
            Outcome.REJECTED ->
                if (soft) Display(Tone.WARN, R.string.res_rejected, error!!.message, Feel.SOFT, retry = false)
                else Display(Tone.ERR, R.string.res_rejected, (error ?: ApiError.REJECTED).message, Feel.FAILURE, retry = true)
            Outcome.NOT_SENT ->
                if (soft) Display(Tone.WARN, R.string.res_not_sent, error!!.message, Feel.SOFT, retry = false)
                else Display(Tone.ERR, R.string.res_not_sent, (error ?: ApiError.NETWORK).message, Feel.FAILURE, retry = retryable(error))
            Outcome.FAILED -> Display(
                Tone.ERR, R.string.failed, (error ?: ApiError.SERVER).message, Feel.FAILURE,
                // It may have reached the car: a toggle (tailgate, horn) is not offered again.
                retry = !CommandPolicy.isToggle(r.command),
            )
            Outcome.EXPIRED -> Display(Tone.ERR, R.string.res_too_late, R.string.err_too_late, Feel.FAILURE, retry = true)
            Outcome.SUPERSEDED, null -> Display(Tone.IDLE, r.command.label, r.command.label, Feel.NONE, retry = false)
        }
    }

    private fun retryable(error: ApiError?) = error != ApiError.NOT_SUPPORTED && error != ApiError.UNAUTHORIZED

    /** Still showing its answer at [now] (wall clock)? Busy records always show. */
    fun showing(r: CommandRecord, now: Long, showMs: Long = SHOW_MS): Boolean =
        !r.terminal || (r.outcome != Outcome.SUPERSEDED && now - (r.finishedAt ?: 0L) < showMs)

    /** Widgets and phone tiles. */
    @ColorRes
    fun colorRes(tone: Tone): Int = when (tone) {
        Tone.IDLE -> R.color.text_primary
        Tone.BUSY -> R.color.busy
        Tone.OK -> R.color.ok
        Tone.WARN -> R.color.soft
        Tone.ERR -> R.color.err
    }
}
