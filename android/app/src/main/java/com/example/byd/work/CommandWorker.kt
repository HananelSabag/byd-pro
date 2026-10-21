package com.example.byd.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.byd.R
import com.example.byd.data.CarCommand
import com.example.byd.data.CarRepository
import com.example.byd.system.Buzz
import kotlinx.coroutines.delay

/**
 * Runs a command started from a widget or tile, outside the app's UI.
 *
 * Expedited work starts right away and gets the network even in Doze, yet the app holds
 * nothing when idle: no service, no wakelock, no polling. The job ends seconds after the
 * car answers.
 */
class CommandWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = CarRepository.get(applicationContext)
        if (inputData.getBoolean(KEY_REFRESH, false)) {
            repo.refresh(fresh = inputData.getBoolean(KEY_FRESH, true))
            return Result.success()
        }
        val command = CarCommand.fromName(inputData.getString(KEY_COMMAND)) ?: return Result.failure()
        // When Android delays background work (battery saver, quota, a reboot), an Unlock that
        // runs minutes after the tap could open the car with nobody there. Those are dropped. A
        // job with no tap time was queued by an older version, so it is old by definition.
        val tappedAt = inputData.getLong(KEY_TAPPED_AT, 0L)
        val age = if (tappedAt > 0) System.currentTimeMillis() - tappedAt else Long.MAX_VALUE
        if ((command in ONLY_IF_PROMPT && age > MAX_DELAY_MS) || age > STALE_MS) {
            repo.reportTooLate(command)
            if (repo.prefs.haptics) Buzz.failure(applicationContext)
            delay(CarRepository.RESULT_SHOW_MS)
            return Result.success()
        }
        val result = repo.send(command)
        if (repo.prefs.haptics) {
            if (result.ok) Buzz.success(applicationContext) else Buzz.failure(applicationContext)
        }
        delay(CarRepository.RESULT_SHOW_MS) // keep the process alive while widgets show ✓ / ✗
        return Result.success()
    }

    /** Only used below Android 12, where expedited work runs as a short foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, applicationContext.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_MIN),
        )
        val notification = Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(applicationContext.getString(R.string.notif_sending))
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val KEY_COMMAND = "command"
        private const val KEY_REFRESH = "refresh"
        private const val KEY_FRESH = "fresh"
        private const val KEY_TAPPED_AT = "tapped_at"
        private const val MAX_DELAY_MS = 45_000L
        private const val STALE_MS = 10 * 60_000L // even a Lock isn't sent this long after the tap
        private val ONLY_IF_PROMPT = setOf(
            CarCommand.UNLOCK, CarCommand.TRUNK, CarCommand.WINDOWS_VENT, CarCommand.CLIMATE_ON,
            CarCommand.LOCATE, CarCommand.FLASH, // a horn going off minutes later helps nobody
        )
        private const val CHANNEL = "commands"
        private const val NOTIFICATION_ID = 7

        fun enqueue(context: Context, command: CarCommand) = enqueue(
            context, "cmd-${command.name}",
            workDataOf(KEY_COMMAND to command.name, KEY_TAPPED_AT to System.currentTimeMillis()),
        )

        /** [fresh] wakes the car (refresh button); a scheduled check is happy with recent data. */
        fun enqueueRefresh(context: Context, fresh: Boolean = true) =
            enqueue(context, "refresh", workDataOf(KEY_REFRESH to true, KEY_FRESH to fresh))

        // REPLACE is safe: the command itself lives in CarRepository's scope, and a tap on a
        // command that is still in flight joins it there instead of sending it twice.
        private fun enqueue(context: Context, name: String, data: androidx.work.Data) {
            val request = OneTimeWorkRequestBuilder<CommandWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
