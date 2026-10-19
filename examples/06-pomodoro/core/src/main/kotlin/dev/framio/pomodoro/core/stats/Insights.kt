package dev.framio.pomodoro.core.stats

import dev.framio.pomodoro.core.model.SessionRecord
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration

/** Today's ring: finished sessions against the daily target, and the minutes focused. */
public data class TodayProgress(val sessions: Int, val target: Int, val focused: Duration) {
    /** 0..1 for the ring. */
    val fraction: Float get() = if (target <= 0) 0f else (sessions.toFloat() / target).coerceIn(0f, 1f)
}

public data class DayTotal(val date: LocalDate, val focused: Duration, val sessions: Int)

/**
 * Calendar helpers for the statistics. The reports themselves (today, the week, "N of 7 days", estimates, the hour
 * heatmap) are built by `history.InsightsReport` from day buckets (DESIGN-history §8); records without a trusted start
 * time have no date and are left out of everything by local date.
 */
public object Insights {
    /** The local date a record started on, or null if its start time was never known. */
    public fun dateOf(record: SessionRecord, zone: ZoneId): LocalDate? =
        record.startWallMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
}
