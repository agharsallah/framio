package dev.framio.hub.core.day

import dev.framio.hub.core.AMSTERDAM
import dev.framio.hub.core.engine.Now
import dev.framio.hub.core.feeds.AgendaEvent
import dev.framio.hub.core.feeds.Weather
import dev.framio.hub.core.feeds.WeatherKind
import dev.framio.hub.core.feeds.WeatherLine
import dev.framio.hub.core.model.CalendarFeed
import dev.framio.hub.core.model.Deadline
import dev.framio.hub.core.model.HubState
import dev.framio.hub.core.model.Note
import dev.framio.hub.core.model.NoteBody
import dev.framio.hub.core.model.Person
import dev.framio.hub.core.model.Routine
import dev.framio.hub.core.model.Run
import dev.framio.hub.core.model.Step
import dev.framio.hub.core.model.TimeWindow
import dev.framio.hub.core.model.Timer
import dev.framio.hub.core.wallAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlanceTest {
    private val state = HubState(
        people = listOf(Person("lena", "Lena", 2), Person("max", "Max", 0)),
        calendars = listOf(CalendarFeed("swim", "Swimming", "lena"), CalendarFeed("foot", "Football", "max"), CalendarFeed("fam", "Family")),
    )

    private fun now(t: String) = Now(1_000_000, "b", wallAt(t), AMSTERDAM)
    private fun event(cal: String, title: String, start: String, end: String, allDay: Boolean = false) =
        AgendaEvent("$title@$start", cal, title, null, wallAt(start), wallAt(end), allDay)

    private val events = listOf(
        event("swim", "Swimming", "2026-10-18T17:30", "2026-10-18T18:30"),
        event("foot", "Football", "2026-10-19T09:00", "2026-10-19T10:30"),
        event("fam", "Grandma's birthday", "2026-10-19T00:00", "2026-10-20T00:00", allDay = true),
    )

    private fun dryWeather(fetched: Long) = Weather(fetched, null, listOf(Weather.Hour(fetched, 12.0, 0.0, 0, 1, 20)), emptyList(), emptyList())

    @Test fun `in the morning the sentence is weather, then the next event with its person`() {
        val n = now("2026-10-18T08:00")
        val g = GlanceRanker.rank(state, dryWeather(n.wallMs!!), events, n)
        assertEquals(
            listOf(Clause.Weather(WeatherLine.Dry(12)), Clause.NextEvent("Swimming", "Lena", wallAt("2026-10-18T17:30"), null)),
            g.clauses,
        )
    }

    @Test fun `an event within the hour says in how many minutes`() {
        val g = GlanceRanker.rank(state, null, events, now("2026-10-18T17:05"))
        assertEquals(Clause.NextEvent("Swimming", "Lena", wallAt("2026-10-18T17:30"), 25), g.clauses.single())
    }

    @Test fun `in the evening tomorrow appears, all-day first`() {
        val g = GlanceRanker.rank(state, null, events, now("2026-10-18T19:00"))
        assertEquals(listOf(Clause.Tomorrow("Grandma's birthday", null, wallAt("2026-10-19T00:00"), true)), g.clauses)
    }

    @Test fun `weather older than two hours is left out`() {
        val n = now("2026-10-18T08:00")
        val g = GlanceRanker.rank(state, dryWeather(n.wallMs!! - 2 * 3_600_000L), emptyList(), n)
        assertTrue(g.clauses.isEmpty())
    }

    @Test fun `without a trusted time there is no sentence, but timers still show`() {
        val timer = Timer("t", "Tea", 180_000, 1, 1, Run.Running(Deadline("b", 1_060_000)))
        val g = GlanceRanker.rank(state.copy(timers = listOf(timer)), null, events, Now(1_000_000, "b", null, AMSTERDAM))
        assertTrue(g.clauses.isEmpty())
        assertEquals(Chip.TimerChip("t", "Tea", 1, TimerState.Running(60_000)), g.chips.single())
    }

    @Test fun `chips are ringing timers first, then new notes, then the routine of the moment`() {
        val running = Timer("a", "Pasta", 480_000, 0, 0, Run.Running(Deadline("b", 1_100_000)))
        val ringing = Timer("b", "Eggs", 360_000, 1, 1, Run.Ringing(Deadline("b", 990_000)))
        val routine = Routine("bed", "Bedtime", "lena", listOf(Step("1", "Teeth", "😁", done = true), Step("2", "Pyjamas", "👕")), window = TimeWindow("19:00", "20:00"))
        val n = now("2026-10-18T19:45")
        val note = Note("n", NoteBody.Text("Hi"), createdWallMs = n.wallMs!! - 60_000)
        val g = GlanceRanker.rank(state.copy(timers = listOf(running, ringing), notes = listOf(note), routines = listOf(routine)), null, emptyList(), n)
        assertEquals(
            listOf(
                Chip.TimerChip("b", "Eggs", 1, TimerState.Ringing),
                Chip.TimerChip("a", "Pasta", 0, TimerState.Running(100_000)),
                Chip.NewNotes(1),
                Chip.RoutineChip("bed", "Bedtime", 1, 2, 2, 15),
            ),
            g.chips,
        )
        val seen = GlanceRanker.rank(state.copy(notes = listOf(note), boardSeenWallMs = n.wallMs), null, emptyList(), n)
        assertTrue(seen.chips.none { it is Chip.NewNotes })
    }

    @Test fun `the sentence keeps clauses in order and drops the first that doesn't fit, with the rest`() {
        val a = Clause.Weather(WeatherLine.RainFrom(0, WeatherKind.Rain, true))
        val b = Clause.NextEvent("Swimming", "Lena", 0, null)
        val c = Clause.Tomorrow("x", null, 0, false)
        assertEquals(listOf(a), Sentence.fit(listOf(a, b, c)) { it.size <= 1 })
        assertEquals(listOf(a, b, c), Sentence.fit(listOf(a, b, c)) { true })
        assertEquals("never skips ahead to a shorter clause", listOf(a), Sentence.fit(listOf(a, b, c)) { it.none { x -> x is Clause.NextEvent } })
    }

    @Test fun `event colours and badges come from the calendar's person`() {
        assertEquals("L", Agenda.badge(events[0], state))
        assertEquals("F", Agenda.badge(events[2], state)) // Family calendar, no person
        assertEquals(Agenda.NEUTRAL, Agenda.color(events[2], state))
        val day = Agenda.on(events, java.time.LocalDate.of(2026, 10, 19), AMSTERDAM)
        assertEquals(listOf("Grandma's birthday", "Football"), day.map { it.title })
    }
}
