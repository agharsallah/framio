package dev.framio.hub.core.sensors

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Finds a deliberate double knock in accelerometer samples: two spikes 120–600 ms apart, with nothing in
 * the 650 ms before the first or after the second. That rules out a slammed door (one spike), a third knock,
 * and any regular series such as footsteps: the quiet time is longer than the longest allowed gap, so any
 * pair inside a series has a neighbour too close. Builds on the Lab's KnockDetector (1.2 m/s²
 * worked on this frame).
 *
 * Axes follow Android, relative to this frame's landscape panel. Pure; fed by recorded traces in tests.
 */
class KnockPattern(
    var threshold: Float = 1.2f,
    private val minGapMs: Long = 120,
    private val maxGapMs: Long = 600,
    private val quietBeforeMs: Long = 650,
    private val quietAfterMs: Long = 650,
    /** One knock rings for a few tens of ms: spikes closer than this are the same knock. */
    private val sameKnockMs: Long = 90,
) {
    private val gravity = FloatArray(3)
    private var primed = 0
    private var lastSpikeMs = Long.MIN_VALUE / 4
    private val spikes = ArrayDeque<Long>()

    /** Added to [threshold] while the Hub's own speaker plays, so a melody doesn't knock. */
    var boost = 0f

    /** Last gravity-free magnitude, for diagnostics. */
    var linear = 0f
        private set

    /** Feeds one sample; returns true once, [quietAfterMs] after the second knock of a double knock. */
    fun add(timeMs: Long, x: Float, y: Float, z: Float): Boolean {
        if (primed < 10) {
            val a = if (primed == 0) 0f else ALPHA
            gravity[0] = gravity[0] * a + x * (1 - a)
            gravity[1] = gravity[1] * a + y * (1 - a)
            gravity[2] = gravity[2] * a + z * (1 - a)
            primed++
            return false
        }
        val lx = x - gravity[0]
        val ly = y - gravity[1]
        val lz = z - gravity[2]
        gravity[0] = gravity[0] * ALPHA + x * (1 - ALPHA)
        gravity[1] = gravity[1] * ALPHA + y * (1 - ALPHA)
        gravity[2] = gravity[2] * ALPHA + z * (1 - ALPHA)
        linear = sqrt(lx * lx + ly * ly + lz * lz)
        if (linear > threshold + boost && timeMs - lastSpikeMs > sameKnockMs) {
            lastSpikeMs = timeMs
            spikes.addLast(timeMs)
        }
        return decide(timeMs)
    }

    private fun decide(nowMs: Long): Boolean {
        // forget what can no longer be part of a pattern
        while (spikes.size > 3 || (spikes.isNotEmpty() && nowMs - spikes.first() > quietBeforeMs + maxGapMs + quietAfterMs)) spikes.removeFirst()
        if (spikes.size < 2) return false
        val n = spikes.size
        val second = spikes[n - 1]
        val first = spikes[n - 2]
        if (nowMs - second < quietAfterMs) return false // wait: a third knock would spoil it
        val before = if (n >= 3) spikes[n - 3] else Long.MIN_VALUE / 4
        val gap = second - first
        val ok = gap in minGapMs..maxGapMs && first - before >= quietBeforeMs
        spikes.clear()
        return ok
    }

    companion object {
        private const val ALPHA = 0.9f
    }
}

/** How the frame is standing. */
enum class Stand { Landscape, Portrait, FaceDown, FaceUp, UpsideDown }

/**
 * Low-pass filtered gravity → how the frame stands, reported only after it has been stable for a while
 * (portrait 2 s, face down 3 s, anything else 1 s), so picking the frame up doesn't flicker the screen.
 */
class StandFilter(private val tauMs: Float = 300f) {
    private val g = FloatArray(3)
    private var lastMs = -1L
    private var candidate: Stand? = null
    private var candidateSinceMs = 0L

    var stand: Stand = Stand.Landscape
        private set

    /** Feeds one sample; returns the new stand when it changes. */
    fun add(timeMs: Long, x: Float, y: Float, z: Float): Stand? {
        if (lastMs < 0) {
            g[0] = x; g[1] = y; g[2] = z
        } else {
            val a = exp(-(timeMs - lastMs).coerceAtLeast(0) / tauMs)
            g[0] = g[0] * a + x * (1 - a)
            g[1] = g[1] * a + y * (1 - a)
            g[2] = g[2] * a + z * (1 - a)
        }
        lastMs = timeMs
        val now = classify(g[0], g[1], g[2])
        if (now != candidate) {
            candidate = now
            candidateSinceMs = timeMs
        }
        if (now != stand && timeMs - candidateSinceMs >= holdMs(now)) {
            stand = now
            return now
        }
        return null
    }

    private fun holdMs(s: Stand) = when (s) {
        Stand.Portrait -> 2_000L
        Stand.FaceDown -> 3_000L
        else -> 1_000L
    }

    companion object {
        /** Standing on its stand gives y ≈ +9.5 on this frame (03 LOG). */
        fun classify(x: Float, y: Float, z: Float): Stand {
            val ax = abs(x)
            val ay = abs(y)
            val az = abs(z)
            return when {
                ay >= ax && ay >= az -> if (y > 0) Stand.Landscape else Stand.UpsideDown
                ax >= ay && ax >= az -> Stand.Portrait
                else -> if (z > 0) Stand.FaceUp else Stand.FaceDown
            }
        }
    }
}
