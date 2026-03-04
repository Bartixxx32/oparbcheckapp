package com.bartixxx.oneplusarbchecker.utils

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object HapticUtils {

    private fun getVibrator(context: Context): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    /**
     * FUSED / danger — strong double-pulse warning buzz
     */
    fun vibrateWarning(context: Context) {
        val vibrator = getVibrator(context)
        // Pattern: pause-buzz-pause-buzz (double knock)
        val timings = longArrayOf(0, 80, 60, 120)
        val amplitudes = intArrayOf(0, 200, 0, 255)
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    /**
     * SAFE — gentle triple-tick confirm
     */
    fun vibrateSuccess(context: Context) {
        val vibrator = getVibrator(context)
        // Pattern: tick-tick-tick (ascending)
        val timings = longArrayOf(0, 30, 50, 30, 50, 40)
        val amplitudes = intArrayOf(0, 80, 0, 120, 0, 180)
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    /**
     * Unknown — subtle single tick
     */
    fun vibrateTick(context: Context) {
        val vibrator = getVibrator(context)
        vibrator.vibrate(VibrationEffect.createOneShot(20, 100))
    }

    /**
     * Button press — crisp click
     */
    fun vibrateClick(context: Context) {
        val vibrator = getVibrator(context)
        vibrator.vibrate(VibrationEffect.createOneShot(15, 150))
    }

    /**
     * Error — sharp reject buzz
     */
    fun vibrateError(context: Context) {
        val vibrator = getVibrator(context)
        val timings = longArrayOf(0, 100, 40, 100)
        val amplitudes = intArrayOf(0, 255, 0, 200)
        vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }
}
