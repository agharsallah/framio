package dev.framio.meadow.worlds.quiet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FeelingsTest {

    @Test fun levelsAddSubtlerFeelings() {
        assertEquals(setOf(Feeling.Happy, Feeling.Sad, Feeling.Angry, Feeling.Scared), feelingsFor(0).toSet())
        assertTrue(Feeling.Surprised in feelingsFor(2) && Feeling.Proud !in feelingsFor(2))
        assertEquals(Feeling.entries.toSet(), feelingsFor(4).toSet())
        for (l in 0..4) {
            assertTrue(exploreFeelings(l).size <= 6)
            assertTrue(exploreNeeded(l) <= exploreFeelings(l).size)
        }
    }

    @Test fun choicesHoldTheAnswerAndNothingToMixUp() {
        for (level in 0..4) for (target in feelingsFor(level)) for (seed in 0 until 40) {
            val c = feelingChoices(target, level, Random(seed))
            assertEquals(if (level == 0) 2 else 3, c.size)
            assertTrue(target in c)
            assertEquals(c.size, c.toSet().size)
            for (f in c) if (f != target) assertFalse("$f next to $target", confusable(f, target))
        }
    }

    @Test fun littleOnesOnlySeeFacesTheyKnowWhenPossible() {
        for (seed in 0 until 40) {
            val c = feelingChoices(Feeling.Happy, 0, Random(seed))
            assertTrue(c.all { it in feelingsFor(0) })
        }
    }

    @Test fun roundsVaryAndStayWithinTheLevel() {
        for (level in 0..4) for (seed in 0 until 40) {
            val rounds = pickScenarios(level, 4, Random(seed))
            assertEquals(4, rounds.size)
            assertEquals(4, rounds.map { it.id }.toSet().size)
            assertTrue(rounds.all { it.feeling in feelingsFor(level) })
            rounds.zipWithNext().forEach { (a, b) -> assertTrue(a.feeling != b.feeling) }
        }
    }

    @Test fun everyHardFeelingHasHelpers() {
        for (f in Feeling.entries) {
            if (f.positive) assertTrue(helpersFor(f).isEmpty())
            else assertEquals(3, helpersFor(f).size)
        }
        for (s in SCENARIOS) assertTrue(s.friend in FEELING_FRIENDS)
        // every feeling appears in at least one story
        assertEquals(Feeling.entries.toSet(), SCENARIOS.map { it.feeling }.toSet())
    }
}
