package dev.framio.pomodoro.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val SR = Pcm.SAMPLE_RATE

/** Strength of [hz] in a window (Goertzel), about 0..1 for a full-scale sine. */
private fun magnitude(s: ShortArray, from: Int, count: Int, hz: Double): Double {
    val coeff = 2 * cos(2 * PI * hz / SR)
    var s1 = 0.0
    var s2 = 0.0
    for (i in from until minOf(from + count, s.size)) {
        val s0 = s[i] / 32768.0 + coeff * s1 - s2
        s2 = s1
        s1 = s0
    }
    return sqrt((s1 * s1 + s2 * s2 - coeff * s1 * s2).coerceAtLeast(0.0)) / (count / 2.0)
}

/** Energy of the first difference relative to the signal: higher means brighter. */
private fun brightness(s: ShortArray): Double {
    var d = 0.0
    var e = 0.0
    for (i in 1 until s.size) {
        val x = s[i].toDouble()
        d += (x - s[i - 1]) * (x - s[i - 1])
        e += x * x
    }
    return d / e
}

/** 3 for Gaussian noise; higher when sparse peaks ride on it. */
private fun kurtosis(s: ShortArray): Double {
    var m2 = 0.0
    var m4 = 0.0
    for (x in s) {
        val v = x / 32768.0
        m2 += v * v
        m4 += v * v * v * v
    }
    m2 /= s.size
    m4 /= s.size
    return m4 / (m2 * m2)
}

class ChimeSynthTest {
    private val chime = ChimeSynth.render(peakDb = -12.0)

    @Test
    fun `peaks where asked, never clips, starts and ends at zero`() {
        val target = Pcm.dbToGain(-12.0) * Pcm.FULL_SCALE
        assertEquals(target, Pcm.peak(chime).toDouble(), target * 0.01)
        assertEquals(0, chime.first().toInt())
        assertTrue(abs(chime.last().toInt()) <= 2)
        assertEquals(2.02, chime.size.toDouble() / SR, 0.01)
    }

    @Test
    fun `the attack is gentle`() {
        val peak = Pcm.peak(chime)
        assertTrue(Pcm.peak(chime, 0, Pcm.frames(5.0)) < peak * 0.15)
        assertTrue(Pcm.peak(chime, 0, Pcm.frames(25.0)) > peak * 0.5)
    }

    @Test
    fun `two notes, E5 then A5`() {
        val early = Pcm.frames(100.0)
        val late = Pcm.frames(500.0)
        val n = Pcm.frames(100.0)
        assertTrue(magnitude(chime, early, n, 659.255) > 5 * magnitude(chime, early, n, 880.0))
        assertTrue(magnitude(chime, late, n, 880.0) > 0.02)
    }

    @Test
    fun `the rising cue starts at -24 dB and climbs evenly over 8 s`() {
        val cue = ChimeSynth.rising()
        val starts = ChimeSynth.risingStarts()
        val peaks = starts.mapIndexed { i, at -> Pcm.peak(cue, at, starts.getOrElse(i + 1) { cue.size }) }
        assertEquals(Pcm.dbToGain(-24.0) * Pcm.FULL_SCALE, peaks[0].toDouble(), peaks[0] * 0.02)
        assertEquals(Pcm.dbToGain(-18.0) * Pcm.FULL_SCALE, peaks[1].toDouble(), peaks[1] * 0.02)
        assertEquals(Pcm.dbToGain(-12.0) * Pcm.FULL_SCALE, peaks[2].toDouble(), peaks[2] * 0.02)
        assertTrue(cue.size < 8 * SR)
        assertEquals(0, cue.first().toInt())
    }
}

class NoiseSynthTest {
    @Test
    fun `30 s of pink and brown never get near clipping`() {
        for (color in NoiseColor.entries) {
            val s = NoiseSynth(color, seed = 7).next(30 * SR)
            assertTrue("$color peak ${Pcm.peak(s)}", Pcm.peak(s) < Pcm.FULL_SCALE * 0.9)
            assertTrue("$color is silent", Pcm.rms(s, SR * 3, SR * 4) > 0.02)
        }
    }

