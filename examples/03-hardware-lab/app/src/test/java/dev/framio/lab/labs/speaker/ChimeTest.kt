package dev.framio.lab.labs.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ChimeTest {

    private val rate = 44100
    private val noteFrames = rate * Chime.NOTE_MS / 1000
    private val gapFrames = rate * Chime.GAP_MS / 1000

    @Test
    fun `three notes with their gaps last 660 ms`() {
        assertEquals(660, Chime.durationMs())
        assertEquals((noteFrames + gapFrames) * 3 * 2, Chime.render(rate).size)
    }

    @Test
    fun `gaps between notes are silent`() {
        val s = Chime.render(rate)
        val gap = s.copyOfRange(2 * noteFrames, 2 * (noteFrames + gapFrames))
        assertTrue(gap.all { it == 0.toShort() })
    }

    @Test
    fun `loudest sample stays at the requested level`() {
        val s = Chime.render(rate, gain = 0.5)
        val peak = s.maxOf { abs(it.toInt()) }
        assertTrue(peak in 15_000..16_384)
    }

    @Test
    fun `first note rises at C5`() {
        val s = Chime.render(rate)
        // rising zero crossings of the left channel over the first note
        val crossings = (1 until noteFrames).count { s[2 * (it - 1)] < 0 && s[2 * it] >= 0 }
        assertEquals(523.25 * Chime.NOTE_MS / 1000, crossings.toDouble(), 2.0)
    }

    @Test
    fun `right-only chime leaves the left channel silent`() {
        val s = Chime.render(rate, channel = Channel.Right)
        assertTrue((0 until s.size / 2).all { s[2 * it] == 0.toShort() })
        assertTrue((0 until s.size / 2).any { s[2 * it + 1] != 0.toShort() })
    }
}
