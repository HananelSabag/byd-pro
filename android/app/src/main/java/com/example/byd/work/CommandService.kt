package com.example.byd.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.byd.R
import com.example.byd.bus.CommandRecord
import com.example.byd.bus.Outcomes
import com.example.byd.bus.Source
import com.example.byd.data.CarCommand
import com.example.byd.data.CarRepository
import com.example.byd.system.Buzz
import com.example.byd.widget.QuickAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while a command is on its way, however it was tapped.
 *
 * The command itself runs on the bus (CarRepository); this short foreground service only keeps
 * the process, its network (even in Doze) and the widgets' answer alive until every command,
 * its check and the widgets' result display are done, plus [LINGER_MS]. Battery savers can hold
 * background jobs back for minutes; a foreground service started right after a tap can't be.
 * It shows a quiet notification with the command's current step and stops by itself.
 */
class CommandService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastStartId = 0
    private var keeper: Job? = null
    private var refreshes = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val repo = CarRepository.get(this)
        goForeground(notification(textFor(repo.bus.records.value)))
        if (intent?.action == ACTION_REFRESH) {
            refreshes++
            scope.launch {
                try {
                    repo.refresh(fresh = true)
                } finally {
                    refreshes--
                }
            }
        }
        keep(repo)
        return START_NOT_STICKY
    }

    /** Waits until the bus is idle (and stays idle for [LINGER_MS]), then stops, unless a newer start came in. */
    private fun keep(repo: CarRepository) {
        if (keeper?.isActive == true) return
        keeper = scope.launch {
            val texts = launch {
                repo.bus.records.map(::textFor).distinctUntilChanged().collect { notify(it) }
            }
            while (true) {
                repo.bus.awaitIdle()
                delay(LINGER_MS) // widgets show the answer, then their normal state is drawn
                if (!repo.bus.idle.value || refreshes > 0) continue
                // Only stops if no start arrived after lastStartId: a tap that comes in right now
                // keeps the service (stopSelf() here could crash such a start, see Android's
                // "did not then call startForeground").
                if (stopSelfResult(lastStartId)) break
            }
            texts.cancel()
        }
    }

    /** Android 14+: a short service gets about three minutes; the bus has persisted everything by then. */
    override fun onTimeout(startId: Int) = timeUp()

    override fun onTimeout(startId: Int, fgsType: Int) = timeUp()

    private fun timeUp() {
        CarRepository.get(this).log.log(null, "keep-alive service out of time")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(lastStartId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun textFor(records: List<CommandRecord>): String {
        val live = records.lastOrNull { !it.terminal } ?: return getString(R.string.notif_sending)
        return getString(R.string.notif_step, getString(live.command.label), getString(Outcomes.of(live).label))
    }

    private fun notification(text: String): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_car)
        .setContentTitle(text)
        .setOngoing(true)
        .build()

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun goForeground(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "commands"
        private const val NOTIFICATION_ID = 7
        private const val LINGER_MS = 3_000L
        private const val ACTION_KEEP = "com.example.byd.KEEP"
        private const val ACTION_REFRESH = "com.example.byd.REFRESH"

        /** A tap outside the app (widget, confirm dialog, quick-settings tile): instant buzz, then the bus. */
        fun tap(context: Context, command: CarCommand, source: Source): CommandRecord {
            val repo = CarRepository.get(context)
            if (repo.prefs.haptics) Buzz.tap(context)
            return repo.run(command, source = source)
        }

        fun tap(context: Context, action: QuickAction, source: Source): CommandRecord =
            tap(context, action.command(CarRepository.get(context)), source)

        /**
         * Keeps the process alive for the commands on the bus. If Android refuses the service
         * (a start from the background without an exemption), a job keeps it alive instead.
         */
        fun keepAlive(context: Context) {
            if (!start(context, Intent(context, CommandService::class.java).setAction(ACTION_KEEP))) {
                CarRepository.get(context).log.log(null, "foreground service refused: keep-alive job instead")
                CommandWorker.enqueueKeepAlive(context)
            }
        }

        /** The dashboard widget's refresh button: one fresh read of the car. */
        fun refresh(context: Context) {
            CarRepository.get(context).markRefreshing()
            if (!start(context, Intent(context, CommandService::class.java).setAction(ACTION_REFRESH))) {
                CommandWorker.enqueueRefresh(context)
            }
        }

        private fun start(context: Context, intent: Intent): Boolean = try {
            context.startForegroundService(intent)
            true
        } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException is one
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