    @Test
    fun `chunked output is seamless`() {
        for (color in NoiseColor.entries) {
            val whole = NoiseSynth(color, seed = 3).next(SR)
            val chunked = ShortArray(SR)
            val gen = NoiseSynth(color, seed = 3)
            var at = 0
            for (size in generateSequence(1_000) { it * 3 / 2 + 7 }) {
                val n = minOf(size, SR - at)
                gen.fill(chunked, at, n)
                at += n
                if (at == SR) break
            }
            assertArrayEquals(whole, chunked)
        }
    }

    @Test
    fun `it fades in from silence and out to silence`() {
        val gen = NoiseSynth(NoiseColor.Pink, seed = 1, fadeIn = 2.seconds)
        val s = gen.next(4 * SR)
        assertEquals(0, s[0].toInt())
        val start = Pcm.rms(s, 0, Pcm.frames(50.0))
        val mid = Pcm.rms(s, SR / 2, SR)
        val steady = Pcm.rms(s, 3 * SR, 4 * SR)
        assertTrue(start < steady * 0.05)
        assertTrue(mid in start..steady)
        gen.fadeOut(500.milliseconds)
        assertFalse(gen.isSilent)
        gen.next(SR / 2)
        assertTrue(gen.isSilent)
        assertTrue(gen.next(1_000).all { it.toInt() == 0 })
    }

    @Test
    fun `brown is darker than pink, and seeds are reproducible`() {
        val pink = NoiseSynth(NoiseColor.Pink, seed = 5, fadeIn = 0.seconds).next(2 * SR)
        val brown = NoiseSynth(NoiseColor.Brown, seed = 5, fadeIn = 0.seconds).next(2 * SR)
        assertTrue(brightness(brown) < brightness(pink) / 4)
        assertArrayEquals(pink, NoiseSynth(NoiseColor.Pink, seed = 5, fadeIn = 0.seconds).next(2 * SR))
        assertFalse(pink.contentEquals(NoiseSynth(NoiseColor.Pink, seed = 6, fadeIn = 0.seconds).next(2 * SR)))
    }
}

class RainSynthTest {
    @Test
    fun `rain never clips and is seamless across buffers`() {
        val s = RainSynth(seed = 2).next(30 * SR)
        assertTrue("peak ${Pcm.peak(s)}", Pcm.peak(s) < Pcm.FULL_SCALE * 0.9)
        assertTrue(Pcm.rms(s, 3 * SR, 4 * SR) > 0.02)
        val whole = RainSynth(seed = 9).next(SR)
        val gen = RainSynth(seed = 9)
        val parts = ShortArray(SR)
        gen.fill(parts, 0, 12_345)
        gen.fill(parts, 12_345, SR - 12_345)
        assertArrayEquals(whole, parts)
    }

    @Test
    fun `droplets add bright clicks on top of the bed, and it fades`() {
        val dry = RainSynth(seed = 4, dropsPerSecond = 0.0, fadeIn = 0.seconds).next(3 * SR)
        val wet = RainSynth(seed = 4, dropsPerSecond = 12.0, fadeIn = 0.seconds).next(3 * SR)
        assertTrue(kurtosis(wet) > kurtosis(dry) + 0.2) // sparse, peaky clicks
        assertTrue(Pcm.rms(wet) > Pcm.rms(dry))
        val gen = RainSynth(seed = 4, fadeIn = 1.seconds)
        val s = gen.next(2 * SR)
        assertEquals(0, s[0].toInt())
        assertTrue(Pcm.rms(s, 0, Pcm.frames(20.0)) < Pcm.rms(s, SR, 2 * SR) * 0.05)
        gen.fadeOut(200.milliseconds)
        gen.next(SR / 4)
        assertTrue(gen.isSilent)
    }
}

