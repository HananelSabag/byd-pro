package com.example.byd.wear

import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * The phone's side of the conversation while the app is closed: new car state (keep it, redraw
 * the tile, buzz when a tap got its answer), the phone's ack of a tap, and the phone asking for
 * this watch's debug log.
 */
class StateListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val map = events.lastOrNull { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == PhoneLink.PATH_STATE }
            ?.let { DataMapItem.fromDataItem(it.dataItem).dataMap } ?: return
        StateCache.save(this, map)
        Buzz.settled(this, Taps.settle(this, WatchState.from(map)))
        TileService.getUpdater(this).requestUpdate(CarTileService::class.java)
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            PhoneLink.PATH_ACK -> {
                val parts = String(event.data).split('|')
                val id = parts.firstOrNull().orEmpty()
                Taps.ack(this, id, parts.getOrNull(1).orEmpty())?.let { Buzz.settled(this, listOf(it)) }
                Buzz.settled(this, Taps.settle(this))
                TileService.getUpdater(this).requestUpdate(CarTileService::class.java)
            }
            PhoneLink.PATH_LOG_REQUEST -> PhoneLink.sendLog(this, event.sourceNodeId, WatchLog.text(this))
        }
    }
}
