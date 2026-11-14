package com.example.byd.tile

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.byd.R
import com.example.byd.bus.Outcomes
import com.example.byd.bus.Source
import com.example.byd.data.CarCommand
import com.example.byd.data.CarRepository
import com.example.byd.work.CommandService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Quick-settings tiles (pull down the notification shade). The system binds them only while the
 * shade is open; during that time they follow the live state, the rest of the time they cost nothing.
 * The subtitle shows the lock's command step and answer from the shared outcome table.
 */
abstract class CarTileService(private val command: CarCommand) : TileService() {
    private var watcher: Job? = null

    override fun onStartListening() {
        watcher?.cancel()
        watcher = CoroutineScope(Dispatchers.Main.immediate).launch {
            val repo = CarRepository.get(this@CarTileService)
            launch { repo.state.collect { render() } }
            while (true) { // an answer is shown for a moment, then the lock state again
                delay(1_000)
                render()
            }
        }
    }

    override fun onStopListening() {
        watcher?.cancel()
        watcher = null
    }

    override fun onClick() {
        // Since 2.8 both tiles are one lock toggle, like on / off: the opposite of the last known
        // state; unknown locks. ([command] only names the tile that was added.)
        val next = if (CarRepository.get(this).state.value.snapshot.locked == true) CarCommand.UNLOCK else CarCommand.LOCK
        val run = Runnable { CommandService.tap(this, next, Source.QS_TILE) }
        // Unlocking the car from a locked phone requires unlocking the phone first.
        if (next == CarCommand.UNLOCK && isLocked) unlockAndRun(run) else run.run()
    }

    private fun render() {
        val tile = qsTile ?: return
        val state = CarRepository.get(this).state.value
        val locked = state.snapshot.locked
        val record = state.latest(LOCK_COMMANDS)?.takeIf { Outcomes.showing(it, System.currentTimeMillis()) }
        tile.state = if (locked == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, if (locked == false) R.drawable.ic_unlock else R.drawable.ic_lock)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                record != null -> getString(Outcomes.of(record).label)
                locked == true -> getString(R.string.state_locked)
                locked == false -> getString(R.string.state_unlocked)
                else -> null
            }
        }
        tile.updateTile()
    }

    private companion object {
        val LOCK_COMMANDS = setOf(CarCommand.LOCK, CarCommand.UNLOCK)
    }
}

class LockTileService : CarTileService(CarCommand.LOCK)
class UnlockTileService : CarTileService(CarCommand.UNLOCK)
