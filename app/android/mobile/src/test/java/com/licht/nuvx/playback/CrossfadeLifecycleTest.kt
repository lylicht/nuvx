package com.licht.nuvx.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossfadeLifecycleTest {
    @Test
    fun idleIsTheOnlyNonTransitioningPhase() {
        CrossfadePhase.entries.forEach { phase ->
            assertEquals(phase != CrossfadePhase.IDLE, CrossfadeLifecycle.isTransitioning(phase))
        }
        assertFalse(CrossfadeLifecycle.isTransitioning(CrossfadePhase.IDLE))
    }

    @Test
    fun skipRequestsOnlyTriggerBailoutOutsideIdle() {
        CrossfadePhase.entries.forEach { phase ->
            assertEquals(phase != CrossfadePhase.IDLE, CrossfadeLifecycle.shouldBailOnSkip(phase))
        }
        assertFalse(CrossfadeLifecycle.shouldBailOnSkip(CrossfadePhase.IDLE))
    }

    @Test
    fun tickerIntervalsMatchTheWorkRequiredByEachPhase() {
        assertEquals(250L, CrossfadeLifecycle.tickIntervalMs(CrossfadePhase.IDLE))
        assertEquals(40L, CrossfadeLifecycle.tickIntervalMs(CrossfadePhase.ARMING))
        assertEquals(30L, CrossfadeLifecycle.tickIntervalMs(CrossfadePhase.FADING))
        assertEquals(30L, CrossfadeLifecycle.tickIntervalMs(CrossfadePhase.SLEEP_FADE))
        assertEquals(15L, CrossfadeLifecycle.tickIntervalMs(CrossfadePhase.BAILING))
        assertEquals(40L, CrossfadeLifecycle.tickIntervalMs(CrossfadePhase.LOOP_ARMING))
        assertEquals(20L, CrossfadeLifecycle.tickIntervalMs(CrossfadePhase.LOOP_RUNNING))
    }
}
