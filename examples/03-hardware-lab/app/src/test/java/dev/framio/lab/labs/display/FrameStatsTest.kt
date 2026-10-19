package dev.framio.lab.labs.display

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameStatsTest {

    private val frame57 = (1e9 / 57.17).toLong()

    @Test
    fun `steady intervals give the refresh rate and no janks`() {
        val s = FrameStats()
        repeat(100) { s.add(frame57) }
        assertEquals(57.17, s.fps, 0.01)
        assertEquals(0, s.janks(57.17))
    }

    @Test
    fun `a skipped vsync counts as one jank and is the worst frame`() {
        val s = FrameStats()
        repeat(10) { s.add(frame57) }
        s.add(frame57 * 2)
        assertEquals(1, s.janks(57.17))
        assertEquals(frame57 * 2 / 1e6, s.worstMs, 1e-6)
    }

    @Test
    fun `only the most recent intervals are kept`() {
        val s = FrameStats(capacity = 3)
        s.add(frame57 * 3)
        repeat(3) { s.add(frame57) }
        assertEquals(3, s.count)
        assertEquals(0, s.janks(57.17))
    }

    @Test
    fun `non-positive intervals are ignored`() {
        val s = FrameStats()
        s.add(0)
        s.add(-5)
        assertEquals(0, s.count)
        assertEquals(0.0, s.fps, 0.0)
    }
}
