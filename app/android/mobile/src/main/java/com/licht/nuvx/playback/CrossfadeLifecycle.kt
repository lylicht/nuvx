package com.licht.nuvx.playback

internal enum class CrossfadePhase {
    IDLE,
    ARMING,
    FADING,
    SLEEP_FADE,
    BAILING,
    LOOP_ARMING,
    LOOP_RUNNING,
}

/**
 * Pure lifecycle policy shared by CrossfadeController and its unit tests.
 */
internal object CrossfadeLifecycle {
    const val IDLE_STEP_MS = 250L
    const val ARM_STEP_MS = 40L
    const val FADE_STEP_MS = 30L
    const val BAIL_STEP_MS = 15L
    const val LOOP_STEP_MS = 20L

    fun isTransitioning(phase: CrossfadePhase): Boolean =
        phase != CrossfadePhase.IDLE

    fun shouldBailOnSkip(phase: CrossfadePhase): Boolean =
        phase != CrossfadePhase.IDLE

    fun tickIntervalMs(phase: CrossfadePhase): Long = when (phase) {
        CrossfadePhase.IDLE -> IDLE_STEP_MS
        CrossfadePhase.ARMING, CrossfadePhase.LOOP_ARMING -> ARM_STEP_MS
        CrossfadePhase.FADING, CrossfadePhase.SLEEP_FADE -> FADE_STEP_MS
        CrossfadePhase.BAILING -> BAIL_STEP_MS
        CrossfadePhase.LOOP_RUNNING -> LOOP_STEP_MS
    }
}
