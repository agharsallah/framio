package dev.framio.pomodoro.core.time

import dev.framio.pomodoro.core.AMSTERDAM
import dev.framio.pomodoro.core.FakeClock
import dev.framio.pomodoro.core.wallAt
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class DayPartTest {
    private fun at(hhmm: String, evening: LocalTime? = null) = DayPart.at(LocalTime.parse(hhmm), evening)

    @Test
    fun `boundaries with the default evening at 20 30`() {
        assertEquals(DayPart.Night, at("04:59"))
        assertEquals(DayPart.Dawn, at("05:00"))
        assertEquals(DayPart.Dawn, at("07:59"))
        assertEquals(DayPart.Day, at("08:00"))
        assertEquals(DayPart.Day, at("17:29"))
        assertEquals(DayPart.Dusk, at("17:30"))
        assertEquals(DayPart.Dusk, at("20:29"))
        assertEquals(DayPart.Night, at("20:30"))
        assertEquals(DayPart.Night, at("00:00"))
    }

    @Test
    fun `night follows the evening setting, and an implausibly early one falls back to the default`() {
        assertEquals(DayPart.Dusk, at("21:30", LocalTime.of(22, 0)))
        assertEquals(DayPart.Night, at("22:00", LocalTime.of(22, 0)))
        assertEquals(DayPart.Day, at("18:00", LocalTime.of(22, 0)))
        assertEquals(DayPart.Day, at("12:00", LocalTime.of(9, 0))) // 09:00 "evening" would make the whole day dusk
    }

    @Test
    fun `an untrusted clock paints day, a trusted one its local time`() {
        val clock = FakeClock(wallMs = null)
        assertEquals(DayPart.Day, DayPart.of(clock.now()))
        clock.wallMs = wallAt("2026-10-18T19:00", AMSTERDAM)
        assertEquals(DayPart.Dusk, DayPart.of(clock.now()))
        clock.wallMs = wallAt("2026-10-18T06:30", AMSTERDAM)
        assertEquals(DayPart.Dawn, DayPart.of(clock.now()))
    }
}
