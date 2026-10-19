package dev.framio.meadow.worlds.words

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

class TraceGeometryTest {

    private val box = 520f

    /** Follows every stroke with points [step] px apart, nudged sideways by [wobble] px. */
    private fun traceAll(t: TraceTracker, wobble: Float = 0f, step: Float = 12f) {
        var guard = 0
        while (!t.done && guard++ < 50) {
            val st = t.strokes[t.stroke]
            var at = 0f
            val stroke = t.stroke
            while (at <= st.length + step && t.stroke == stroke) {
                val p = st.at(at)
                val d = st.direction(at)
                val side = if ((at / step).toInt() % 2 == 0) wobble else -wobble
                t.feed(p.x - d.y * side, p.y + d.x * side)
                at += step
            }
        }
    }

    @Test fun everyGlyphStaysInsideItsBox() {
        for (g in Glyphs.shapes + Glyphs.letters.values) for (st in g.strokes) for (p in st) {
            assertTrue("${g.id} $p", p.x in 0.02f..0.98f && p.y in 0.02f..0.98f)
        }
    }

    @Test fun allTwentySixLettersAreDefined() {
        assertEquals(26, Glyphs.letters.size)
        assertEquals(26, (Glyphs.tier1 + Glyphs.tier2 + Glyphs.tier3).toSet().size)
    }

    @Test fun followingTheLineFinishesEveryGlyph() {
        for (g in Glyphs.shapes + Glyphs.letters.values) {
            val t = TraceTracker(g, box)
            traceAll(t)
            assertTrue("${g.id} not finished", t.done)
        }
    }

    @Test fun wobblingWithinToleranceIsFine() {
        for (g in Glyphs.shapes + Glyphs.letters.values) {
            val t = TraceTracker(g, box, tolerance = 45f)
            traceAll(t, wobble = 32f)
            assertTrue("${g.id} wobbly not finished", t.done)
        }
    }

    @Test fun farFromTheLineDoesNothing() {
        val t = TraceTracker(Glyphs.letter('L'), box)
        val st = t.strokes[0]
        var at = 0f
        while (at < st.length) {
            val p = st.at(at)
            t.feed(p.x + 120f, p.y) // a parallel line well off the track
            at += 10f
        }
        assertEquals(0, t.stroke)
        assertEquals(0f, t.progress, 0.01f)
    }

    @Test fun touchingTheEndFirstDoesNotSkipTheStroke() {
        val t = TraceTracker(Glyphs.shapes.first { it.id == "down" }, box)
        val end = t.strokes[0].at(t.strokes[0].length)
        assertFalse(t.feed(end.x, end.y))
        assertEquals(0f, t.progress, 0.01f)
    }

    @Test fun aCircleIsNotDoneWhereItStarts() {
        val t = TraceTracker(Glyphs.shapes.first { it.id == "circle" }, box)
        val start = t.strokes[0].at(0f)
        repeat(5) { t.feed(start.x + it, start.y) }
        assertFalse(t.done)
        assertTrue(t.fraction < 0.2f)
    }

    @Test fun progressOnlyMovesForward() {
        val t = TraceTracker(Glyphs.letter('C'), box)
        val st = t.strokes[0]
        for (at in listOf(0f, 40f, 80f, 120f)) st.at(at).let { t.feed(it.x, it.y) }
        val reached = t.progress
        st.at(20f).let { t.feed(it.x, it.y) }
        assertEquals(reached, t.progress, 0.01f)
    }

    @Test fun strokesGoInOrder() {
        val t = TraceTracker(Glyphs.letter('T'), box)
        // tracing the stem first does nothing: the top bar comes first
        val stem = t.strokes[1]
        var at = 0f
        while (at < stem.length) { stem.at(at).let { t.feed(it.x, it.y) }; at += 10f }
        assertEquals(0, t.stroke)
    }

    @Test fun resamplingKeepsTheLength() {
        val p = StrokePath(listOf(Pt(0f, 0f), Pt(300f, 0f), Pt(300f, 400f)), 4f)
        assertEquals(700f, p.length, 0.5f)
        val mid = p.at(500f)
        assertEquals(300f, mid.x, 0.5f)
        assertEquals(200f, mid.y, 0.5f)
        for (i in 1 until p.s.size) assertTrue(hypot(p.xs[i] - p.xs[i - 1], p.ys[i] - p.ys[i - 1]) <= 4.01f)
    }

    @Test fun roundsFollowTheLevel() {
        val r = Random(1)
        assertEquals(listOf("down", "across", "circle", "zigzag", "wave"), Glyphs.round(1, 5, r).map { it.id })
        val two = Glyphs.round(2, 5, r)
        assertTrue(two.all { it.isLetter && it.id[0] in Glyphs.tier1 })
        assertEquals(5, two.map { it.id }.toSet().size)
        val three = Glyphs.round(3, 5, r)
        assertEquals(3, three.count { it.id[0] in Glyphs.tier2 })
        val four = Glyphs.round(4, 5, r)
        assertEquals(2, four.count { it.id[0] in Glyphs.tier3 })
        assertEquals(5, four.map { it.id }.toSet().size)
    }

    @Test fun liftingAlittleEarlyStillFinishesTheStroke() {
        val t = TraceTracker(Glyphs.shapes.first { it.id == "down" }, box)
        val st = t.strokes[0]
        var at = 0f
        while (at < st.length - 30f) { st.at(at).let { t.feed(it.x, it.y) }; at += 10f }
        assertTrue(t.done)
    }
}
