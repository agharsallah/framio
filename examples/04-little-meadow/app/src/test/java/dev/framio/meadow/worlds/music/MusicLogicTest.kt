package dev.framio.meadow.worlds.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MusicLogicTest {

    private fun names(song: Song) = song.notes.joinToString(" ") { listOf("C", "D", "E", "F", "G", "A", "B", "C'")[it.bar] }

    @Test fun `the bars are a C major scale from C5 to C6`() {
        assertEquals(listOf(72, 74, 76, 77, 79, 81, 83, 84), XYLO_MIDI.toList())
    }

    @Test fun `songs have the right melodies`() {
        val byId = SONGS.associateBy { it.id }
        assertEquals("C C G G A A G F F E E D D C", names(byId.getValue("twinkle")))
        assertEquals("E D C D E E E D D D E G G E D C D E E E E D D E D C", names(byId.getValue("mary")))
        assertEquals("C C C D E E D E F G C' C' C' G G G E E E C C C G F E D C", names(byId.getValue("row")))
        assertEquals(
            "C D E C C D E C E F G E F G G A G F E C G A G F E C C' G C' C' G C'",
            names(byId.getValue("jacques")),
        )
        assertEquals("E D C E D C C C C C D D D D E D C", names(byId.getValue("buns")))
    }

    @Test fun `songs fill whole bars of their metre`() {
        // 4/4 songs: whole bars of 4 beats; Row, row (6/8 written in quarter beats): bars of 1.5
        for (song in SONGS) {
            val beats = song.notes.sumOf { it.beats.toDouble() }
            if (song.id == "row") assertEquals(0.0, beats % 1.5, 1e-6) else assertEquals("${song.id} $beats", 0.0, beats % 4.0, 1e-6)
        }
    }

    @Test fun `playback times follow the durations`() {
        val song = SONGS.first { it.id == "twinkle" }
        val t = noteTimes(song, 500)
        assertEquals(0L, t[0])
        assertEquals(500L, t[1])
        assertEquals(3000L, t[6]) // the half note G starts on beat 7
        assertEquals(4000L, t[7]) // and lasts two beats
    }

    @Test fun `bubble notes are pentatonic and bigger bubbles sing lower`() {
        val c = setOf(0, 2, 4, 7, 9)
        for (s in 0..10) assertTrue((bubbleNote(s / 10f) - 72).mod(12) in c)
        assertTrue(bubbleNote(1f) < bubbleNote(0f))
    }

    @Test fun `never more than about eight bubbles at once`() {
        for (level in 0..4) {
            var maxAlive = 0
            var t = 0f
            while (t < 120_000f) {
                val alive = liveRange(t, level, 688f).count { i -> val b = bubble(i, level); t >= b.bornMs && !b.gone(t, 688f) }
                maxAlive = maxOf(maxAlive, alive)
                t += 100f
            }
            assertTrue("level $level: $maxAlive", maxAlive <= 9)
        }
    }

    @Test fun `the live range never misses a visible bubble`() {
        val level = 2
        for (i in 0 until 200) {
            val b = bubble(i, level)
            var t = b.bornMs + 1f
            while (!b.gone(t, 688f)) {
                assertTrue(i in liveRange(t, level, 688f))
                t += 250f
            }
        }
    }

    @Test fun `bubbles start to the right of the friend and on screen`() {
        for (i in 0 until 300) {
            val b = bubble(i, 1)
            assertTrue(b.x in 0.2f..0.95f)
            assertTrue(b.radius in 50f..90f)
        }
    }

    @Test fun `echo patterns grow with the level`() {
        assertEquals(2, echoLength(0))
        assertEquals(5, echoLength(4))
        assertEquals(2, echoDrums(0))
        assertEquals(3, echoDrums(4))
    }

    @Test fun `patterns mix drums and never repeat one three times`() {
        val r = Random(7)
        for (level in 0..4) repeat(300) {
            val p = echoPattern(echoLength(level), echoDrums(level), r)
            assertEquals(echoLength(level), p.size)
            assertTrue(p.all { it in 0 until echoDrums(level) })
            assertTrue(p.distinct().size >= 2)
            for (i in 2 until p.size) assertTrue(!(p[i] == p[i - 1] && p[i] == p[i - 2]))
        }
    }

    @Test fun `only the order matters when echoing`() {
        val p = listOf(0, 1, 1)
        assertEquals(EchoStep.Next, echoCheck(p, 0, 0))
        assertEquals(EchoStep.Wrong, echoCheck(p, 0, 1))
        assertEquals(EchoStep.Next, echoCheck(p, 1, 1))
        assertEquals(EchoStep.Done, echoCheck(p, 2, 1))
    }
}
