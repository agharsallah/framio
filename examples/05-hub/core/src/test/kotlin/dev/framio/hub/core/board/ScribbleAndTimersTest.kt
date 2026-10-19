package dev.framio.hub.core.board

import dev.framio.hub.core.feeds.RefreshPolicy
import dev.framio.hub.core.timers.Durations
import dev.framio.hub.core.timers.Melodies
import dev.framio.hub.core.timers.MelodySynth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.math.abs
import kotlin.random.Random

class ScribbleCodecTest {
    private fun stroke(ink: Int, n: Int, seed: Int): Stroke {
        val r = Random(seed)
        var x = 0.5f
        var y = 0.5f
        return Stroke(ink, FloatArray(n * 2) { i -> if (i % 2 == 0) { x = (x + r.nextFloat() * 0.02f - 0.01f).coerceIn(0f, 1f); x } else { y = (y + r.nextFloat() * 0.02f - 0.01f).coerceIn(0f, 1f); y } })
    }

    @Test fun `strokes round-trip to within a third of a pixel`() {
        val strokes = listOf(stroke(0, 300, 1), stroke(2, 120, 2), stroke(3, 1, 3))
        val decoded = ScribbleCodec.decode(ScribbleCodec.encode(strokes))
        assertEquals(strokes.map { it.ink }, decoded.map { it.ink })
        strokes.zip(decoded).forEach { (a, b) ->
            assertEquals(a.points.size, b.points.size)
            a.points.indices.forEach { i -> assertTrue(abs(a.points[i] - b.points[i]) * 1280 < 0.33f) }
        }
        val again = ScribbleCodec.decode(ScribbleCodec.encode(decoded))
        assertEquals("encoding a decoded scribble is exact", decoded, again)
    }

    @Test fun `a typical scribble is a few KB`() {
        val bytes = ScribbleCodec.encode((0 until 20).map { stroke(it % 4, 100, it) })
        assertTrue("${bytes.size} bytes", bytes.size in 1_000..8_000)
    }

    @Test(expected = IOException::class) fun `truncated data is an error, not garbage`() {
        val bytes = ScribbleCodec.encode(listOf(stroke(0, 50, 1)))
        ScribbleCodec.decode(bytes.copyOf(bytes.size - 3))
    }

    @Test(expected = IOException::class) fun `other files are rejected`() {
        ScribbleCodec.decode("hello world".toByteArray())
    }
}

class TimersMathTest {
    @Test fun `durations parse the way people write them`() {
        assertEquals(180_000L, Durations.parse("3m"))
        assertEquals(90_000L, Durations.parse("90s"))
        assertEquals(5_400_000L, Durations.parse("1h30m"))
        assertEquals(3_910_000L, Durations.parse("1h 5m 10s"))
        assertEquals(600_000L, Durations.parse("10"))
        assertNull(Durations.parse("soon"))
        assertNull(Durations.parse("0m"))
        assertNull(Durations.parse(""))
    }

    @Test fun `clock text rounds up so 0_00 means done`() {
        assertEquals("4:32", Durations.clock(271_001))
        assertEquals("0:01", Durations.clock(1))
        assertEquals("0:00", Durations.clock(0))
        assertEquals("1:02:03", Durations.clock(3_723_000))
    }

    @Test fun `every melody plays its notes at the right pitch, without clicks`() {
        for (melody in Melodies.all) {
            val pcm = MelodySynth.render(melody, gain = 0.5)
            assertTrue(pcm.maxOf { abs(it.toInt()) } <= (0.5 * Short.MAX_VALUE).toInt() + 1)
            assertTrue("starts silent", abs(pcm.first().toInt()) < 200)
            assertTrue("ends silent", abs(pcm.last().toInt()) < 200)
            for (tone in melody) {
                val from = MelodySynth.SAMPLE_RATE * (tone.startMs + 10) / 1000
                val count = MelodySynth.SAMPLE_RATE * 60 / 1000
                val right = MelodySynth.magnitude(pcm, from, count, tone.hz)
                val off = MelodySynth.magnitude(pcm, from, count, tone.hz * 1.06) // a semitone away
                assertTrue("${tone.midi}: $right vs $off", right > 3 * off)
            }
        }
    }

    @Test fun `melodies are all different and padding makes the alarm loop length`() {
        assertEquals(Melodies.count, Melodies.all.map { m -> m.map { it.midi } }.toSet().size)
        val looped = MelodySynth.render(Melodies.get(0), lengthMs = 4_000)
        assertEquals(4_000L, MelodySynth.durationMs(looped))
    }

    @Test fun `refresh waits 15 min after success and backs off after failures`() {
        assertEquals(RefreshPolicy.EVERY_MS, RefreshPolicy.nextDelayMs(0, 0.5))
        assertEquals(60_000L, RefreshPolicy.nextDelayMs(1, 0.5))
        assertEquals(240_000L, RefreshPolicy.nextDelayMs(3, 0.5))
        assertEquals(RefreshPolicy.MAX_BACKOFF_MS, RefreshPolicy.nextDelayMs(20, 0.5))
        assertTrue(RefreshPolicy.nextDelayMs(0, 0.0) < RefreshPolicy.nextDelayMs(0, 1.0))
    }
}
