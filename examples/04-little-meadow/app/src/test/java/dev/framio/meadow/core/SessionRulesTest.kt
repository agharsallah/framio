package dev.framio.meadow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class SessionRulesTest {

    /** A plausible wall-clock time today at [h]:[m]. */
    private fun at(h: Int, m: Int = 0): Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 18, h, m, 0)
    }.timeInMillis

    private val s = Settings(dailyLimitMin = 45, breakEveryMin = 15, bedtime = "19:30", wakeUp = "06:30", bedtimeMode = true)

    @Test fun openDuringTheDay() = assertEquals(Gate.Open, SessionRules.gate(s, at(10), 0, 0, 0, false))

    @Test fun lockedWinsOverEverything() = assertEquals(Gate.Locked, SessionRules.gate(s, at(21), 99_999, 99_999, 0, true))

    @Test fun dailyLimitAndBonus() {
        assertEquals(Gate.DailyLimit, SessionRules.gate(s, at(10), 45 * 60, 0, 0, false))
        assertEquals(Gate.Open, SessionRules.gate(s, at(10), 45 * 60, 0, 15 * 60, false))
        assertEquals(Gate.Open, SessionRules.gate(s.copy(dailyLimitMin = 0), at(10), 999_999, 0, 0, false))
    }

    @Test fun wiggleBreakAfterTheSetMinutes() {
        assertEquals(Gate.Open, SessionRules.gate(s, at(10), 0, 15 * 60 - 1, 0, false))
        assertEquals(Gate.Break, SessionRules.gate(s, at(10), 0, 15 * 60, 0, false))
        assertEquals(Gate.Open, SessionRules.gate(s.copy(breakEveryMin = 0), at(10), 0, 99_999, 0, false))
    }

    @Test fun bedtimeOnlyWithAPlausibleClock() {
        assertEquals(Gate.Night, SessionRules.gate(s, at(20), 0, 0, 0, false))
        assertEquals(Gate.Open, SessionRules.gate(s.copy(bedtimeMode = false), at(20), 0, 0, 0, false))
        assertEquals(Gate.Open, SessionRules.gate(s, 3_600_000L, 0, 0, 0, false)) // 1970: the boot clock
    }

    @Test fun minutesLeft() {
        assertEquals(30, SessionRules.minutesLeft(s, 15 * 60, 0))
        assertEquals(0, SessionRules.minutesLeft(s, 60 * 60, 0))
        assertNull(SessionRules.minutesLeft(s.copy(dailyLimitMin = 0), 0, 0))
    }
}
