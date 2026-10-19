package dev.framio.meadow.worlds.colors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StudioTest {

    // ---- Color hunt

    @Test fun youngestLearnTheFourBasicColoursWithBalloons() {
        for (seed in 0 until 200) {
            val r = huntRound(0, Random(seed))
            assertEquals(2, r.options.size)
            assertTrue(r.options.all { it.hue in listOf(Hue.Red, Hue.Yellow, Hue.Blue, Hue.Green) && it.thing == "balloon" })
            assertTrue(r.promptLine.startsWith("colorhunt_find_"))
        }
    }

    @Test fun choicesGrowWithLevel() {
        assertEquals(listOf(2, 3, 3, 4, 4), (0..4).map { huntChoices(it) })
        assertEquals(listOf(4, 4, 7, 10, 10), (0..4).map { huntPool(it).size })
    }

    @Test fun everyRoundHasDistinctColoursAndTheTargetOnce() {
        for (level in 0..4) for (seed in 0 until 300) {
            val r = huntRound(level, Random(seed), previous = Hue.Red)
            assertEquals(r.options.size, r.options.map { it.hue }.toSet().size)
            assertEquals(1, r.options.count { it.hue == r.target.hue })
            assertNotEquals(Hue.Red, r.target.hue) // never the same colour twice in a row
            assertTrue(r.options.all { it.hue in huntPool(level) })
        }
    }

    @Test fun mixedThingsUseTheGenericPrompt() {
        val r = HuntRound(listOf(HuntItem(Hue.Blue, "car"), HuntItem(Hue.Red, "balloon")), HuntItem(Hue.Blue, "car"))
        assertEquals("colorhunt_one_blue", r.promptLine)
        assertEquals("co_car_blue", r.target.sprite)
        assertEquals("ob_balloon_red", r.options[1].sprite)
    }

    @Test fun newerColoursGetPractisedAtHigherLevels() {
        val targets = (0 until 600).map { huntRound(3, Random(it)).target.hue }
        assertTrue(targets.count { it in listOf(Hue.Brown, Hue.White, Hue.Black) } > 60)
    }

    // ---- Shape sorter

    @Test fun sorterSizesAndPools() {
        assertEquals(listOf(3, 4, 4, 5, 6), (0..4).map { sorterCount(it) })
        assertFalse(SortShape.Rect in sorterPool(1))
        assertTrue(SortShape.Diamond in sorterPool(2))
        assertFalse(SortShape.Hexagon in sorterPool(3))
        assertTrue(SortShape.Hexagon in sorterPool(4))
    }

    @Test fun holesMatchPiecesButNotInTheSameOrder() {
        for (level in 0..4) for (seed in 0 until 200) {
            val r = sorterRound(level, Random(seed))
            assertEquals(sorterCount(level), r.pieces.size)
            assertEquals(r.pieces.map { it.shape }.toSet(), r.holes.toSet())
            assertEquals(r.holes.size, r.holes.toSet().size)
            assertTrue(r.holes.indices.none { r.holes[it] == r.pieces[it].shape })
            assertTrue(r.pieces.all { it.color in SHAPE_COLORS && it.shape in sorterPool(level) })
        }
    }

    // ---- Mixing

    @Test fun primaryMixesAreRight() {
        assertEquals("orange", mixOf(Pot.Red, Pot.Yellow).name)
        assertEquals("green", mixOf(Pot.Blue, Pot.Yellow).name)
        assertEquals("purple", mixOf(Pot.Blue, Pot.Red).name)
        assertEquals("pink", mixOf(Pot.White, Pot.Red).name)
        assertEquals("grey", mixOf(Pot.Black, Pot.White).name)
    }

    @Test fun everyPairMakesSomethingAndOrderDoesNotMatter() {
        val all = Pot.entries
        for (a in all) for (b in all) if (a != b) {
            val m = mixOf(a, b)
            assertEquals(m, mixOf(b, a))
            assertTrue(m.line.matches(Regex("mix_[a-z]+_[a-z]+")))
        }
        assertEquals(10, all.flatMap { a -> all.filter { it != a }.map { mixOf(a, it).key } }.toSet().size)
    }

    @Test fun blackOnlyAtTheTop() {
        assertFalse(Pot.Black in mixPots(3))
        assertTrue(Pot.Black in mixPots(4))
        // enough different mixes to reach the goal with the four pots
        val p = mixPots(0)
        assertTrue(p.size * (p.size - 1) / 2 >= MIX_GOAL)
    }

    @Test fun blendIsLinearPerChannel() {
        assertEquals(0xFF000000, blendArgb(0xFF000000, 0xFFFFFFFF, 0f))
        assertEquals(0xFFFFFFFF, blendArgb(0xFF000000, 0xFFFFFFFF, 1f))
        assertEquals(0xFF808080, blendArgb(0xFF000000, 0xFFFFFFFF, 0.5f))
        assertEquals(0xFF102030, blendArgb(0xFF102030, 0xFF102030, 0.3f))
    }

    // ---- Paint

    @Test fun rainbowStaysOpaqueAndMoves() {
        val a = rainbowArgb(0f)
        val b = rainbowArgb(200f)
        assertNotEquals(a, b)
        for (d in 0 until 5000 step 37) assertEquals(0xFFL, (rainbowArgb(d.toFloat()) shr 24) and 0xFF)
    }

    @Test fun drawingNamesSortByTime() {
        val n = drawingName(0L)
        assertTrue(n.matches(Regex("\\d{8}-\\d{6}\\.png")))
        assertTrue(drawingName(1_000_000_000_000L) < drawingName(1_000_000_100_000L))
    }

    @Test fun palettesHaveTenColours() {
        assertEquals(10, PAINT_COLORS.size)
        assertEquals(10, COLORING_COLORS.size)
        assertEquals(10, PAINT_COLORS.map { it.second }.toSet().size)
    }
}
