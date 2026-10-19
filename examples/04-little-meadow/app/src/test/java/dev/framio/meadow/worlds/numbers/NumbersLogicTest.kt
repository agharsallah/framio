package dev.framio.meadow.worlds.numbers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.random.Random

class NumbersLogicTest {

    @Test fun `how many starts at one to three and reaches ten`() {
        assertEquals(1..3, howManyRange(0))
        assertEquals(10, howManyRange(4).last)
        for (l in 0..4) assertTrue(howManyRange(l).first >= 1)
    }

    @Test fun `answer cards are three distinct numbers including the answer`() {
        val r = Random(1)
        for (level in 0..4) repeat(200) {
            val round = howManyRound(level, r)
            assertEquals(3, round.choices.size)
            assertEquals(3, round.choices.toSet().size)
            assertTrue(round.n in round.choices)
            assertTrue(round.choices.all { it in 1..10 })
        }
    }

    @Test fun `level zero always offers one two and three`() {
        val r = Random(2)
        repeat(100) { assertEquals(setOf(1, 2, 3), howManyRound(0, r).choices.toSet()) }
    }

    @Test fun `dots until the top level, numerals only at the top`() {
        assertTrue(howManyDots(0))
        assertTrue(howManyDots(3))
        assertFalse(howManyDots(4))
    }

    @Test fun `the scene changes from one round to the next`() {
        val r = Random(3)
        var prev: HowManyScene? = null
        repeat(100) {
            val s = howManyRound(2, r, prev).scene
            assertNotEquals(prev, s)
            prev = s
        }
    }

    @Test fun `dot patterns have exactly n dots inside the square`() {
        for (n in 1..10) {
            val dots = dotPattern(n)
            assertEquals(n, dots.size)
            assertEquals(n, dots.toSet().size)
            assertTrue(dots.all { (x, y) -> x in 0f..1f && y in 0f..1f })
        }
    }

    @Test fun `scene spots are inside the picture and do not overlap`() {
        for (scene in HowManyScene.entries) for (n in 1..10) for (seed in 0..20) {
            val spots = sceneSpots(scene, n, seed)
            assertEquals(n, spots.size)
            assertTrue(spots.all { (x, y) -> x in 0.05f..0.95f && y in 0.1f..0.95f })
            // the picture is about 960×392 dp and things are at most 150 dp (104 dp above five)
            val item = if (n <= 5) 110f else 100f
            for (i in spots.indices) for (j in i + 1 until spots.size) {
                val d = hypot((spots[i].first - spots[j].first) * 960f, (spots[i].second - spots[j].second) * 392f)
                assertTrue("$scene n=$n seed=$seed", d >= item)
            }
        }
    }

    @Test fun `feed status counts under, exact and too many`() {
        assertEquals(FeedStatus.Under, feedStatus(2, 3))
        assertEquals(FeedStatus.Exact, feedStatus(3, 3))
        assertEquals(FeedStatus.TooMany, feedStatus(4, 3))
    }

    @Test fun `Pip never asks for more berries than the bush has`() {
        for (l in 0..4) assertTrue(feedRange(l).last <= BUSH_BERRIES && feedRange(l).first >= 1)
        assertEquals(1..3, feedRange(0))
        assertEquals(BUSH_BERRIES, bushSpots().size)
        assertEquals(BUSH_BERRIES, (0 until BUSH_BERRIES).map(::nestSpot).toSet().size)
    }

    @Test fun `more rounds differ by at least the level's gap and ask fewer only from level three`() {
        val r = Random(4)
        for (level in 0..4) repeat(300) {
            val m = moreRound(level, r)
            assertTrue(m.left in 1..10 && m.right in 1..10)
            val gap = kotlin.math.abs(m.left - m.right)
            assertTrue("level $level gap $gap", gap >= moreMinGap(level))
            if (level < 3) assertTrue(m.askMore)
            val answerCount = if (m.answer == 0) m.left else m.right
            val other = if (m.answer == 0) m.right else m.left
            if (m.askMore) assertTrue(answerCount > other) else assertTrue(answerCount < other)
        }
    }

    @Test fun `top levels sometimes ask for fewer`() {
        val r = Random(5)
        assertTrue((0 until 100).any { !moreRound(4, r).askMore })
    }

    @Test fun `a basket holds up to ten in two rows that do not overlap sideways`() {
        for (n in 1..10) {
            val spots = pileSpots(n)
            assertEquals(n, spots.size)
            assertTrue(spots.map { it.second }.distinct().size <= 2)
            val px = pileItemSize(n <= 6) / 380f // the basket picture is 380 dp wide
            for (row in spots.groupBy { it.second }.values) {
                val xs = row.map { it.first }.sorted()
                for (i in 1 until xs.size) assertTrue(xs[i] - xs[i - 1] >= px * 0.95f)
                assertTrue(xs.first() - px / 2 >= 0.05f && xs.last() + px / 2 <= 0.95f)
            }
        }
    }
}
