package dev.framio.pomodoro.core.time

import dev.framio.pomodoro.core.AMSTERDAM
import dev.framio.pomodoro.core.FakeClock
import dev.framio.pomodoro.core.wallAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class EveningTest {
    private val eight = LocalTime.of(20, 0)
    private val six = LocalTime.of(6, 0)

    private fun covers(hhmm: String, start: LocalTime? = eight, end: LocalTime = six) = Evening.covers(LocalTime.parse(hhmm), start, end)

    @Test
    fun `a window across midnight - from the start, until the morning`() {
        assertFalse(covers("19:59"))
        assertTrue(covers("20:00"))
        assertTrue(covers("23:59"))
        assertTrue(covers("00:00"))
        assertTrue(covers("05:59"))
        assertFalse(covers("06:00"))
        assertFalse(covers("12:00"))
    }

    @Test
    fun `a window within one day, an empty one, and off`() {
        assertTrue(covers("02:00", LocalTime.of(1, 0), LocalTime.of(5, 0)))
        assertFalse(covers("05:00", LocalTime.of(1, 0), LocalTime.of(5, 0)))
        assertFalse(covers("00:30", LocalTime.of(1, 0), LocalTime.of(5, 0)))
        assertFalse(covers("20:00", eight, eight))
        assertFalse(covers("21:00", null))
    }

    @Test
    fun `only a trusted clock turns the evening on`() {
        val clock = FakeClock(wallMs = null)
        assertFalse(Evening.at(clock.now(), eight, six)) // the boot clock says 01:00: no amber
        assertNull(Evening.nextChange(clock.now(), eight, six))
        clock.wallMs = wallAt("2026-10-18T21:00", AMSTERDAM)
        assertTrue(Evening.at(clock.now(), eight, six))
        clock.wallMs = wallAt("2026-10-18T07:00", AMSTERDAM)
        assertFalse(Evening.at(clock.now(), eight, six))
    }

    @Test
    fun `the next change is the nearer of start and end`() {
        val clock = FakeClock(wallMs = wallAt("2026-10-18T19:30", AMSTERDAM))
        assertEquals(30.minutes, Evening.nextChange(clock.now(), eight, six))
        clock.wallMs = wallAt("2026-10-18T22:00", AMSTERDAM)
        assertEquals(8.hours, Evening.nextChange(clock.now(), eight, six))
        clock.wallMs = wallAt("2026-10-18T20:00", AMSTERDAM) // exactly at the start: the next one is the end
        assertEquals(10.hours, Evening.nextChange(clock.now(), eight, six))
        assertNull(Evening.nextChange(clock.now(), null, six))
    }

    @Test
    fun `across the autumn DST change the wait is in real hours`() {
        // 2026-10-25 03:00 CEST → 02:00 CET: the night from the 24th is 11 real hours long between 20:00 and 06:00
        val clock = FakeClock(wallMs = wallAt("2026-10-24T20:00", AMSTERDAM))
        assertEquals(11.hours, Evening.nextChange(clock.now(), eight, six))
    }
}
