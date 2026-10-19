package dev.framio.hub.core.feeds

import biweekly.Biweekly
import biweekly.ICalendar
import biweekly.component.VEvent
import biweekly.property.ICalProperty
import biweekly.util.ICalDate
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/** One occurrence of a calendar event, ready to draw. All-day events carry dates instead of instants. */
@Serializable
data class AgendaEvent(
    /** Unique per occurrence: uid + start. */
    val id: String,
    val calendarId: String,
    val title: String,
    val location: String? = null,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean = false,
)

/**
 * Reads .ics feeds (Google's secret address, iCloud, Outlook) with biweekly and expands recurrences into
 * occurrences inside a window. Handles TZIDs with or without VTIMEZONE, floating times (in the Hub's zone),
 * all-day events, EXDATE, RECURRENCE-ID overrides and cancelled events.
 */
object Ics {
    /** Safety net for a rule like FREQ=MINUTELY: the window never needs more than this many. */
    private const val MAX_PER_EVENT = 500

    fun parse(text: String, calendarId: String, fromMs: Long, toMs: Long, zone: ZoneId): List<AgendaEvent> {
        val calendars = Biweekly.parse(text).all()
        val out = mutableListOf<AgendaEvent>()
        for (cal in calendars) {
            val (masters, overrides) = cal.events.filter { it.dateStart?.value != null }.partition { it.recurrenceId == null }
            val overridden = overrides.groupBy { it.uid?.value }.mapValues { (_, list) -> list.mapNotNull { key(it.recurrenceId.value, cal, it.recurrenceId, zone) }.toSet() }
            for (event in masters) {
                if (cancelled(event)) continue
                val skip = overridden[event.uid?.value] ?: emptySet()
                out += occurrences(cal, event, calendarId, fromMs, toMs, zone, skip)
            }
            for (event in overrides) {
                if (cancelled(event)) continue
                single(cal, event, calendarId, zone)?.takeIf { it.endMs > fromMs && it.startMs < toMs }?.let { out += it }
            }
        }
        return out.distinctBy { it.id }.sortedWith(compareBy({ it.startMs }, { it.title }))
    }

    private fun cancelled(e: VEvent) = e.status?.value.equals("CANCELLED", ignoreCase = true)

    private fun occurrences(cal: ICalendar, e: VEvent, calendarId: String, fromMs: Long, toMs: Long, zone: ZoneId, skip: Set<Long>): List<AgendaEvent> {
        val first = single(cal, e, calendarId, zone) ?: return emptyList()
        val length = first.endMs - first.startMs
        val recurring = e.recurrenceRule != null || e.recurrenceDates.isNotEmpty()
        if (!recurring) return if (first.endMs > fromMs && first.startMs < toMs && first.startMs !in skip) listOf(first) else emptyList()

        val start = e.dateStart.value
        val tz = iterationZone(cal, e.dateStart, start, zone)
        val iterator = e.getDateIterator(tz)
        iterator.advanceTo(Date(fromMs - length - 1)) // occurrences already over before the window are skipped
        val out = mutableListOf<AgendaEvent>()
        var n = 0
        while (iterator.hasNext() && n++ < MAX_PER_EVENT) {
            val date = iterator.next()
            val startMs = if (start.hasTime()) date.time else startOfDay(date, tz, zone)
            if (startMs >= toMs) break
            val endMs = if (start.hasTime()) startMs + length else endOfDays(startMs, first, zone)
            if (endMs <= fromMs || startMs in skip) continue
            out += first.copy(id = "${e.uid?.value ?: first.title}@$startMs", startMs = startMs, endMs = endMs)
        }
        return out
    }

    /** The event as written, without recurrence. */
    private fun single(cal: ICalendar, e: VEvent, calendarId: String, zone: ZoneId): AgendaEvent? {
        val start = e.dateStart?.value ?: return null
        val startMs = key(start, cal, e.dateStart, zone) ?: return null
        val endMs = when {
            e.dateEnd?.value != null -> key(e.dateEnd.value, cal, e.dateEnd, zone)
            e.duration?.value != null -> startMs + e.duration.value.toMillis()
            !start.hasTime() -> plusDays(startMs, 1, zone)
            else -> startMs
        } ?: startMs
        return AgendaEvent(
            id = "${e.uid?.value ?: e.summary?.value}@$startMs",
            calendarId = calendarId,
            title = e.summary?.value?.trim()?.ifEmpty { null } ?: "(no title)",
            location = e.location?.value?.trim()?.ifEmpty { null },
            startMs = startMs,
            endMs = maxOf(endMs, startMs),
            allDay = !start.hasTime(),
        )
    }

    /**
     * Epoch ms for a property's date: a date means local midnight in the Hub's zone; a floating time is read
     * in the Hub's zone; a time with a TZID or Z is an instant already (biweekly resolved it).
     */
    private fun key(date: ICalDate, cal: ICalendar, property: ICalProperty, zone: ZoneId): Long? {
        val raw = date.rawComponents
        return when {
            !date.hasTime() && raw != null -> LocalDate.of(raw.year, raw.month, raw.date).atStartOfDay(zone).toInstant().toEpochMilli()
            raw != null && cal.timezoneInfo.isFloating(property) && !raw.isUtc ->
                LocalDateTime.of(raw.year, raw.month, raw.date, raw.hour, raw.minute, raw.second).atZone(zone).toInstant().toEpochMilli()
            else -> date.time
        }
    }

    /** The zone rules repeat in: the event's own TZID if it has one, else the Hub's (floating times, dates). */
    private fun iterationZone(cal: ICalendar, property: ICalProperty, start: ICalDate, zone: ZoneId): TimeZone {
        if (start.hasTime()) {
            cal.timezoneInfo.getTimezone(property)?.timeZone?.let { return it }
            if (start.rawComponents?.isUtc == true) return TimeZone.getTimeZone("UTC")
        }
        return TimeZone.getTimeZone(zone.id)
    }

    /** A date-only occurrence comes back as midnight in [tz]; take its calendar date in the Hub's zone. */
    private fun startOfDay(date: Date, tz: TimeZone, zone: ZoneId): Long {
        // Calendar, not TimeZone.toZoneId(): that one is API 26, and tz may be a VTIMEZONE without an Olson id
        val c = Calendar.getInstance(tz).apply { time = date }
        val local = LocalDate.of(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        return local.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    /** All-day occurrences keep the master's length in days (a 3-day trip stays 3 days across DST). */
    private fun endOfDays(startMs: Long, first: AgendaEvent, zone: ZoneId): Long {
        val days = Duration.ofMillis(first.endMs - first.startMs).plusHours(12).toDays().coerceAtLeast(1)
        return plusDays(startMs, days, zone)
    }

    private fun plusDays(ms: Long, days: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).plusDays(days).toInstant().toEpochMilli()
}
