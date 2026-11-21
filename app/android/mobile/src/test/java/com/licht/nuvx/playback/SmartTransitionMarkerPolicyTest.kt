package com.licht.nuvx.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartTransitionMarkerPolicyTest {
    private fun validPlan() = shouldClearSmartTransitionMarker(
        smartFadeEnabled = true,
        versionSwapActive = false,
        inListenTogetherParty = false,
        bitPerfectActive = false,
        hasNext = true,
        repeatingOne = false,
        durationAvailable = true,
    )

    @Test
    fun validSmartFadeInputsKeepTheMarkerEligible() {
        assertFalse(validPlan())
    }

    @Test
    fun markerIsClearedWhenSmartFadeIsDisabled() {
        assertTrue(shouldClearSmartTransitionMarker(
            smartFadeEnabled = false,
            versionSwapActive = false,
            inListenTogetherParty = false,
            bitPerfectActive = false,
            hasNext = true,
            repeatingOne = false,
            durationAvailable = true,
        ))
    }

    @Test
    fun markerIsClearedWhenPlanningIsBlockedOrUnavailable() {
        val invalidations = listOf(
            shouldClearSmartTransitionMarker(true, true, false, false, true, false, true),
            shouldClearSmartTransitionMarker(true, false, true, false, true, false, true),
            shouldClearSmartTransitionMarker(true, false, false, true, true, false, true),
            shouldClearSmartTransitionMarker(true, false, false, false, false, false, true),
            shouldClearSmartTransitionMarker(true, false, false, false, true, true, true),
            shouldClearSmartTransitionMarker(true, false, false, false, true, false, false),
        )
        invalidations.forEach { assertTrue(it) }
    }
}
