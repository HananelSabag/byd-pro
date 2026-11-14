package com.example.byd.wear

import android.content.Context

/**
 * The status line for a tap, shared by the app screen and the tile: the phone's own words while
 * it has the command (they come from the phone's outcome table), the watch's while it hasn't.
 * [tone] is the phone's: BUSY, OK, WARN, ERR.
 */
data class TapStatus(val text: String, val tone: String) {
    companion object {
        fun of(context: Context, tap: Tap, state: WatchState): TapStatus {
            if (tap.done) {
                val text = when (tap.local) {
                    Tap.LOCAL_NO_PHONE -> context.getString(R.string.no_phone)
                    Tap.LOCAL_NO_ACK -> context.getString(R.string.phone_no_reply)
                    Tap.LOCAL_NO_ANSWER -> context.getString(R.string.no_answer)
                    Tap.LOCAL_REFUSED -> context.getString(R.string.refused)
                    Tap.LOCAL_LEGACY_OK -> context.getString(R.string.done)
                    Tap.LOCAL_LEGACY_FAIL -> context.getString(R.string.failed)
                    else -> tap.message.ifEmpty { tap.label }
                }
                return TapStatus(text, tap.tone)
            }
            val record = state.records[tap.id]
            return when {
                record != null -> TapStatus(record.label, record.tone.ifEmpty { BUSY })
                tap.ackedAt > 0L || tap.legacy -> TapStatus(context.getString(R.string.sending), BUSY)
                else -> TapStatus(context.getString(R.string.sending_to_phone), BUSY)
            }
        }

        const val BUSY = "BUSY"
    }
}
