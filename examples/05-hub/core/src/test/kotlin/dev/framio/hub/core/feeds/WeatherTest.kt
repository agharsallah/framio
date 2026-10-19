package dev.framio.hub.core.feeds

import dev.framio.hub.core.model.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WeatherTest {
    private fun resource(name: String) = javaClass.getResource("/openmeteo/$name")!!.readText()

    /** Recorded 2026-10-18 ~01:00 Amsterdam: dry night, rain from early morning. */
    private val amsterdam = OpenMeteo.parse(resource("amsterdam.json"), fetchedWallMs = 1_792_278_000_000)

    @Test fun `the url asks for unix times and rounds the location to about a kilometre`() {
        val url = OpenMeteo.url(Location(52.37226, 4.89236))
        assertTrue(url.contains("latitude=52.37&longitude=4.89"))
        assertTrue(url.contains("timeformat=unixtime"))
        assertTrue(url.contains("minutely_15=precipitation"))
    }

    @Test fun `a recorded response parses into every section`() {
        val w = amsterdam
        val current = w.current!!
        assertEquals(10.5, current.temperature, 0.0)
        assertEquals(false, current.isDay)
        assertEquals(48, w.hourly.size)
        assertEquals(192, w.quarters.size)
        assertEquals(2, w.days.size)
        assertEquals(12.1, w.days[0].max!!, 0.0)
        assertEquals(Instant.parse("2026-10-18T06:10:50Z").toEpochMilli(), w.days[0].sunriseMs)
    }

    @Test fun `missing sections and null values don't fail`() {
        val w = OpenMeteo.parse("""{"hourly":{"time":[1792278000,1792281600],"temperature_2m":[10.0,null]}}""", 0)
        assertNull(w.current)
        assertEquals(2, w.hourly.size)
        assertNull(w.hourly[1].temperature)
        assertTrue(w.quarters.isEmpty())
        assertNull(Nowcast.line(OpenMeteo.parse("{}", 0), 0))
    }

    @Test fun `the recorded night is dry until rain in the morning`() {
        val line = Nowcast.line(amsterdam, 1_792_278_000_000) as WeatherLine.RainFrom
        val at = Instant.ofEpochMilli(line.atMs)
        // the first wet quarter in the recording is before the 2.6 mm hour at 06:00 local (04:00 UTC)
        assertTrue("rain from $at", at >= Instant.parse("2026-10-18T02:00:00Z") && at <= Instant.parse("2026-10-18T04:00:00Z"))
        assertEquals(false, line.soon)
    }

    private fun weather(quarters: List<Double>, start: Long = 0, hourly: List<Weather.Hour> = emptyList()) =
        Weather(0, null, hourly, quarters.mapIndexed { i, mm -> Weather.Quarter(start + i * Nowcast.QUARTER_MS, mm) }, emptyList())

    @Test fun `raining now says until when`() {
        val w = weather(listOf(0.3, 0.2, 0.1, 0.0, 0.0, 0.0, 0.0, 0.0))
        assertEquals(WeatherLine.RainUntil(3 * Nowcast.QUARTER_MS, WeatherKind.Rain), Nowcast.line(w, 60_000))
    }

    @Test fun `rain within two hours is soon`() {
        val w = weather(listOf(0.0, 0.0, 0.0, 0.4, 0.4, 0.0) + List(10) { 0.0 })
        assertEquals(WeatherLine.RainFrom(3 * Nowcast.QUARTER_MS, WeatherKind.Rain, soon = true), Nowcast.line(w, 0))
    }

    @Test fun `no rain in the horizon is dry`() {
        assertEquals(WeatherLine.Dry(12), Nowcast.line(weather(List(48) { 0.0 }), 0))
    }

    @Test fun `hourly data beyond the quarters fills the ring, merged into windows`() {
        val hours = (0 until 6).map { h -> Weather.Hour(h * 3_600_000L, 10.0, if (h in 3..4) 1.0 else 0.0, 10, 61, 90) }
        val w = weather(List(8) { 0.0 }, hourly = hours) // quarters cover the first 2 hours
        val slots = Nowcast.slots(w, 0, 6 * 3_600_000L)
        assertEquals(listOf(RainSlot(3 * 3_600_000L, 5 * 3_600_000L, 1.0, false)), slots)
    }

    @Test fun `wmo codes map to kinds`() {
        assertEquals(WeatherKind.Clear, WeatherKind.of(0))
        assertEquals(WeatherKind.Showers, WeatherKind.of(81))
        assertEquals(WeatherKind.Snow, WeatherKind.of(86))
        assertEquals(WeatherKind.Thunder, WeatherKind.of(95))
        assertEquals(WeatherKind.Cloudy, WeatherKind.of(null))
    }

    @Test fun `frost shows from mid-afternoon when the night goes below zero`() {
        val hours = (0 until 12).map { h -> Weather.Hour(h * 3_600_000L, 3.0 - h, 0.0, 0, 0, 0) }
        val w = Weather(0, null, hours, emptyList(), emptyList())
        assertNull(Nowcast.frost(w, 0, localHour = 10))
        assertEquals(WeatherLine.Frost(-8.0), Nowcast.frost(w, 0, localHour = 16))
    }
}
