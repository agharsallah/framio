package dev.framio.hub.core.time

import dev.framio.hub.core.engine.Now
import dev.framio.hub.core.model.TimeWindow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Local "HH:mm" times as the household writes them, and the local calendar around them. */
object LocalTimes {
    private val HHMM = Regex("""^([01]?\d|2[0-3]):([0-5]\d)$""")

    fun parse(hhmm: String): LocalTime? =
        HHMM.matchEntire(hhmm.trim())?.let { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }

    fun isValid(hhmm: String) = parse(hhmm) != null

    /**
     * The day a daily reset at [resetAt] belongs to: before 04:00 it is still yesterday's routine.
     * ISO date, e.g. "2026-10-18".
     */
    fun resetDay(resetAt: String, wallMs: Long, zone: ZoneId): String {
        val local = Instant.ofEpochMilli(wallMs).atZone(zone)
        val reset = parse(resetAt) ?: LocalTime.of(4, 0)
        val date = if (local.toLocalTime() < reset) local.toLocalDate().minusDays(1) else local.toLocalDate()
        return date.toString()
    }

    /** The next time the wall clock reads [at] after [wallMs], DST-correct (a skipped 02:30 becomes 03:30). */
    fun nextOccurrence(at: LocalTime, wallMs: Long, zone: ZoneId): Long {
        val now = Instant.ofEpochMilli(wallMs).atZone(zone)
        var candidate = ZonedDateTime.of(now.toLocalDate(), at, zone)
        if (!candidate.toInstant().isAfter(now.toInstant())) candidate = ZonedDateTime.of(now.toLocalDate().plusDays(1), at, zone)
        return candidate.toInstant().toEpochMilli()
    }

    fun startOfDay(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()
}

/** True when [time] is inside the window, which may cross midnight (21:30–07:00). End is exclusive. */
fun TimeWindow.contains(time: LocalTime): Boolean {
    val s = LocalTimes.parse(start) ?: return false
    val e = LocalTimes.parse(end) ?: return false
    return when {
        s == e -> false
        s < e -> time >= s && time < e
        else -> time >= s || time < e
    }
}

fun TimeWindow.isValid() = LocalTimes.isValid(start) && LocalTimes.isValid(end)

/** Local date and time, or null while the clock isn't trusted. */
fun Now.local(): ZonedDateTime? = wallMs?.let { Instant.ofEpochMilli(it).atZone(zone) }
