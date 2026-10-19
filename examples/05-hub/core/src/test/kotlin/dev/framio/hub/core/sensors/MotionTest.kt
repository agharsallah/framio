package dev.framio.hub.core.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Synthetic traces at 100 Hz: the frame standing (y ≈ 9.5) with one-sample spikes where something hits it.
 * Spike S4 adds traces recorded on the frame (`--es record accel`) next to these.
 */
class KnockPatternTest {
    private fun run(knocksMs: List<Long>, lengthMs: Long = 3_000, strength: Float = 3f, pattern: KnockPattern = KnockPattern()): List<Long> {
        val hits = mutableListOf<Long>()
        var t = 0L
        while (t < lengthMs) {
            val spike = knocksMs.any { it == t }
            if (pattern.add(t, 0.1f, 9.5f + if (spike) strength else 0f, 1.2f)) hits += t
            t += 10
        }
        return hits
    }

    @Test fun `a double knock is detected once, 650 ms after the second knock`() {
        assertEquals(listOf(1_950L), run(listOf(1_000, 1_300)))
    }

    @Test fun `gaps outside 120 to 600 ms are not a double knock`() {
        assertEquals(emptyList<Long>(), run(listOf(1_000, 1_080)))
        assertEquals(emptyList<Long>(), run(listOf(1_000, 1_700)))
    }

    @Test fun `one knock, a slammed door, is nothing`() {
        assertEquals(emptyList<Long>(), run(listOf(1_000)))
    }

    @Test fun `three knocks or footsteps are not a double knock`() {
        assertEquals(emptyList<Long>(), run(listOf(1_000, 1_300, 1_600)))
        for (period in listOf(130L, 300L, 450L, 600L)) {
            assertEquals("steps every $period ms", emptyList<Long>(), run((0 until 8).map { 500L + it * period }, lengthMs = 6_000))
        }
    }

    @Test fun `a knock just before the pair spoils it`() {
        assertEquals(emptyList<Long>(), run(listOf(800, 1_100, 1_400)))
    }

    @Test fun `weak taps below the threshold, or below it with the speaker boost, are ignored`() {
        assertEquals(emptyList<Long>(), run(listOf(1_000, 1_300), strength = 1.0f))
        val loud = KnockPattern().apply { boost = 2.5f }
        assertEquals(emptyList<Long>(), run(listOf(1_000, 1_300), strength = 3f, pattern = loud))
    }
}

class StandFilterTest {
    private fun feed(f: StandFilter, fromMs: Long, toMs: Long, x: Float, y: Float, z: Float): List<Pair<Long, Stand>> {
        val changes = mutableListOf<Pair<Long, Stand>>()
        var t = fromMs
        while (t < toMs) {
            f.add(t, x, y, z)?.let { changes += t to it }
            t += 20
        }
        return changes
    }

    @Test fun `face down is reported after 3 s and standing up after 1 s`() {
        val f = StandFilter()
        assertEquals(emptyList<Pair<Long, Stand>>(), feed(f, 0, 2_000, 0.1f, 9.5f, 1.2f))
        val down = feed(f, 2_000, 8_000, 0f, 0.3f, -9.7f)
        assertEquals(Stand.FaceDown, down.single().second)
        assertEquals(5_000.0, down.single().first.toDouble(), 600.0) // 3 s hold plus the filter's settling
        val up = feed(f, 8_000, 11_000, 0.1f, 9.5f, 1.2f)
        assertEquals(Stand.Landscape, up.single().second)
    }

    @Test fun `picking it up for a moment doesn't count`() {
        val f = StandFilter()
        feed(f, 0, 1_000, 0.1f, 9.5f, 1.2f)
        assertEquals(emptyList<Pair<Long, Stand>>(), feed(f, 1_000, 2_500, 0f, 0.3f, -9.7f))
        assertEquals(emptyList<Pair<Long, Stand>>(), feed(f, 2_500, 5_000, 0.1f, 9.5f, 1.2f))
        assertEquals(Stand.Landscape, f.stand)
    }

    @Test fun `portrait either way round`() {
        assertEquals(Stand.Portrait, StandFilter.classify(9.5f, 0.2f, 1f))
        assertEquals(Stand.Portrait, StandFilter.classify(-9.5f, 0.2f, 1f))
        assertEquals(Stand.UpsideDown, StandFilter.classify(0f, -9.5f, 1f))
        assertNull(StandFilter().add(0, 0f, 9.5f, 0f))
    }
}
