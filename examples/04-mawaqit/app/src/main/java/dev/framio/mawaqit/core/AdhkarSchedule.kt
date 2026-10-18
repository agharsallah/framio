package dev.framio.mawaqit.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Each collection's own time of the day (highlighted on its card; the day its progress counts for).
 *
 * - Morning: from Fajr until Shuruq + 3 h.
 * - Evening: from Asr until Maghrib + 1 h.
 * - Before sleep: from the first enabled before-sleep schedule ([AdhkarSettings.sleepSchedule]) for [SLEEP_HOURS];
 *   a window that crosses midnight belongs to the day it started (its progress, completion and dismissal are that
 *   day's).
 *
 * When to remind of them (or open them) is the schedules' business: [AdhkarReminders].
 */
object AdhkarSchedule {
    const val SLEEP_HOURS = 3L
    private const val MORNING_AFTER_SHURUQ_HOURS = 3L
    private const val EVENING_AFTER_MAGHRIB_HOURS = 1L

    /** [start, end) is the collection's time. [day] is the window's own day. */
    data class Window(
        val category: AdhkarCategory,
        val day: LocalDate,
        val start: ZonedDateTime,
        val end: ZonedDateTime,
    ) {
        operator fun contains(t: ZonedDateTime) = !t.isBefore(start) && t.isBefore(end)
    }

    /** The windows that start on [day]. */
    fun windows(day: LocalDate, source: TimesSource, b: Behavior, s: AdhkarSettings): List<Window> {
        val out = ArrayList<Window>(3)
        val d = source.day(day)
        d?.fajr?.let { fajr ->
            val end = d.shuruq?.adhan?.plusHours(MORNING_AFTER_SHURUQ_HOURS) ?: fajr.adhan.plusHours(5)
            out += Window(AdhkarCategory.MORNING, day, fajr.adhan, end)
        }
        d?.asr?.let { asr ->
            val end = d.maghrib?.adhan?.plusHours(EVENING_AFTER_MAGHRIB_HOURS) ?: asr.adhan.plusHours(4)
            out += Window(AdhkarCategory.EVENING, day, asr.adhan, end)
        }
        s.sleepSchedule?.takeIf { it.on(day) }?.let { AdhkarReminders.resolve(it, day, source, b) }?.let { start ->
            // a clock time in a DST gap is moved forward by the gap's length (ZonedDateTime.of)
            out += Window(AdhkarCategory.SLEEP, day, start, start.plusHours(SLEEP_HOURS))
        }
        return out
    }

    /** The windows open at [now], from yesterday's and today's (a sleep window can start yesterday). */
    fun open(now: ZonedDateTime, source: TimesSource, b: Behavior, s: AdhkarSettings): List<Window> {
        val today = now.withZoneSameInstant(source.zone).toLocalDate()
        return (windows(today.minusDays(1), source, b, s) + windows(today, source, b, s)).filter { now in it }
    }

    /** The collection whose time it is, to highlight; the first by start when two overlap. */
    fun current(open: List<Window>): AdhkarCategory? = open.minByOrNull { it.start.toInstant() }?.category

    /** The day [category]'s progress counts for at [now]: an open window's day, otherwise today. */
    fun progressDay(category: AdhkarCategory, now: ZonedDateTime, open: List<Window>): LocalDate =
        open.firstOrNull { it.category == category }?.day ?: now.toLocalDate()

    fun sleepTime(hhmm: String): LocalTime? = runCatching {
        val (h, m) = hhmm.split(':').map(String::toInt)
        LocalTime.of(h, m)
    }.getOrNull()

    /**
     * When the screens of [p] (adhan, dua, iqama countdown, iqama, prayer, azkar) are over: the end of the last phase
     * that belongs to it. The adhan itself when it has no screens.
     */
    fun flowEnd(p: PrayerTime, source: TimesSource, b: Behavior): ZonedDateTime {
        var t = p.adhan
        repeat(12) {
            val phase = Timeline.phaseAt(t, source, b)
            val end = phase.end
            if (phase.prayer?.adhan != p.adhan || phase.kind == PhaseKind.REMINDER || end == null || !end.isAfter(t)) return t
            t = end
        }
        return t
    }

    /** The last of the five prayers whose adhan is at or before [now] (yesterday's Isha before Fajr), else null. */
    fun lastPrayer(now: ZonedDateTime, source: TimesSource): PrayerKey? {
        val today = now.withZoneSameInstant(source.zone).toLocalDate()
        return listOfNotNull(source.day(today.minusDays(1)), source.day(today))
            .flatMap { it.five.filterNotNull() }
            .filter { !it.adhan.isAfter(now) }
            .maxByOrNull { it.adhan.toInstant() }?.key
    }
}
