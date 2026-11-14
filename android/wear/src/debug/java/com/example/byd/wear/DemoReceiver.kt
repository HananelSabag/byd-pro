package com.example.byd.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataMap

/**
 * Debug builds only. Shows a made-up car state without a paired phone:
 *   adb shell am broadcast -a com.example.byd.wear.DEMO -p com.example.byd --es state cooling
 * States: locked, unlocked, trunk, cooling, heating, charging, sending.
 */
class DemoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scenario = intent.getStringExtra("state") ?: "locked"
        val now = System.currentTimeMillis()
        val map = DataMap().apply {
            putString("name", "ATTO 3")
            putInt("soc", 78)
            putInt("range", 331)
            putInt("locked", if (scenario == "unlocked" || scenario == "trunk") 0 else 1)
            putInt("trunk", if (scenario == "trunk") 1 else 0)
            putInt("climate", if (scenario == "cooling" || scenario == "heating") 1 else 0)
            putString("mode", if (scenario == "heating") "heat" else if (scenario == "cooling") "cool" else "")
            putLong("until", if (scenario == "cooling" || scenario == "heating") now + 14 * 60_000 else 0L)
            putInt("charging", if (scenario == "charging") 1 else 0)
            putDouble("kw", if (scenario == "charging") 7.4 else 0.0)
            putStringArray("pending", if (scenario == "sending") arrayOf("UNLOCK") else emptyArray())
            putLong("updated", now - 60_000)
        }
        StateCache.save(context, map)
        TileService.getUpdater(context.applicationContext).requestUpdate(CarTileService::class.java)
    }
}
