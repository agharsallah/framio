package dev.framio.meadow.worlds.puzzles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PuzzleLogicTest {

    @Test fun memoryDealsEveryThingTwice() {
        for (level in 0..4) repeat(20) { seed ->
            val cards = memoryDeal(level, Random(seed))
            val (c, r) = memoryGrid(level)
            assertEquals(c * r, cards.size)
            assertTrue(cards.groupingBy { it }.eachCount().values.all { it == 2 })
        }
    }

    @Test fun memoryGrowsFrom4To12Cards() {
        assertEquals(listOf(4, 6, 8, 12, 12), (0..4).map { memoryGrid(it).let { (c, r) -> c * r } })
    }

    @Test fun memoryCardsStayBigEnough() {
        for (level in 0..4) {
            val (c, r) = memoryGrid(level)
            val size = memoryCardSize(c, r, 1280f - 254f, 688f)
            assertTrue("level $level: $size", size >= 150f)
            assertTrue(c * size * 1.13f <= 1280f - 254f && r * size * 1.13f <= 688f + size * 0.13f)
        }
    }

    @Test fun jigsawPieceCountsFollowTheLevel() {
        assertEquals(listOf(2, 4, 6, 9, 9), (0..4).map { jigsawGrid(it).let { (c, r) -> c * r } })
    }

    @Test fun neighbouringEdgesFitAndTheBorderIsFlat() {
        repeat(30) { seed ->
            val (cols, rows) = 3 to 3
            val e = jigsawEdges(cols, rows, Random(seed))
            for (r in 0 until rows) for (c in 0 until cols) {
                val p = e[r * cols + c]
                if (c == 0) assertEquals(0, p.left)
                if (r == 0) assertEquals(0, p.top)
                if (c == cols - 1) assertEquals(0, p.right) else assertEquals(-p.right, e[r * cols + c + 1].left)
                if (r == rows - 1) assertEquals(0, p.bottom) else assertEquals(-p.bottom, e[(r + 1) * cols + c].top)
                if (c in 1 until cols - 1) assertNotEquals(0, p.left)
            }
        }
    }

    @Test fun outlineIsClosedAndStaysInsideItsBox() {
        val w = 200f
        val h = 140f
        val m = pieceMargin(w, h)
        for (bumps in listOf(PieceEdges(1, 1, 1, 1), PieceEdges(-1, -1, -1, -1), PieceEdges(0, 1, -1, 0))) {
            val segs = pieceOutline(bumps, w, h)
            val start = segs.first() as Seg.Move
            val end = segs.last() as Seg.Line
            assertEquals(start.x, end.x, 1e-3f)
            assertEquals(start.y, end.y, 1e-3f)
            val pts = segs.flatMap {
                when (it) {
                    is Seg.Move -> listOf(it.x to it.y)
                    is Seg.Line -> listOf(it.x to it.y)
                    is Seg.Cubic -> listOf(it.x1 to it.y1, it.x2 to it.y2, it.x to it.y)
                }
            }
            assertTrue(pts.all { (x, y) -> x >= -1e-3f && y >= -1e-3f && x <= w + 2 * m + 1e-3f && y <= h + 2 * m + 1e-3f })
        }
        // a flat-edged piece has exactly four lines plus its start
        assertEquals(5, pieceOutline(PieceEdges(0, 0, 0, 0), w, h).size)
    }

    @Test fun jigsawSpotsAreOffTheBoardAndClearOfTheFriend() {
        for (level in 0..4) {
            val (c, r) = jigsawGrid(level)
            val l = jigsawLayout(c, r)
            assertEquals(c * r, l.spots.size)
            val halfW = (l.cellW / 2 + l.margin) * l.looseScale
            val halfH = (l.cellH / 2 + l.margin) * l.looseScale
            for (p in l.spots) {
                assertEquals("spot $p on the board", -1, l.slotAt(p))
                // the friend's corner: x < 230, y > 688 - 230
                val inFriend = p.x - halfW < 230f && p.y + halfH > 688f - 230f
                assertTrue("spot $p over the friend", !inFriend)
                assertTrue(p.x - halfW >= 0f && p.x + halfW <= 1280f && p.y - halfH >= 0f && p.y + halfH <= 688f)
            }
        }
    }

    @Test fun snappingOnlyNearTheRightSlot() {
        val l = jigsawLayout(3, 3)
        for (i in 0 until 9) {
            val c = l.slotCenter(i)
            assertEquals(i, l.slotAt(c))
            assertTrue(l.snaps(i, Pt(c.x + 20f, c.y - 15f)))
            assertTrue(!l.snaps(i, Pt(c.x + l.cellW, c.y)))
        }
    }

    @Test fun shadowRoundsHaveTheRightSizeAndNoRepeats() {
        for (level in 0..4) repeat(30) { seed ->
            val t = shadowRound(level, Random(seed))
            assertEquals(shadowCount(level), t.size)
            assertEquals(t.size, t.toSet().size)
            val groups = t.map { thing -> SHADOW_GROUPS.indexOfFirst { thing in it } }
            if (level <= 1) assertEquals("all different outlines", t.size, groups.toSet().size)
            else assertTrue("look-alikes at level $level", groups.toSet().size < t.size)
        }
    }

    @Test fun trayNeverMatchesTheShadowOrder() {
        repeat(50) { seed ->
            val o = shadowTrayOrder(4, Random(seed))
            assertEquals((0 until 4).toSet(), o.toSet())
            assertTrue(o.indices.none { o[it] == it })
        }
    }

    @Test fun patternsFollowTheirRuleAndFitTheTrack() {
        for (level in 0..4) repeat(40) { seed ->
            val r = patternRound(level, Random(seed))
            val unit = r.kind.unit
            assertTrue(r.cars.size in 5..7)
            assertTrue(r.cars.size > unit.length)
            // the sequence is the unit repeated: same letter ⇔ same thing
            for (i in r.cars.indices) for (j in r.cars.indices) {
                assertEquals(unit[i % unit.length] == unit[j % unit.length], r.cars[i] == r.cars[j])
            }
            assertEquals(r.cars.last(), r.answer)
            assertEquals(3, r.choices.size)
            assertEquals(3, r.choices.toSet().size)
            assertTrue(r.answer in r.choices)
        }
        assertEquals(listOf("AB", "AAB", "ABC", "ABB", "AABB"), (0..4).map { patternKind(it).unit })
    }

    @Test fun shapeQuizHasTheTargetAndLookAlikesWhenHarder() {
        for (s in Solid.entries) repeat(10) { seed ->
            val easy = solidChoices(s, 0, Random(seed))
            val hard = solidChoices(s, 4, Random(seed))
            for (c in listOf(easy, hard)) {
                assertEquals(3, c.toSet().size)
                assertTrue(s in c)
            }
        }
        assertEquals(setOf(Solid.Cone, Solid.Pyramid, Solid.Cylinder), solidChoices(Solid.Cone, 3, Random(1)).toSet())
        assertEquals(Solid.entries.toSet(), solidOrder(Random(3)).toSet())
    }
}
