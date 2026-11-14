package com.example.byd.system

import com.example.byd.bus.Source
import com.example.byd.bus.newRequestId
import com.example.byd.data.CarCommand
import com.example.byd.data.CarRepository
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlin.math.abs

/**
 * Messages from the watch. Android wakes this service for each one, so the phone app doesn't
 * have to be open. A tap goes on the same command bus as the app's, widgets' and tiles' taps and
 * is acknowledged at once; its progress and answer reach the watch through [WearSync].
 *
 * Nothing here blocks: the callbacks share one thread, and a slow one would hold up the next tap.
 */
class WearListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val repo = CarRepository.get(this)
        when (event.path) {
            WearSync.PATH_COMMAND -> {
                val tap = WearSync.parse(String(event.data)) ?: return
                val command = CarCommand.fromName(tap.command)?.takeIf { it in WearSync.WATCH_COMMANDS }
                if (command == null) {
                    repo.log.log(tap.id, "watch asked for '${tap.command.take(20)}': refused")
                    tap.id?.let { WearSync.ack(this, event.sourceNodeId, it, ACK_REFUSED) }
                    return
                }
                val record = repo.run(
                    command,
                    source = Source.WATCH,
                    id = tap.id ?: newRequestId(),
                    tappedAt = tap.tapAt?.let(::tapTime),
                )
                tap.id?.let { WearSync.ack(this, event.sourceNodeId, it, record.stage.name) }
            }
            // The watch app opened: a read unless the state is from the last few seconds.
            WearSync.PATH_REFRESH -> repo.refreshIfStale()
            WearSync.PATH_LOG -> WearSync.onWatchLog(String(event.data))
        }
    }

    /** The watch's tap time, when its clock agrees with the phone's (it decides "too late to send"). */
    private fun tapTime(tapAt: Long): Long? {
        val now = System.currentTimeMillis()
        return if (abs(now - tapAt) <= CLOCK_TRUST_MS) minOf(tapAt, now) else null
    }

    private companion object {
        const val ACK_REFUSED = "REFUSED"
        const val CLOCK_TRUST_MS = 60_000L
    }
}
