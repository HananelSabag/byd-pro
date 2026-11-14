package com.example.byd.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.byd.MainActivity
import com.example.byd.R
import com.example.byd.data.CarRepository
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * The check every two hours, 08:00–20:00: one read of the car through the server, then a
 * notification if the car is unlocked or anything is open. Outside those hours it does nothing, and
 * a problem is announced once until it changes. At most 6 car reads a day (Settings shows the
 * server's count of every car read, command and byte).
 */
class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = CarRepository.get(applicationContext)
        val prefs = repo.prefs
        if (!prefs.autoCheck || prefs.serverUrl.isBlank()) return Result.success()
        if (LocalTime.now().hour !in FIRST_HOUR until LAST_HOUR) return Result.success()
        // A failed read is no alarm: the free server may be waking up. The next run tries again.
        if (!repo.refresh()) return Result.success()

        val car = repo.state.value.snapshot
        val problems = listOfNotNull(
            R.string.check_unlocked.takeIf { car.locked == false },
            R.string.check_trunk.takeIf { car.trunkOpen == true },
            R.string.check_windows.takeIf { car.windowsOpen == true },
            R.string.check_doors.takeIf { car.doorsOpen == true },
        ).map { applicationContext.getString(it) }
        val key = problems.joinToString("|")
        if (key != prefs.lastCheckAlert) {
            prefs.lastCheckAlert = key
            if (problems.isNotEmpty()) notify(applicationContext, problems)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "car-check"
        private const val CHANNEL = "car_check"
        private const val NOTIFICATION_ID = 31
        private const val FIRST_HOUR = 8
        private const val LAST_HOUR = 20

        /** Every two hours while the phone has a network; Android picks the exact moment. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CheckWorker>(2, TimeUnit.HOURS)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }

        private fun notify(context: Context, problems: List<String>) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.notif_check_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_lock)
                .setContentTitle(context.getString(R.string.notif_check_title))
                .setContentText(problems.joinToString(" · "))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }
    }
}
