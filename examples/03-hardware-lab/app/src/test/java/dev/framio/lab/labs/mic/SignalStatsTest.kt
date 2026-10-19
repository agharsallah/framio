package dev.framio.lab.labs.mic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SignalStatsTest {

    @Test
    fun `constant codec offset with a couple of LSB jitter is flat`() {
        val samples = ShortArray(16000) { (1274 + it % 3 - 1).toShort() }
        val s = SignalStats.of(samples)
        assertTrue(s.flat)
        assertEquals(1274.0, s.dc, 0.5)
    }

    @Test
    fun `slow offset drift after power-up is flat even though it moves hundreds of LSB`() {
        // measured on the frame: offset settles over ~1.5 s
        val samples = ShortArray(16000) { (-2000 + it / 20).toShort() }
        val s = SignalStats.of(samples)
        assertTrue(s.noise > 200)
        assertTrue(s.flat)
    }

    @Test
    fun `room noise from a real mic is not flat`() {
        val r = Random(1)
        val samples = ShortArray(16000) { (r.nextInt(-60, 61)).toShort() }
        assertFalse(SignalStats.of(samples).flat)
    }

    @Test
    fun `full-scale square wave is 0 dBFS`() {
        val samples = ShortArray(1000) { if (it % 2 == 0) Short.MAX_VALUE else (-Short.MAX_VALUE).toShort() }
        assertEquals(0.0, SignalStats.of(samples).dbfs, 0.01)
    }

    @Test
    fun `digital silence is minus infinity`() {
        assertEquals(Double.NEGATIVE_INFINITY, SignalStats.of(ShortArray(100)).dbfs, 0.0)
    }

    @Test
    fun `only the counted part of the buffer is measured`() {
        val samples = ShortArray(100) { if (it < 50) 10 else 30000 }
        assertEquals(10.0, SignalStats.of(samples, count = 50).dc, 0.0)
    }
}
