package com.example.byd.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.byd.R
import com.example.byd.data.CarCommand
import com.example.byd.data.CarRepository
import com.example.byd.work.CommandService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Quick-settings tiles (pull down the notification shade). The system binds them only while the
 * shade is open; during that time they follow the live state, the rest of the time they cost nothing.
 */
abstract class CarTileService(private val command: CarCommand) : TileService() {
    private var watcher: Job? = null

    override fun onStartListening() {
        watcher?.cancel()
        watcher = CoroutineScope(Dispatchers.Main.immediate).launch {
            CarRepository.get(this@CarTileService).state.collect { render() }
        }
    }

    override fun onStopListening() {
        watcher?.cancel()
        watcher = null
    }

    override fun onClick() {
        val run = Runnable { CommandService.run(this, command) }
        // Unlocking the car from a locked phone requires unlocking the phone first.
        if (command == CarCommand.UNLOCK && isLocked) unlockAndRun(run) else run.run()
    }

    private fun render() {
        val tile = qsTile ?: return
        val state = CarRepository.get(this).state.value
        val locked = state.snapshot.locked
        val busy = command in state.pending
        tile.state = when {
            locked == null -> Tile.STATE_INACTIVE
            (command == CarCommand.LOCK) == locked -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                busy -> getString(R.string.sending)
                locked == true -> getString(R.string.state_locked)
                locked == false -> getString(R.string.state_unlocked)
                else -> null
            }
        }
        tile.updateTile()
    }
}

class LockTileService : CarTileService(CarCommand.LOCK)
class UnlockTileService : CarTileService(CarCommand.UNLOCK)
