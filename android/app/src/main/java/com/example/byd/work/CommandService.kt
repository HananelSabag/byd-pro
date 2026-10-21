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
import com.example.byd.data.CarCommand
import com.example.byd.data.CarRepository
import com.example.byd.system.Buzz
import com.example.byd.widget.QuickAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs a command tapped on a widget or tile, right away.
 *
 * Background jobs can be held back for minutes by phone battery savers (OnePlus, Xiaomi,
 * Samsung…), and a late Unlock is worse than none. A short foreground service is allowed right
 * after a widget or tile tap and starts at once, with network even in Doze. It shows a quiet
 * "sending" notification and stops the moment the car has answered.
 */
class CommandService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repo = CarRepository.get(this)
        val refresh = intent?.action == ACTION_REFRESH
        val command = if (refresh) null else CarCommand.fromName(intent?.getStringExtra(EXTRA_COMMAND))
        goForeground(command)
        if (!refresh && command == null) {
            if (running == 0) stop()
            return START_NOT_STICKY
        }
        running++
        scope.launch {
            try {
                if (command == null) {
                    repo.refresh(fresh = true)
                } else {
                    val result = repo.send(command)
                    if (repo.prefs.haptics) {
                        if (result.ok) Buzz.success(this@CommandService) else Buzz.failure(this@CommandService)
                    }
                    delay(CarRepository.RESULT_SHOW_MS) // widgets show the answer, then their normal state
                }
            } finally {
                if (--running == 0) stop()
            }
        }
        return START_NOT_STICKY
    }

    /** Android 14+: a short service gets about three minutes; a command never needs that long. */
    override fun onTimeout(startId: Int) = stop()

    override fun onTimeout(startId: Int, fgsType: Int) = stop()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun stop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(command: CarCommand?) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN),
        )
        val text = command?.let { getString(R.string.notif_sending_cmd, getString(it.label)) } ?: getString(R.string.notif_sending)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(text)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL = "commands"
        private const val NOTIFICATION_ID = 7
        private const val EXTRA_COMMAND = "command"
        private const val ACTION_RUN = "com.example.byd.RUN"
        private const val ACTION_REFRESH = "com.example.byd.REFRESH"

        /**
         * Sends [command] from a widget or tile tap: instant "sending" state and buzz, then the
         * service. If Android refuses the service (it shouldn't after a tap), the job queue
         * takes it, with its late-tap guard.
         */
        fun run(context: Context, command: CarCommand) {
            val repo = CarRepository.get(context)
            if (repo.prefs.haptics) Buzz.tap(context)
            repo.markQueued(command)
            val started = start(context, Intent(context, CommandService::class.java).setAction(ACTION_RUN).putExtra(EXTRA_COMMAND, command.name))
            if (!started) CommandWorker.enqueue(context, command)
        }

        fun run(context: Context, action: QuickAction) = run(context, action.command(CarRepository.get(context)))

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
