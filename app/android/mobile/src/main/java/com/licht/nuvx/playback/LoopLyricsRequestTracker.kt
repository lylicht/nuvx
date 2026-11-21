package com.licht.nuvx.playback

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks in-flight/completed Loop Mix lyric lookups and throttles retries for unavailable results.
 */
internal class LoopLyricsRequestTracker(
    private val retryDelayMs: Long,
) {
    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val retryAfterMs = ConcurrentHashMap<String, Long>()

    init {
        require(retryDelayMs >= 0L)
    }

    fun begin(id: String, nowMs: Long, enabled: Boolean): Boolean {
        if (!enabled) return false
        val retryAt = retryAfterMs[id]
        if (retryAt != null && nowMs < retryAt) return false
        if (retryAt != null) retryAfterMs.remove(id, retryAt)
        return requested.add(id)
    }

    fun succeeded(id: String) {
        retryAfterMs.remove(id)
    }

    fun unavailable(id: String, nowMs: Long) {
        requested.remove(id)
        retryAfterMs[id] = nowMs + retryDelayMs
    }

    fun cancelled(id: String) {
        requested.remove(id)
        retryAfterMs.remove(id)
    }
}
