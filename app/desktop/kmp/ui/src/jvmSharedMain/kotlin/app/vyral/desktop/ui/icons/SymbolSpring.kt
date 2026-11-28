package app.vyral.desktop.ui.icons

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Desktop-only damped springs. Retapping adds velocity without resetting position. */
internal class SymbolSpring(private val count: Int) {
    private val position = FloatArray(count)
    private val velocity = FloatArray(count)
    private var at: Long? = null

    fun advance(now: Long) {
        val previous = at
        if (previous != null && now < previous) return
        at = now
        val dt = if (previous == null) 0f else (now - previous) / 1_000_000_000f
        repeat(count) { index ->
            val omega = 23f / (1f + (index % 6) * 0.12f)
            val decay = omega * 0.56f
            val damped = sqrt(omega * omega - decay * decay)
            val envelope = exp(-decay * dt)
            val a = position[index]
            val b = (velocity[index] + decay * a) / damped
            val angle = damped * dt
            position[index] = envelope * (a * cos(angle) + b * sin(angle))
            velocity[index] = envelope * ((b * damped - decay * a) * cos(angle) -
                (a * damped + decay * b) * sin(angle))
        }
    }

    fun tap() {
        repeat(count) { index ->
            val omega = 23f / (1f + (index % 6) * 0.12f)
            val limit = sqrt((omega * omega * (0.24f * 0.24f -
                position[index] * position[index])).coerceAtLeast(0f)).coerceAtMost(5f)
            velocity[index] = (velocity[index] + 4f / (1f + (index % 6) * 0.15f)).coerceIn(-limit, limit)
        }
    }

    fun lifts(): List<Float> = position.map { it.coerceIn(-0.26f, 0.26f) }
    fun settled(): Boolean = position.indices.all {
        kotlin.math.abs(position[it]) < 0.0001f && kotlin.math.abs(velocity[it]) < 0.001f
    }

    fun reset() {
        position.fill(0f)
        velocity.fill(0f)
        at = null
    }
}
