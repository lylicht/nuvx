package com.licht.nuvx.data.innertube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class CancellableRunCatchingTest {
    @Test
    fun cancellationIsRethrown() = runBlocking {
        val cancellation = CancellationException("playback request was cancelled")

        val thrown = assertFailsWith<CancellationException> {
            runCatchingExceptCancellation<Int> { throw cancellation }
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun ordinaryFailuresRemainCaptured() = runBlocking {
        val failure = IllegalStateException("signature lookup failed")

        val result = runCatchingExceptCancellation<Int> { throw failure }

        assertSame(failure, result.exceptionOrNull())
    }
}
