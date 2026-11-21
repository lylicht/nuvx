package com.licht.nuvx.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopLyricsRequestTrackerTest {
    @Test
    fun disabledLookupDoesNotConsumeTheRequest() {
        val tracker = LoopLyricsRequestTracker(retryDelayMs = 30_000L)

        assertFalse(tracker.begin("track", nowMs = 0L, enabled = false))
        assertTrue(tracker.begin("track", nowMs = 1L, enabled = true))
    }

    @Test
    fun unavailableLookupCanRetryAfterTheCooldown() {
        val tracker = LoopLyricsRequestTracker(retryDelayMs = 30_000L)

        assertTrue(tracker.begin("track", nowMs = 0L, enabled = true))
        tracker.unavailable("track", nowMs = 100L)

        assertFalse(tracker.begin("track", nowMs = 30_099L, enabled = true))
        assertTrue(tracker.begin("track", nowMs = 30_100L, enabled = true))
    }

    @Test
    fun successfulLookupIsNotRequestedAgain() {
        val tracker = LoopLyricsRequestTracker(retryDelayMs = 30_000L)

        assertTrue(tracker.begin("track", nowMs = 0L, enabled = true))
        tracker.succeeded("track")

        assertFalse(tracker.begin("track", nowMs = 100_000L, enabled = true))
    }

    @Test
    fun cancelledLookupCanBeStartedAgainImmediately() {
        val tracker = LoopLyricsRequestTracker(retryDelayMs = 30_000L)

        assertTrue(tracker.begin("track", nowMs = 0L, enabled = true))
        tracker.cancelled("track")

        assertTrue(tracker.begin("track", nowMs = 1L, enabled = true))
    }
}
