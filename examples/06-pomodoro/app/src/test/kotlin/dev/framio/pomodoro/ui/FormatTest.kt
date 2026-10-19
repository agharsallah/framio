package dev.framio.pomodoro.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class FormatTest {
    @Test
    fun `seconds round up so a block starts at its full length and ends at zero`() {
        assertEquals("25:00", Format.clock(25.minutes, seconds = true))
        assertEquals("25:00", Format.clock(25.minutes - 1.milliseconds, seconds = true))
        assertEquals("24:59", Format.clock(24.minutes + 59.seconds, seconds = true))
        assertEquals("0:01", Format.clock(1.milliseconds, seconds = true))
        assertEquals("0:00", Format.clock(Duration.ZERO, seconds = true))
        assertEquals("0:00", Format.clock((-3).seconds, seconds = true))
    }

    @Test
    fun `hours show when a block is that long`() {
        assertEquals("1:05:00", Format.clock(1.hours + 5.minutes, seconds = true))
    }

    @Test
    fun `without seconds it says whole minutes left, rounded up`() {
        assertEquals("38 min", Format.clock(37.minutes + 1.seconds, seconds = false))
        assertEquals(12, Format.minutesLeft(11.minutes + 1.seconds))
        assertEquals(1, Format.minutesLeft(1.seconds))
        assertEquals(0, Format.minutesLeft(Duration.ZERO))
    }
}
