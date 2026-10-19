package dev.framio.lab.labs.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class YuvPatternTest {

    @Test
    fun `frame is width times height times one and a half bytes`() {
        assertEquals(1280 * 720 * 3 / 2, YuvPattern.FrameBuilder(1280, 720, planar = false).frame(0).size)
    }

    @Test
    fun `luma scrolls 8 pixels per frame`() {
        val f0 = YuvPattern.lumaRow(64, 0)
        val f1 = YuvPattern.lumaRow(64, 1)
        assertEquals(f0[8], f1[0])
        assertNotEquals(f0[0], f1[0])
    }

    @Test
    fun `nv12 interleaves U and V after the luma plane`() {
        val frame = YuvPattern.FrameBuilder(16, 2, planar = false).frame(0)
        val (u, v) = YuvPattern.chroma(0, 16)
        assertArrayEquals(byteArrayOf(u, v), frame.copyOfRange(32, 34))
    }

    @Test
    fun `i420 keeps U and V in separate planes`() {
        val frame = YuvPattern.FrameBuilder(16, 2, planar = true).frame(0)
        val (u, v) = YuvPattern.chroma(0, 16)
        assertEquals(u, frame[32]) // U plane starts after 16×2 luma
        assertEquals(v, frame[32 + 8]) // V plane after 8×1 U
    }

    @Test
    fun `chroma stays put while luma changes between frames`() {
        val b = YuvPattern.FrameBuilder(16, 2, planar = false)
        val first = b.frame(0).copyOf()
        val second = b.frame(3).copyOf()
        assertArrayEquals(first.copyOfRange(32, 48), second.copyOfRange(32, 48))
        assertNotEquals(first[0], second[0])
    }
}
