package dev.framio.meadow.worlds.quiet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class QuietMathTest {

    @Test fun sineEasingStartsAndEndsAtRest() {
        assertEquals(0f, sineInOut(0f), 1e-6f)
        assertEquals(1f, sineInOut(1f), 1e-6f)
        assertEquals(0.5f, sineInOut(0.5f), 1e-6f)
        var prev = 0f
        for (i in 1..100) {
            val v = sineInOut(i / 100f)
            assertTrue(v >= prev)
            prev = v
        }
        // flat at both ends: the first and last 1 % move far less than 1 %
        assertTrue(sineInOut(0.01f) < 0.001f)
        assertTrue(1f - sineInOut(0.99f) < 0.001f)
    }

    @Test fun aBreathIsAboutFourInFiveOut() {
        for (level in 0..4) {
            val t = breathTiming(level)
            assertTrue(t.inMs in 3000..4500)
            assertTrue(t.outMs in 4000..5500)
            assertTrue(t.outMs > t.inMs) // breathing out is always the longer, calming half
        }
    }

    @Test fun breathLevelNeverJumps() {
        for (level in 0..4) {
            val t = breathTiming(level)
            assertEquals(0f, breathLevel(0, t), 1e-6f)
            assertEquals(1f, breathLevel(t.inMs.toLong(), t), 1e-3f)
            assertEquals(0f, breathLevel(t.totalMs.toLong() - 1, t), 1e-6f)
            var prev = breathLevel(0, t)
            for (ms in 10L until t.totalMs step 10) {
                val v = breathLevel(ms, t)
                assertTrue("jump at $ms ms", abs(v - prev) < 0.01f)
                prev = v
            }
        }
    }

    @Test fun voiceGuidesFirstThenFadesToInOut() {
        assertEquals("breathe_hand_in" to "breathe_hand_out", breathLines("hand", 0))
        assertEquals("breathe_hand_in" to "breathe_hand_out", breathLines("hand", 1))
        for (i in 2 until BREATHS) assertEquals("breathe_in" to "breathe_out", breathLines("hand", i))
        assertEquals(5, BREATHS) // one per finger of the starfish hand
    }

    @Test fun nightFadeGoesSlowlyToAlmostBlack() {
        val total = 20 * 60_000L
        assertEquals(0f, nightAlpha(0, total), 1e-6f)
        assertEquals(NIGHT_DARK, nightAlpha(total, total), 1e-6f)
        assertEquals(NIGHT_DARK, nightAlpha(total * 3, total), 1e-6f)
        var prev = 0f
        for (s in 1..1200) {
            val a = nightAlpha(s * 1000L, total)
            assertTrue(a >= prev)
            assertTrue("a second's step is invisible", a - prev < 0.003f)
            prev = a
        }
        // after a wake-up it starts from where it is told
        assertEquals(0.82f, nightAlpha(0, 120_000, from = 0.82f), 1e-6f)
        assertEquals(NIGHT_DARK, nightAlpha(0, 0), 1e-6f)
    }

    @Test fun musicFollowsTheDark() {
        assertEquals(1f, nightMusic(0f), 1e-6f)
        assertEquals(0.2f, nightMusic(NIGHT_DARK), 1e-5f)
        assertTrue(nightMusic(0.5f) in 0.2f..1f)
    }

    @Test fun starsAreSpreadOutAndAvoidTheMoon() {
        val stars = skyStars(30, seed = 7)
        assertEquals(30, stars.size)
        assertEquals(stars, skyStars(30, seed = 7))
        for (s in stars) {
            assertTrue(s.x in 0f..1f && s.y in 0f..0.7f)
            assertTrue(s.periodS >= 6f) // slow twinkles only
            val dx = (s.x - 0.83f) * 1.6f
            val dy = s.y - 0.2f
            assertTrue(kotlin.math.sqrt(dx * dx + dy * dy) / 1.6f >= 0.12f)
        }
        for (i in stars.indices) for (j in i + 1 until stars.size) {
            val dx = (stars[i].x - stars[j].x) * 1.6f
            val dy = stars[i].y - stars[j].y
            assertTrue(kotlin.math.sqrt(dx * dx + dy * dy) / 1.6f >= 0.07f)
        }
    }

    @Test fun aTapFindsTheNearestStar() {
        val stars = skyStars(30, seed = 7)
        val s = stars[4]
        assertEquals(4, nearestStar(stars, s.x + 0.005f, s.y))
        assertEquals(-1, nearestStar(listOf(SkyStar(0.1f, 0.1f, 2f, 6f, 0f, 0f)), 0.9f, 0.9f))
    }
}
