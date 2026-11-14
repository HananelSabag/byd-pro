package com.example.byd.system

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.byd.bus.Feel

/**
 * Short system vibrations: the tap of a widget / tile, and every command's result on the phone
 * (once per command, whatever it was tapped on: see CarRepository.onAnswered and Outcomes).
 */
object Buzz {
    fun tap(context: Context) = play(context, VibrationEffect.EFFECT_TICK, longArrayOf(0, 12))
    fun success(context: Context) = play(context, VibrationEffect.EFFECT_CLICK, longArrayOf(0, 30))
    fun soft(context: Context) = play(context, VibrationEffect.EFFECT_HEAVY_CLICK, longArrayOf(0, 20, 120, 20))
    fun failure(context: Context) = play(context, VibrationEffect.EFFECT_DOUBLE_CLICK, longArrayOf(0, 40, 90, 40))

    fun result(context: Context, feel: Feel) = when (feel) {
        Feel.SUCCESS -> success(context)
        Feel.SOFT -> soft(context)
        Feel.FAILURE -> failure(context)
        Feel.NONE -> Unit
    }

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
