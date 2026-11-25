package com.licht.nuvx.wear.data

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings

object WearPlaybackHaptics {
    @Volatile
    var enabled: Boolean = true

    @Volatile
    private var lastControlAtMs: Long = 0L

    fun markControlAction() {
        lastControlAtMs = SystemClock.elapsedRealtime()
    }

    fun playPlayback(context: Context, kind: Kind) {
        if (!enabled || SystemClock.elapsedRealtime() - lastControlAtMs < DUPLICATE_WINDOW_MS) return
        val hapticsOn = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
        }.getOrDefault(true)
        if (!hapticsOn) return
        val vibrator = context.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() } ?: return
        val effect = when (kind) {
            Kind.Resume -> VibrationEffect.EFFECT_TICK
            Kind.Pause -> VibrationEffect.EFFECT_CLICK
            Kind.TrackChange -> VibrationEffect.EFFECT_DOUBLE_CLICK
        }
        runCatching { vibrator.vibrate(VibrationEffect.createPredefined(effect)) }
    }

    enum class Kind { Resume, Pause, TrackChange }

    private const val DUPLICATE_WINDOW_MS = 600L
}
