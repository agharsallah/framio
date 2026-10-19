package dev.framio.hub.core.feeds

import dev.framio.hub.core.AMSTERDAM
import dev.framio.hub.core.wallAt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class IcsTest {
    private val original = TimeZone.getDefault()

    // The JVM's default zone must not matter: only the Hub's zone does
    @Before fun otherDefaultZone() = TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))

    @After fun restore() = TimeZone.setDefault(original)

    private fun read(name: String, from: String = "2026-10-17T00:00", to: String = "2026-11-01T00:00") =
        Ics.parse(javaClass.getResource("/ics/$name")!!.readText(), "fam", wallAt(from), wallAt(to), AMSTERDAM)

    private fun List<AgendaEvent>.titled(title: String) = filter { it.title == title }
    private fun local(ms: Long) = Instant.ofEpochMilli(ms).atZone(AMSTERDAM).toLocalDateTime().toString()

    @Test fun `a weekly event skips its exdate and is replaced by its moved occurrence`() {
        val events = read("google.ics")
        assertEquals(listOf("2026-10-17T17:00"), events.titled("Swimming").map { local(it.startMs) })
        val moved = events.titled("Swimming (moved)").single()
        assertEquals("2026-10-31T18:00", local(moved.startMs))
        assertEquals("Zuiderbad", events.titled("Swimming").single().location)
    }

    @Test fun `a daily event stays at 09_00 local across the end of summer time`() {
        val starts = read("google.ics").titled("Breakfast together").map { local(it.startMs) }
        assertEquals(10, starts.size)
        assertTrue(starts.all { it.endsWith("T09:00") })
        assertTrue(starts.contains("2026-10-26T09:00"))
    }

    @Test fun `all-day events are whole local days, multi-day ones keep their length`() {
        val events = read("google.ics")
        val birthday = events.titled("Grandma's birthday").single()
        assertTrue(birthday.allDay)
        assertEquals(wallAt("2026-10-20T00:00"), birthday.startMs)
        assertEquals(wallAt("2026-10-21T00:00"), birthday.endMs)
        val trip = events.titled("Weekend in Ghent").single()
        assertEquals(wallAt("2026-10-23T00:00"), trip.startMs)
        assertEquals("ends at local midnight, though the DST night is 25 h", wallAt("2026-10-26T00:00"), trip.endMs)
    }

    @Test fun `utc times, midnight events and cancelled events`() {
        val events = read("google.ics")
        assertEquals("2026-10-19T08:00", local(events.titled("Dentist").single().startMs))
        assertEquals("2026-10-19T00:00", local(events.titled("Midnight feed").single().startMs))
        assertFalse(events.any { it.title == "Cancelled meeting" })
    }

    @Test fun `only occurrences inside the window`() {
        val events = read("google.ics", from = "2026-10-19T08:30", to = "2026-10-19T12:00")
        // breakfast 09:00–09:30 is inside; the dentist (08:00–09:00) overlaps the start, so it's included too
        assertEquals(listOf("Dentist", "Breakfast together"), events.map { it.title })
    }

    @Test fun `outlook floating times use the hub's zone and windows-named zones resolve`() {
        val events = read("outlook-floating.ics")
        assertEquals("2026-10-19T10:00", local(events.titled("Football").single().startMs))
        val piano = events.titled("Piano").single()
        assertEquals("2026-10-19T16:00", local(piano.startMs))
        assertEquals(45 * 60_000L, piano.endMs - piano.startMs)
    }

    @Test fun `occurrence ids are unique and stable`() {
        val a = read("google.ics")
        assertEquals(a.size, a.map { it.id }.toSet().size)
        assertEquals(a.map { it.id }, read("google.ics").map { it.id })
    }

    @Test fun `garbage is an empty calendar, not a crash`() {
        assertTrue(Ics.parse("<html>not a calendar</html>", "x", 0, Long.MAX_VALUE / 2, AMSTERDAM).isEmpty())
    }
}
