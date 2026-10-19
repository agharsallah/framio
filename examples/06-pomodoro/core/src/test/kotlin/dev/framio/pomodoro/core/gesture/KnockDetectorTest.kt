package dev.framio.pomodoro.core.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Synthetic traces at 20 ms (SENSOR_DELAY_GAME on this frame): the frame resting on its stand (y ≈ 9.5,
 * z ≈ 3.4, 03 LOG) with sensor noise, plus knocks, slams, steps and typing.
 */
class KnockDetectorTest {
    private class Trace(seed: Int = 1, val noise: Float = 0.05f, val stepMs: Long = 20) {
        private val random = Random(seed)
        private val bumps = sortedMapOf<Long, Float>()

        /** A knock at [t]: a sharp spike that rings for a few samples. */
        fun knock(t: Long, strength: Float = 4f, ringMs: Long = 40) = apply {
            val start = (t + 10) / 20 * 20 // onto the sample grid
            var at = start
            var s = strength
            while (at <= start + ringMs) {
                bumps[at] = s
                at += 20
                s *= -0.6f
            }
        }

        /** A slow ring: [amp] alternating in sign every sample for [ms]. */
        fun ring(t: Long, ms: Long, amp: Float) = apply {
            for (at in t..t + ms step 20) bumps[at] = if ((at - t) % 40 == 0L) amp else -amp
        }

        fun bump(t: Long, strength: Float) = apply { bumps[(t + 10) / 20 * 20] = strength }

        /** Runs [ms] of samples through [d]; returns (time, knock) for every knock reported. */
        fun run(d: KnockDetector = KnockDetector(), ms: Long = 5_000): List<Pair<Long, Knock>> {
            val out = mutableListOf<Pair<Long, Knock>>()
            var t = 0L
            while (t <= ms) {
                val z = 3.4f + (bumps[t] ?: 0f) + gauss() * noise
                d.add(t, 0.1f + gauss() * noise, 9.5f + gauss() * noise, z)?.let { out += t to it }
                t += stepMs
            }
            return out
        }

        private fun gauss() = (random.nextFloat() + random.nextFloat() + random.nextFloat() - 1.5f) * 2f
    }

    private fun knocks(trace: Trace, d: KnockDetector = KnockDetector()) = trace.run(d).map { it.second }

    @Test
    fun `resting noise is nothing`() {
        assertEquals(emptyList<Knock>(), knocks(Trace(noise = 0.15f)))
    }

    @Test
    fun `the frame's measured rest noise at 100 Hz, about 0_45 on z, is nothing`() {
        // gauss() spans ±3σ: σ 0.15 gives the ±0.45 measured in M0, on every axis
        for (seed in 1..5) assertEquals(emptyList<Knock>(), Trace(seed, noise = 0.15f, stepMs = 10).run(ms = 30_000).map { it.second })
    }

    @Test
    fun `a single tap is a single, reported once quiet`() {
        val events = Trace().knock(1_000).run()
        assertEquals(listOf(Knock.Single), events.map { it.second })
        assertTrue(events.single().first in 1_650..1_800)
    }

    @Test
    fun `two knocks 120 to 600 ms apart are a double`() {
        for (gap in listOf(140L, 250L, 400L, 600L)) {
            assertEquals("gap $gap", listOf(Knock.Double), knocks(Trace().knock(1_000, ringMs = 20).knock(1_000 + gap, ringMs = 20)))
        }
    }

    @Test
    fun `too close is one knock, too far apart is two singles`() {
        assertEquals(listOf(Knock.Single), knocks(Trace().knock(1_000, ringMs = 20).knock(1_080, ringMs = 20)))
        assertEquals(listOf(Knock.Single, Knock.Single), knocks(Trace().knock(1_000).knock(1_700)))
        assertEquals(emptyList<Knock>(), knocks(Trace().knock(1_000, ringMs = 0).knock(1_100, ringMs = 0))) // 100 ms: neither
    }

    @Test
    fun `a third knock spoils the double`() {
        assertEquals(emptyList<Knock>(), knocks(Trace().knock(1_000).knock(1_250).knock(1_500)))
    }

    @Test
    fun `a slammed door rings long but is never a double`() {
        val slam = Trace().knock(1_000, strength = 9f).ring(1_060, ms = 300, amp = 2.5f)
        assertEquals(listOf(Knock.Single), knocks(slam))
    }

    @Test
    fun `footsteps and hard typing are regular series, not knocks`() {
        val steps = Trace()
        for (t in 1_000L..4_000L step 500) steps.knock(t, strength = 1.8f)
        assertEquals(emptyList<Knock>(), knocks(steps))
        val typing = Trace()
        for (t in 1_000L..4_000L step 160) typing.bump(t, if (t % 480 == 0L) 1.5f else 0.6f)
        assertTrue(knocks(typing).none { it == Knock.Double })
    }

    @Test
    fun `after a double, knocks are ignored for 350 ms`() {
        val trace = Trace().knock(1_000, ringMs = 0).knock(1_250, ringMs = 0)
        // the double is reported at 1900; a knock at 2100 falls in the refractory time, one at 2400 doesn't
        trace.knock(2_100, ringMs = 0).knock(3_400, ringMs = 0)
        val events = trace.run()
        assertEquals(listOf(Knock.Double, Knock.Single), events.map { it.second })
        assertTrue(events[0].first in 1_900..1_960)
        assertTrue(events[1].first >= 4_050)
    }

    @Test
    fun `the threshold and the speaker boost are respected`() {
        assertEquals(emptyList<Knock>(), knocks(Trace().knock(1_000, strength = 1.0f)))
        val d = KnockDetector().apply { boost = 2f }
        assertEquals(emptyList<Knock>(), knocks(Trace().knock(1_000, strength = 2.5f), d))
        assertEquals(listOf(Knock.Single), knocks(Trace().knock(1_000, strength = 2.5f)))
        val sensitive = KnockDetector(KnockConfig(threshold = 0.8f))
        assertEquals(listOf(Knock.Single), knocks(Trace().knock(1_000, strength = 1.0f), sensitive))
    }

    @Test
    fun `the first samples only seed gravity`() {
        val d = KnockDetector()
        repeat(10) { assertEquals(null, d.add(it * 20L, 0f, 9.5f, if (it == 9) 20f else 3.4f)) }
        assertEquals(0f, d.linear)
    }

    @Test
    fun `reset forgets a pattern in progress`() {
        val d = KnockDetector()
        var t = 0L
        val out = mutableListOf<Knock>()
        while (t <= 3_000) {
            if (t == 1_200L) d.reset()
            d.add(t, 0.1f, 9.5f, if (t == 1_000L) 7.4f else 3.4f)?.let { out += it }
            t += 20
        }
        assertEquals(emptyList<Knock>(), out)
    }
}
