package com.example.byd.system

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.byd.MainActivity
import com.example.byd.R
import com.example.byd.data.AppPrefs
import com.example.byd.data.CarSnapshot
import com.example.byd.work.CommandWorker
import java.util.Calendar

/**
 * Charging notifications with no background service.
 *
 * Every time fresh car data arrives (app, widget, or a check below) the previous and new
 * charging states are compared; a change posts one notification. To catch the car starting or
 * finishing on its own, one-shot alarms are set at the schedule's start/end and at the expected
 * finish. Each alarm does a single status read and is gone: no polling, no wakelock.
 */
object ChargeWatch {
    private const val CHANNEL = "charging"
    private const val NOTIFICATION_ID = 21
    private const val REQ_START = 301
    private const val REQ_END = 302
    private const val REQ_FINISH = 303
    private const val QUIET_MS = 3 * 60_000L
    private const val MAX_FINISH_CHECKS = 6
    private const val KEY_FINISH_CHECKS = "charge_finish_checks"

    /** Until then, charging changes come from the owner's own tap: no notification about them. */
    @Volatile
    private var quietUntil = 0L

    fun quietForOwnCommand() {
        quietUntil = System.currentTimeMillis() + QUIET_MS
    }

    fun onUpdate(context: Context, before: CarSnapshot, after: CarSnapshot) {
        val prefs = context.getSharedPreferences("byd", Context.MODE_PRIVATE)
        if (before.charging != after.charging) prefs.edit().putInt(KEY_FINISH_CHECKS, 0).apply() // new charge session
        if (before.charging != null && after.charging != null && before.charging != after.charging &&
            System.currentTimeMillis() > quietUntil
        ) {
            notify(context, after)
        }
        // One check at the expected finish, from fresh data only, and a few per charge at most:
        // a car that keeps saying "charging" must not be woken again and again.
        val eta = after.chargeEtaMin
        val checks = prefs.getInt(KEY_FINISH_CHECKS, 0)
        if (after.charging == true && eta != null && eta > 0 && checks < MAX_FINISH_CHECKS) {
            alarmAt(context, System.currentTimeMillis() + (eta + 2) * 60_000L, REQ_FINISH)
            prefs.edit().putInt(KEY_FINISH_CHECKS, checks + 1).apply()
        }
        if (after.schedule?.enabled == true) scheduleChanged(context, after) // keeps the daily checks rolling
    }

    /** Sets the daily start/end checks again from the last known state (after a reboot, or before each check). */
    fun rearm(context: Context, car: CarSnapshot) = scheduleChanged(context, car)

    /** Charging alerts switched off: no more checks, so the car is never woken for them. */
    fun cancelAll(context: Context) {
        cancel(context, REQ_START)
        cancel(context, REQ_END)
        cancel(context, REQ_FINISH)
    }

    fun scheduleChanged(context: Context, car: CarSnapshot) {
        val schedule = car.schedule ?: return
        if (!schedule.enabled) {
            cancel(context, REQ_START)
            cancel(context, REQ_END)
            return
        }
        schedule.start?.let { alarmDaily(context, it, REQ_START) }
        schedule.end?.takeIf { it != "full" }?.let { alarmDaily(context, it, REQ_END) }
    }

    private fun notify(context: Context, car: CarSnapshot) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.notif_charge_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = context.getString(if (car.charging == true) R.string.notif_charge_started else R.string.notif_charge_stopped)
        val body = if (car.soc != null && car.rangeKm != null) context.getString(R.string.notif_charge_body, car.soc, car.rangeKm) else null
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun pending(context: Context, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code, Intent(context, ChargeCheckReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun alarmAt(context: Context, epochMs: Long, code: Int) {
        // Inexact-while-idle is enough here and needs no special alarm permission.
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochMs, pending(context, code))
    }

    /** Next occurrence of [hhmm], 90 s late so the car has already switched. */
    private fun alarmDaily(context: Context, hhmm: String, code: Int) {
        val (hours, minutes) = hhmm.split(":").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hours)
            set(Calendar.MINUTE, minutes)
            set(Calendar.SECOND, 90)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        alarmAt(context, at.timeInMillis, code)
    }

    private fun cancel(context: Context, code: Int) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context, code))
    }
}

/**
 * One scheduled charging check: a single status read through the regular worker. The daily
 * checks are set again first, so one failed read doesn't end the chain. After a reboot (alarms
 * don't survive one) the checks are restored from the last known state.
 */
class ChargeCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = AppPrefs(context)
        if (!prefs.chargeAlerts) return
        ChargeWatch.rearm(context, CarSnapshot.from(prefs.stateDoc))
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) CommandWorker.enqueueRefresh(context, fresh = false)
    }
}
