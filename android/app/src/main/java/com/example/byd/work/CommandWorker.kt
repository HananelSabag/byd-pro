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
import com.example.byd.data.CarRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Background work: a status read (refresh button, charging checks), or keeping the app alive for
 * the commands on the bus when Android refused the foreground service. A job never sends a
 * command itself: a command that couldn't go out while the app was alive is reported, not sent
 * late (the bus marks it "not sent" when the app starts again).
 */
class CommandWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = CarRepository.get(applicationContext)
        when {
            inputData.getBoolean(KEY_REFRESH, false) -> repo.refresh(fresh = inputData.getBoolean(KEY_FRESH, true))
            inputData.getBoolean(KEY_KEEP, false) -> {
                withTimeoutOrNull(KEEP_MAX_MS) { repo.bus.awaitIdle() }
                delay(CarRepository.RESULT_SHOW_MS + 500) // widgets show the answer, then their normal state
            }
            // A command queued by version 2.7 before the update: too old to send now.
            inputData.getString(KEY_COMMAND) != null -> repo.log.log(null, "dropped a command job left by 2.7: ${inputData.getString(KEY_COMMAND)}")
        }
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
        private const val KEY_COMMAND = "command" // 2.7 jobs
        private const val KEY_REFRESH = "refresh"
        private const val KEY_FRESH = "fresh"
        private const val KEY_KEEP = "keep"
        private const val KEEP_MAX_MS = 4 * 60_000L
        private const val CHANNEL = "commands"
        private const val NOTIFICATION_ID = 7

        /** [fresh] wakes the car (refresh button); a scheduled check is happy with recent data. */
        fun enqueueRefresh(context: Context, fresh: Boolean = true) =
            enqueue(context, "refresh", workDataOf(KEY_REFRESH to true, KEY_FRESH to fresh), ExistingWorkPolicy.REPLACE)

        /** KEEP: a keep-alive already running waits for the whole bus, including what was just added. */
        fun enqueueKeepAlive(context: Context) =
            enqueue(context, "keep-alive", workDataOf(KEY_KEEP to true), ExistingWorkPolicy.KEEP)

        private fun enqueue(context: Context, name: String, data: androidx.work.Data, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<CommandWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(data)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
        }
    }
}
