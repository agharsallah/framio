package dev.framio.lab.labs.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnockDetectorTest {

    /** 10 ms samples of the frame resting on its stand. */
    private fun rested(): KnockDetector = KnockDetector().apply {
        repeat(50) { add(it * 10L, 0.1f, 9.5f, 3.4f) }
    }

    @Test
    fun `sensor noise while resting is not a knock`() {
        val d = rested()
        assertFalse(d.add(600, 0.15f, 9.45f, 3.5f))
        assertEquals(0, d.count)
    }

    @Test
    fun `a sharp spike is a knock`() {
        val d = rested()
        assertTrue(d.add(600, 0.1f, 9.5f, 6.0f))
        assertEquals(1, d.count)
    }

    @Test
    fun `a second spike inside the refractory window is the same knock`() {
        val d = rested()
        d.add(600, 0.1f, 9.5f, 6.0f)
        assertFalse(d.add(700, 0.1f, 9.5f, 6.0f))
        assertEquals(1, d.count)
    }

    @Test
    fun `a spike after the refractory window is a new knock`() {
        val d = rested()
        d.add(600, 0.1f, 9.5f, 6.0f)
        repeat(30) { d.add(700L + it * 10, 0.1f, 9.5f, 3.4f) }
        assertTrue(d.add(1100, 0.1f, 9.5f, 6.0f))
        assertEquals(2, d.count)
    }

    @Test
    fun `the first samples only seed gravity`() {
        val d = KnockDetector()
        assertFalse(d.add(0, 0f, 30f, 0f))
    }
}
