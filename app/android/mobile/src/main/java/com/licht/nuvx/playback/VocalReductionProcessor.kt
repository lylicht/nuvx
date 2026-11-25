package com.licht.nuvx.playback

import com.licht.nuvx.playback.audio.AudioBlock
import com.licht.nuvx.playback.audio.FloatAudioProcessor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Lightweight centered-vocal reduction. Side content is never modified. */
class VocalReductionProcessor : FloatAudioProcessor {
    @Volatile
    var enabled: Boolean = false

    /** 0 removes the most centered vocal band; 1 is the original mix. */
    @Volatile
    var vocalLevel: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f) }

    var available: Boolean = false
        private set

    private var sampleRate = 0
    private var suppression = 0f
    private var glide = 0f
    private val filters = Array(4) { Biquad() }

    override fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate
        available = sampleRate > 0 && channelCount == 2
        suppression = 0f
        glide = if (sampleRate > 0) 1f - exp(-1f / (sampleRate * GLIDE_SECONDS)) else 0f
        filters[0].configure(sampleRate, LOW_HZ, highPass = true)
        filters[1].configure(sampleRate, LOW_HZ, highPass = true)
        filters[2].configure(sampleRate, minOf(HIGH_HZ, sampleRate * MAX_CUTOFF_FRACTION), highPass = false)
        filters[3].configure(sampleRate, minOf(HIGH_HZ, sampleRate * MAX_CUTOFF_FRACTION), highPass = false)
        filters.forEach(Biquad::reset)
    }

    override fun process(block: AudioBlock) {
        if (!available || block.channelCount != 2 || block.frameCount == 0) return
        val target = if (enabled) 1f - vocalLevel else 0f
        val samples = block.samples
        var index = 0
        repeat(block.frameCount) {
            val left = samples[index]
            val right = samples[index + 1]
            val mid = (left + right) * 0.5f
            val vocalBand = filters[3].process(
                filters[2].process(filters[1].process(filters[0].process(mid))),
            )
            suppression += (target - suppression) * glide
            if (suppression != 0f) {
                val removed = vocalBand * suppression
                samples[index] = left - removed
                samples[index + 1] = right - removed
            }
            index += 2
        }
    }

    override fun flush() {
        suppression = 0f
        filters.forEach(Biquad::reset)
    }

    override fun reset() {
        sampleRate = 0
        available = false
        suppression = 0f
        glide = 0f
        filters.forEach(Biquad::reset)
    }

    private class Biquad {
        private var b0 = 1f
        private var b1 = 0f
        private var b2 = 0f
        private var a1 = 0f
        private var a2 = 0f
        private var z1 = 0f
        private var z2 = 0f

        fun configure(sampleRate: Int, frequency: Float, highPass: Boolean) {
            if (sampleRate <= 0) return
            val omega = (2.0 * PI * frequency / sampleRate).toFloat()
            val cosine = cos(omega)
            val alpha = sin(omega) / (2f * BUTTERWORTH_Q)
            val a0 = 1f + alpha
            if (highPass) {
                b0 = ((1f + cosine) * 0.5f) / a0
                b1 = -(1f + cosine) / a0
                b2 = b0
            } else {
                b0 = ((1f - cosine) * 0.5f) / a0
                b1 = (1f - cosine) / a0
                b2 = b0
            }
            a1 = (-2f * cosine) / a0
            a2 = (1f - alpha) / a0
        }

        fun process(input: Float): Float {
            val output = b0 * input + z1
            z1 = b1 * input - a1 * output + z2
            z2 = b2 * input - a2 * output
            return output
        }

        fun reset() {
            z1 = 0f
            z2 = 0f
        }
    }

    private companion object {
        const val LOW_HZ = 200f
        const val HIGH_HZ = 8_000f
        const val MAX_CUTOFF_FRACTION = 0.45f
        const val GLIDE_SECONDS = 0.018f
        const val BUTTERWORTH_Q = 0.70710678f
    }
}
