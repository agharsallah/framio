package dev.framio.pomodoro.core.gesture

import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

public enum class Knock { Single, Double }

/**
 * @param threshold gravity-free acceleration that counts as a knock, m/s² (1.2 worked on this frame, 03 LOG)
 * @param sameKnock a knock rings: samples over the threshold less than this apart belong to the same knock,
 *   however long it rings (a slammed door rings for a few hundred ms and stays one knock)
 * @param minGap,maxGap a double knock's two knocks start this far apart
 * @param quiet a pattern ends after this long below the threshold. Longer than [maxGap], so a third knock or
 *   a regular series (footsteps, typing hard on the desk) spoils it instead of counting as a double
 * @param refractory after a double knock, knocks are ignored this long (the hand that knocked, settling)
 */
public data class KnockConfig(
    val threshold: Float = 1.2f,
    val sameKnock: Duration = 90.milliseconds,
    val minGap: Duration = 120.milliseconds,
    val maxGap: Duration = 600.milliseconds,
    val quiet: Duration = 650.milliseconds,
    val refractory: Duration = 350.milliseconds,
)

/**
 * Turns accelerometer samples into knocks: a lone knock is a [Knock.Single], two knocks 120–600 ms apart with
 * quiet around them a [Knock.Double] (Deep mode's peek). Gravity is removed with a low-pass filter seeded
 * from the first samples. Each pattern is reported once it has been quiet for [KnockConfig.quiet] (650 ms
 * after the last sample over the threshold), so a third knock can still spoil it. Pure; tests feed it traces.
 */
public class KnockDetector(public val config: KnockConfig = KnockConfig()) {
    private val gravity = FloatArray(3)
    private var primed = 0
    private var lastAboveMs = Long.MIN_VALUE / 4
    private var ignoreUntilMs = Long.MIN_VALUE / 4
    private val spikes = LongArray(MAX_SPIKES)
    private var count = 0

    private val sameMs = config.sameKnock.inWholeMilliseconds
    private val minGapMs = config.minGap.inWholeMilliseconds
    private val maxGapMs = config.maxGap.inWholeMilliseconds
    private val quietMs = config.quiet.inWholeMilliseconds
    private val refractoryMs = config.refractory.inWholeMilliseconds

    /** Added to the threshold while the app's own speaker plays, so a chime doesn't knock. */
    public var boost: Float = 0f

    /** Last gravity-free magnitude, for diagnostics. */
    public var linear: Float = 0f
        private set

    /** Feeds one sample (time in ms, axes in m/s²); returns a knock when a pattern has just completed. */
    public fun add(timeMs: Long, x: Float, y: Float, z: Float): Knock? {
        if (primed < PRIME) {
            val a = if (primed == 0) 0f else ALPHA
            gravity[0] = gravity[0] * a + x * (1 - a)
            gravity[1] = gravity[1] * a + y * (1 - a)
            gravity[2] = gravity[2] * a + z * (1 - a)
            primed++
            return null
        }
        val lx = x - gravity[0]
        val ly = y - gravity[1]
        val lz = z - gravity[2]
        gravity[0] = gravity[0] * ALPHA + x * (1 - ALPHA)
        gravity[1] = gravity[1] * ALPHA + y * (1 - ALPHA)
        gravity[2] = gravity[2] * ALPHA + z * (1 - ALPHA)
        linear = sqrt(lx * lx + ly * ly + lz * lz)
        // A pattern that has been quiet long enough ends before this sample can join it
        val knock = close(timeMs)
        if (linear > config.threshold + boost) {
            if (timeMs >= ignoreUntilMs && timeMs - lastAboveMs > sameMs) {
                if (count < MAX_SPIKES) spikes[count] = timeMs
                count++
            }
            lastAboveMs = timeMs
        }
        return knock
    }

    /** Forgets any pattern in progress (the sensor was paused). Gravity is kept. */
    public fun reset() {
        count = 0
        lastAboveMs = Long.MIN_VALUE / 4
    }

    private fun close(nowMs: Long): Knock? {
        if (count == 0 || nowMs - lastAboveMs < quietMs) return null
        val knock = when {
            count == 1 -> Knock.Single
            count == 2 && spikes[1] - spikes[0] in minGapMs..maxGapMs -> Knock.Double
            else -> null
        }
        count = 0
        if (knock == Knock.Double) ignoreUntilMs = nowMs + refractoryMs
        return knock
    }

    private companion object {
        const val PRIME = 10
        const val ALPHA = 0.9f
        const val MAX_SPIKES = 4
    }
}
