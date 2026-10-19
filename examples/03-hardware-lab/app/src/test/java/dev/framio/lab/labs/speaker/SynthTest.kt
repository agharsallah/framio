package dev.framio.lab.labs.speaker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SynthTest {

    @Test
    fun `waveforms hit their defining points`() {
        assertEquals(1.0, Waveform.Sine.at(0.25), 1e-9)
        assertEquals(1.0, Waveform.Square.at(0.49), 0.0)
        assertEquals(-1.0, Waveform.Square.at(0.5), 0.0)
        assertEquals(1.0, Waveform.Triangle.at(0.5), 1e-9)
        assertEquals(-1.0, Waveform.Triangle.at(0.0), 1e-9)
        assertEquals(-1.0, Waveform.Saw.at(0.0), 1e-9)
    }

    /** Rising zero crossings of the left channel = number of periods. */
    private fun periods(stereo: ShortArray): Int =
        (1 until stereo.size / 2).count { stereo[2 * (it - 1)] < 0 && stereo[2 * it] >= 0 }

    @Test
    fun `one second at 1 kHz holds 1000 periods`() {
        val out = ShortArray(44100 * 2)
        Oscillator(44100).render(out, 44100, 1000.0, 0.5, Waveform.Sine, Channel.Both)
        assertEquals(1000.0, periods(out).toDouble(), 1.0)
    }

    @Test
    fun `phase carries over between blocks so two halves equal one whole`() {
        val whole = ShortArray(2000)
        Oscillator(44100).render(whole, 1000, 440.0, 0.5, Waveform.Sine, Channel.Both)
        val osc = Oscillator(44100)
        val a = ShortArray(1000)
        val b = ShortArray(1000)
        osc.render(a, 500, 440.0, 0.5, Waveform.Sine, Channel.Both)
        osc.render(b, 500, 440.0, 0.5, Waveform.Sine, Channel.Both)
        assertArrayEquals(whole, a + b)
    }

    @Test
    fun `left channel setting silences the right samples`() {
        val out = ShortArray(200)
        Oscillator(44100).render(out, 100, 440.0, 1.0, Waveform.Square, Channel.Left)
        assertEquals(Short.MAX_VALUE, out[0])
        assertEquals(0.toShort(), out[1])
    }

    @Test
    fun `log sweep is at its ends at start and finish and at the geometric mean halfway`() {
        assertEquals(20.0, Sweep.frequencyAt(0.0, 10.0, 20.0, 16_000.0), 1e-9)
        assertEquals(16_000.0, Sweep.frequencyAt(10.0, 10.0, 20.0, 16_000.0), 1e-6)
        assertEquals(Math.sqrt(20.0 * 16_000.0), Sweep.frequencyAt(5.0, 10.0, 20.0, 16_000.0), 1e-6)
        assertEquals(16_000.0, Sweep.frequencyAt(12.0, 10.0, 20.0, 16_000.0), 1e-6)
    }

    @Test
    fun `minus 6 dB is about half amplitude`() {
        assertEquals(0.501, dbToGain(-6.0), 0.001)
        assertEquals(1.0, dbToGain(0.0), 0.0)
    }

    @Test
    fun `wav header describes the samples`() {
        val wav = Wav.pcm16(shortArrayOf(1, -1, 300), 16000, 1)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(36 + 6, b.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(1, b.getShort(22).toInt()) // channels
        assertEquals(16000, b.getInt(24)) // sample rate
        assertEquals(32000, b.getInt(28)) // byte rate
        assertEquals(6, b.getInt(40)) // data bytes
        assertEquals(300, b.getShort(48).toInt())
        assertEquals(50, wav.size)
    }
}
