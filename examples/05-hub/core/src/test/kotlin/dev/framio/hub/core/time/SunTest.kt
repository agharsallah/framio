package dev.framio.hub.core.time

import dev.framio.hub.core.AMSTERDAM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * Reference values from api.sunrise-sunset.org (fetched 2026-10-18). Its noon and twilight agree with NOAA to
 * the minute; its sunrise and sunset are about 2 min wider than other sources (it gives Amsterdam a 16 h 53 m
 * solstice day; sunrisesunset.io and Alpenglow give 16 h 47 m, earliest sunrise 05:18, latest sunset 22:07).
 * So sunrise and sunset get 150 s of tolerance and a day-length check against the 16 h 47 m figure.
 * The daylight arc is drawn at 0.25° per minute, so either way it's well under a pixel.
 */
class SunTest {
    private val riseSet = 150L

    private fun assertNear(expected: String, actual: Instant?, toleranceS: Long = 60) {
        val e = Instant.parse(expected)
        assertTrue("expected $e, got $actual", actual != null && abs(actual.epochSecond - e.epochSecond) <= toleranceS)
    }

    @Test fun `amsterdam at the summer solstice`() {
        val t = Sun.times(LocalDate.of(2026, 6, 21), 52.37, 4.89, AMSTERDAM)
        assertNear("2026-06-21T03:15:48Z", t.sunrise, riseSet)
        assertNear("2026-06-21T20:08:43Z", t.sunset, riseSet)
        val dayMinutes = (t.sunset!!.epochSecond - t.sunrise!!.epochSecond) / 60.0
        assertEquals(16 * 60 + 47.0, dayMinutes, 1.5)
        assertNear("2026-06-21T11:42:15Z", t.noon)
        assertNear("2026-06-21T02:28:17Z", t.dawn)
        assertNear("2026-06-21T20:56:14Z", t.dusk)
    }

    @Test fun `amsterdam today and at the winter solstice`() {
        val oct = Sun.times(LocalDate.of(2026, 10, 18), 52.37, 4.89, AMSTERDAM)
        assertNear("2026-10-18T06:09:27Z", oct.sunrise, riseSet)
        assertNear("2026-10-18T16:41:43Z", oct.sunset, riseSet)
        assertNear("2026-10-18T05:36:42Z", oct.dawn)
        assertNear("2026-10-18T17:14:28Z", oct.dusk)
        val dec = Sun.times(LocalDate.of(2026, 12, 21), 52.37, 4.89, AMSTERDAM)
        assertNear("2026-12-21T07:45:56Z", dec.sunrise, riseSet)
        assertNear("2026-12-21T15:31:03Z", dec.sunset, riseSet)
    }

    @Test fun `sydney's local day starts on the previous utc day`() {
        val t = Sun.times(LocalDate.of(2026, 3, 20), -33.87, 151.21, ZoneId.of("Australia/Sydney"))
        assertNear("2026-03-19T19:56:52Z", t.sunrise, riseSet)
        assertNear("2026-03-20T08:08:36Z", t.sunset, riseSet)
    }

    @Test fun `svalbard in june has no sunrise, the sun is up all day`() {
        val t = Sun.times(LocalDate.of(2026, 6, 21), 78.22, 15.65, ZoneId.of("Arctic/Longyearbyen"))
        assertNull(t.sunrise)
        assertNull(t.sunset)
        assertEquals(true, t.allDay)
    }

    @Test fun `elevation is about zero at sunrise, highest at noon and negative at midnight`() {
        val t = Sun.times(LocalDate.of(2026, 10, 18), 52.37, 4.89, AMSTERDAM)
        assertEquals(-0.833, Sun.elevation(t.sunrise!!, 52.37, 4.89), 0.3)
        // noon elevation = 90 - lat + declination(≈ -9.6° on 18 Oct)
        assertEquals(90 - 52.37 - 9.6, Sun.elevation(t.noon, 52.37, 4.89), 0.5)
        assertTrue(Sun.elevation(Instant.parse("2026-10-18T23:30:00Z"), 52.37, 4.89) < -30)
    }
}
