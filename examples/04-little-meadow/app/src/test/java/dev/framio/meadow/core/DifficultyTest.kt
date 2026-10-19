package dev.framio.meadow.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DifficultyTest {

    private fun run(start: Difficulty.Track, age: Int, vararg firstTry: Boolean) =
        firstTry.fold(start) { t, ok -> Difficulty.after(t, ok, age) }

    @Test fun ageSetsTheStartingLevel() {
        assertEquals(0, Difficulty.start(2))
        assertEquals(1, Difficulty.start(3))
        assertEquals(3, Difficulty.start(5))
    }

    @Test fun threeFirstTriesInARowStepUp() {
        assertEquals(1, run(Difficulty.Track(1), 3, true, true).level)
        assertEquals(2, run(Difficulty.Track(1), 3, true, true, true).level)
    }

    @Test fun aMissResetsTheStreak() {
        assertEquals(1, run(Difficulty.Track(1), 3, true, true, false, true).level)
    }

    @Test fun twoHelpedRoundsInARowStepDownButNotBelowTheAgeFloor() {
        assertEquals(1, run(Difficulty.Track(2), 3, false, false).level)
        assertEquals(0, run(Difficulty.Track(1), 3, false, false).level)  // age 3: floor is level 0
        assertEquals(2, run(Difficulty.Track(2), 5, false, false).level)  // age 5: floor is level 2
    }

    @Test fun neverAboveTheTop() {
        val t = run(Difficulty.Track(Difficulty.MAX), 5, true, true, true, true)
        assertEquals(Difficulty.MAX, t.level)
    }
}
