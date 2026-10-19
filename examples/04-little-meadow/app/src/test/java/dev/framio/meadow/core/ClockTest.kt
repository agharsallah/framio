package dev.framio.meadow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockTest {

    @Test fun parsesHoursAndMinutes() {
        assertEquals(19 * 60 + 30, Clock.parseMinutes("19:30"))
        assertEquals(0, Clock.parseMinutes("0:00"))
        assertNull(Clock.parseMinutes("24:00"))
        assertNull(Clock.parseMinutes("7"))
        assertNull(Clock.parseMinutes("ab:cd"))
    }

    @Test fun nightWindowAcrossMidnight() {
        val bed = 19 * 60 + 30
        val wake = 6 * 60 + 30
        assertTrue(Clock.inNight(bed, bed, wake))       // starts at bedtime
        assertTrue(Clock.inNight(23 * 60, bed, wake))
        assertTrue(Clock.inNight(2 * 60, bed, wake))
        assertFalse(Clock.inNight(wake, bed, wake))     // ends at wake-up
        assertFalse(Clock.inNight(12 * 60, bed, wake))
    }

    @Test fun nightWindowWithinOneDayAndEmptyWindow() {
        assertTrue(Clock.inNight(14 * 60, 13 * 60, 15 * 60)) // a nap
        assertFalse(Clock.inNight(15 * 60, 13 * 60, 15 * 60)) // ends exactly at wake-up
        assertFalse(Clock.inNight(16 * 60, 13 * 60, 15 * 60))
        assertFalse(Clock.inNight(10, 600, 600))
    }

    @Test fun theUnsyncedBootClockIsNotTrusted() {
        assertFalse(Clock.plausible(0L))                   // the frame boots at 1970 / 1 January until NTP
        assertTrue(Clock.plausible(1_760_000_000_000L))    // October 2025
    }

    @Test fun partsOfTheDay() {
        assertEquals(PartOfDay.Morning, Clock.partOfDay(7 * 60))
        assertEquals(PartOfDay.Afternoon, Clock.partOfDay(15 * 60))
        assertEquals(PartOfDay.Evening, Clock.partOfDay(18 * 60))
        assertEquals(PartOfDay.Night, Clock.partOfDay(23 * 60))
    }
}
