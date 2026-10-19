package dev.framio.hub.core.day

import dev.framio.hub.core.engine.Now
import dev.framio.hub.core.feeds.AgendaEvent
import dev.framio.hub.core.feeds.Nowcast
import dev.framio.hub.core.feeds.Weather
import dev.framio.hub.core.feeds.WeatherLine
import dev.framio.hub.core.model.HubState
import dev.framio.hub.core.model.Person
import dev.framio.hub.core.model.Run
import dev.framio.hub.core.model.Timer
import dev.framio.hub.core.time.contains
import dev.framio.hub.core.time.local
import dev.framio.hub.core.time.LocalTimes
import dev.framio.hub.core.timers.remainingMs
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One clause of the day sentence, as structure: `:app` words it with string resources (and German). */
sealed interface Clause {
    data class Weather(val line: WeatherLine) : Clause

    /** The next event today (or within 3 h). [inMinutes] is set when it's within the hour: "Dentist in 25 min". */
    data class NextEvent(val title: String, val who: String?, val startMs: Long, val inMinutes: Int?) : Clause

    /** In the evening, tomorrow's first event: "Tomorrow: football at 09:00 · Max". */
    data class Tomorrow(val title: String, val who: String?, val startMs: Long, val allDay: Boolean) : Clause
}

/** The small status chips under the sentence, most important first. */
sealed interface Chip {
    data class TimerChip(val id: String, val label: String, val color: Int, val state: TimerState) : Chip
    data class NewNotes(val count: Int) : Chip
    data class RoutineChip(val id: String, val title: String, val done: Int, val total: Int, val color: Int?, val leaveInMin: Int?) : Chip
}

sealed interface TimerState {
    data class Running(val remainingMs: Long) : TimerState
    data class Paused(val remainingMs: Long) : TimerState
    data object Ringing : TimerState
    data class Finished(val agoMs: Long?) : TimerState
    data object Interrupted : TimerState
    data object Unknown : TimerState
}

data class Glance(val clauses: List<Clause>, val chips: List<Chip>)

/**
 * Decides what the Ambient screen says, in priority order: timers, weather, the next event, new notes, the
 * routine of the moment, and tomorrow (in the evening). Pure; the tables in GlanceTest pin it down.
 */
object GlanceRanker {
    const val SOON_EVENT_MS = 3 * 3_600_000L
    const val NEW_NOTE_MS = 12 * 3_600_000L
    const val TOMORROW_FROM_HOUR = 18

    fun rank(state: HubState, weather: Weather?, events: List<AgendaEvent>, now: Now): Glance {
        val chips = mutableListOf<Chip>()
        state.timers.sortedBy { order(it) }.forEach { chips += Chip.TimerChip(it.id, it.label, it.color, timerState(it, now)) }

        val clauses = mutableListOf<Clause>()
        val local = now.local() ?: return Glance(clauses, chips) // without the time, no sentence: the UI says so
        val wall = now.wallMs!!

        if (weather != null && Nowcast.ageMs(weather, wall) < Nowcast.STALE_MS) {
            Nowcast.line(weather, wall)?.let { clauses += Clause.Weather(it) }
            Nowcast.frost(weather, wall, local.hour)?.let { clauses += Clause.Weather(it) }
        }

        val endOfToday = LocalTimes.startOfDay(local.toLocalDate().plusDays(1), now.zone)
        val next = events.firstOrNull { !it.allDay && it.startMs > wall && (it.startMs < endOfToday || it.startMs - wall <= SOON_EVENT_MS) }
        next?.let {
            val minutes = ((it.startMs - wall + 59_999) / 60_000).toInt()
            clauses += Clause.NextEvent(it.title, Agenda.who(it, state), it.startMs, minutes.takeIf { m -> m <= 60 })
        }

        val newNotes = state.notes.count { n ->
            val created = n.createdWallMs ?: return@count false
            wall - created < NEW_NOTE_MS && created > (state.boardSeenWallMs ?: Long.MIN_VALUE)
        }
        if (newNotes > 0) chips += Chip.NewNotes(newNotes)

        state.routines.filter { r -> r.window?.contains(local.toLocalTime()) == true }.forEach { r ->
            val end = LocalTimes.parse(r.window!!.end)
            val leaveIn = end?.let { Duration.between(local.toLocalTime(), it).toMinutes().let { m -> if (m < 0) m + 1440 else m }.toInt() }
            chips += Chip.RoutineChip(r.id, r.title, r.steps.count { it.done }, r.steps.size, state.person(r.personId)?.color, leaveIn)
        }

        if (local.hour >= TOMORROW_FROM_HOUR) {
            val tomorrowStart = endOfToday
            val tomorrowEnd = LocalTimes.startOfDay(local.toLocalDate().plusDays(2), now.zone)
            events.filter { it.startMs in tomorrowStart until tomorrowEnd }
                .sortedWith(compareBy({ !it.allDay }, { it.startMs })) // a birthday before the dentist
                .firstOrNull()
                ?.let { clauses += Clause.Tomorrow(it.title, Agenda.who(it, state), it.startMs, it.allDay) }
        }
        return Glance(clauses, chips)
    }

    private fun order(t: Timer) = when (t.run) {
        is Run.Ringing -> 0
        is Run.Running -> 1
        is Run.Paused -> 2
        is Run.Done -> 3
        Run.Interrupted -> 4
    }

    fun timerState(t: Timer, now: Now): TimerState = when (val r = t.run) {
        is Run.Running -> r.until.remainingMs(now)?.let { TimerState.Running(it.coerceAtLeast(0)) } ?: TimerState.Unknown
        is Run.Paused -> TimerState.Paused(r.remainingMs)
        is Run.Ringing -> TimerState.Ringing
        is Run.Done -> TimerState.Finished(r.at.remainingMs(now)?.let { -it })
        Run.Interrupted -> TimerState.Interrupted
    }
}

/** Fits the sentence: clauses are added in priority order; the first that doesn't fit is dropped with the rest. */
object Sentence {
    fun fit(clauses: List<Clause>, fits: (List<Clause>) -> Boolean): List<Clause> {
        val out = mutableListOf<Clause>()
        for (c in clauses) {
            if (!fits(out + c)) break
            out += c
        }
        return out
    }
}

/** Who an event belongs to, its colour, and grouping by day. */
object Agenda {
    /** Neutral grey for calendars without a person. */
    const val NEUTRAL = 0xFF9A968E.toInt()

    fun person(e: AgendaEvent, state: HubState): Person? = state.person(state.calendars.firstOrNull { it.id == e.calendarId }?.personId)

    fun who(e: AgendaEvent, state: HubState): String? = person(e, state)?.name

    fun color(e: AgendaEvent, state: HubState): Int = person(e, state)?.color?.let { dev.framio.hub.core.model.Palette.argb(it) } ?: NEUTRAL

    /** The person's initial, or the calendar's, so colour is never the only cue. */
    fun badge(e: AgendaEvent, state: HubState): String =
        (person(e, state)?.name ?: state.calendars.firstOrNull { it.id == e.calendarId }?.name ?: e.title).take(1).uppercase()

    /** Events touching the local [date]: all-day ones first, then by start. */
    fun on(events: List<AgendaEvent>, date: LocalDate, zone: ZoneId): List<AgendaEvent> {
        val start = LocalTimes.startOfDay(date, zone)
        val end = LocalTimes.startOfDay(date.plusDays(1), zone)
        return events.filter { it.startMs < end && it.endMs > start || (it.startMs == it.endMs && it.startMs in start until end) }
            .sortedWith(compareBy({ !it.allDay }, { it.startMs }))
    }

    fun localDate(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
}
