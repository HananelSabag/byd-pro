package com.example.byd.system

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Short system vibrations for widget / tile feedback (the app itself uses View haptics). */
object Buzz {
    fun tap(context: Context) = play(context, VibrationEffect.EFFECT_TICK, longArrayOf(0, 12))
    fun success(context: Context) = play(context, VibrationEffect.EFFECT_CLICK, longArrayOf(0, 30))
    fun failure(context: Context) = play(context, VibrationEffect.EFFECT_DOUBLE_CLICK, longArrayOf(0, 40, 90, 40))

    private fun play(context: Context, predefined: Int, fallback: LongArray) {
        val vibrator = vibrator(context)?.takeIf { it.hasVibrator() } ?: return
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(predefined)
        } else {
            VibrationEffect.createWaveform(fallback, -1)
        }
        // Notification usage: allowed from the background and respects silent mode.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build(),
            )
        }
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
}
