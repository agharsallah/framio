package dev.framio.lab.labs.speaker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SpectrumTest {

    @Test
    fun `visualizer fft bytes become per-bin magnitudes`() {
        // [DC re, Nyquist re, re1, im1, re2, im2, re3, im3]
        val fft = byteArrayOf(-5, 9, 3, 4, 0, 0, 6, 8)
        assertArrayEquals(floatArrayOf(5f, 5f, 0f, 10f), Spectrum.magnitudesOf(fft), 1e-5f)
    }

    @Test
    fun `peak ignores a large DC term`() {
        assertEquals(2, Spectrum.peakBin(floatArrayOf(100f, 1f, 7f, 3f)))
    }
}