/** RMS of consecutive [ms] windows. */
private fun windows(s: ShortArray, ms: Double, from: Int = 0): List<Double> {
    val n = Pcm.frames(ms)
    return (from until s.size - n step n).map { Pcm.rms(s, it, it + n) }
}

class WindSynthTest {
    @Test
    fun `a minute of wind never gets near clipping, for any seed, and is audible`() {
        for (seed in 1L..6L) {
            val s = WindSynth(seed = seed).next(60 * SR)
            assertTrue("seed $seed peak ${Pcm.peak(s)}", Pcm.peak(s) < Pcm.FULL_SCALE * 0.9)
            assertTrue("seed $seed rms ${Pcm.rms(s, 3 * SR, 60 * SR)}", Pcm.rms(s, 3 * SR, 60 * SR) > 0.02)
        }
    }

    @Test
    fun `chunked output is seamless`() {
        val whole = WindSynth(seed = 8).next(2 * SR)
        val gen = WindSynth(seed = 8)
        val parts = ShortArray(2 * SR)
        var at = 0
        for (size in listOf(1, 31, 33, 1_000, 4_096, 12_345)) {
            gen.fill(parts, at, size)
            at += size
        }
        gen.fill(parts, at, parts.size - at)
        assertArrayEquals(whole, parts)
    }

    @Test
    fun `it fades in from silence and out to silence`() {
        val gen = WindSynth(seed = 3, fadeIn = 2.seconds)
        val s = gen.next(4 * SR)
        assertEquals(0, s[0].toInt())
        assertTrue(Pcm.rms(s, 0, Pcm.frames(50.0)) < Pcm.rms(s, 3 * SR, 4 * SR) * 0.05)
        gen.fadeOut(300.milliseconds)
        assertFalse(gen.isSilent)
        gen.next(SR / 2)
        assertTrue(gen.isSilent)
        assertTrue(gen.next(1_000).all { it.toInt() == 0 })
    }

    @Test
    fun `it gusts - the loudness swells and settles, where steady noise doesn't`() {
        val wind = windows(WindSynth(seed = 5, fadeIn = 0.seconds).next(30 * SR), 250.0)
        val pink = windows(NoiseSynth(NoiseColor.Pink, seed = 5, fadeIn = 0.seconds).next(30 * SR), 250.0)
        val windSwing = wind.max() / wind.min()
        val pinkSwing = pink.max() / pink.min()
        assertTrue("wind swing $windSwing", windSwing > 2.0)
        assertTrue("pink swing $pinkSwing", pinkSwing < 1.5)
        // Slow: neighbouring quarter-seconds are close, it's not a flutter
        val jumps = wind.zipWithNext { a, b -> maxOf(a, b) / minOf(a, b) }
        assertTrue("median jump ${jumps.sorted()[jumps.size / 2]}", jumps.sorted()[jumps.size / 2] < 1.25)
    }

    @Test
    fun `wind is darker than pink noise, and seeds are reproducible`() {
        val wind = WindSynth(seed = 2, fadeIn = 0.seconds).next(5 * SR)
        val pink = NoiseSynth(NoiseColor.Pink, seed = 2, fadeIn = 0.seconds).next(5 * SR)
        assertTrue("wind ${brightness(wind)} pink ${brightness(pink)}", brightness(wind) < brightness(pink) / 2)
        assertArrayEquals(wind, WindSynth(seed = 2, fadeIn = 0.seconds).next(5 * SR))
        assertFalse(wind.contentEquals(WindSynth(seed = 3, fadeIn = 0.seconds).next(5 * SR)))
    }

    @Test
    fun `at half the sample rate it sounds the same, for half the work`() {
        val half = SR / 2
        val s = WindSynth(seed = 4, sampleRate = half, fadeIn = 0.seconds).next(30 * half)
        assertTrue(Pcm.peak(s) < Pcm.FULL_SCALE * 0.9)
        val w = (0 until 30 * half - half / 4 step half / 4).map { Pcm.rms(s, it, it + half / 4) }
        assertTrue(w.max() / w.min() > 2.0)
    }
}
