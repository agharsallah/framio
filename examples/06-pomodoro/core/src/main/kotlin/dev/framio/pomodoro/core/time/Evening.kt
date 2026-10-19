package dev.framio.pomodoro.core.time

import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Evening amber (PLAN §3.2 row 13: evening screen light delays melatonin): from the evening start until the morning
 * the theme turns warm and the brightness is capped. It applies **only while the wall clock is trusted**: at boot the
 * frame believes it is 01:00 on the 1st of January (M0 §7), which must not paint the morning amber.
 */
public object Evening {
    /** Whether [time] falls in [start, end); a window that wraps midnight (20:00 → 06:00) is the usual case. */
    public fun covers(time: LocalTime, start: LocalTime?, end: LocalTime): Boolean = when {
        start == null || start == end -> false
        start.isBefore(end) -> !time.isBefore(start) && time.isBefore(end)
        else -> !time.isBefore(start) || time.isBefore(end)
    }

    /** Evening amber at [now]: false while the clock isn't trusted (no wall time) or when it's switched off. */
    public fun at(now: Now, start: LocalTime?, end: LocalTime): Boolean {
        val wall = now.wallMs ?: return false
        return covers(Instant.ofEpochMilli(wall).atZone(now.zone).toLocalTime(), start, end)
    }

    /**
     * How long until [at] may change by itself: until the next start or end, whichever comes first. Null when it can't
     * change by itself (switched off, or no trusted clock: then trust arriving is the change).
     */
    public fun nextChange(now: Now, start: LocalTime?, end: LocalTime): Duration? {
        val wall = now.wallMs ?: return null
        if (start == null || start == end) return null
        val here = Instant.ofEpochMilli(wall).atZone(now.zone)
        val next = listOf(start, end).minOf { nextAt(here, it).toInstant().toEpochMilli() }
        return (next - wall).milliseconds
    }

    /** The next time the local clock reads [time], strictly after [from] (DST gaps resolve forward). */
    private fun nextAt(from: ZonedDateTime, time: LocalTime): ZonedDateTime {
        val today = from.toLocalDate().atTime(time).atZone(from.zone)
        return if (today.isAfter(from)) today else from.toLocalDate().plusDays(1).atTime(time).atZone(from.zone)
    }
}
