package dev.framio.mawaqit.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * A secondary mosque as the display shows it: display only, the main mosque alone drives the adhan and the screens.
 * [today], [tomorrow], [next] and [jumua] are computed for the moment the entry was published (every minute).
 *
 * @param name the mosque's name, or its slug until the page has been read once
 * @param jumua the Jumu'a times of today if it is a Friday, else of the coming Friday
 * @param updatedAt when the page shown was fetched (ms), null before the first load
 * @param error "not_found", "network" or "parse" from the last attempt; the cached times are still shown
 */
data class SecondaryEntry(
    val slug: String,
    val name: String,
    val today: PrayerDay?,
    val tomorrow: PrayerDay?,
    val next: PrayerTime?,
    val jumua: List<PrayerTime>,
    val updatedAt: Long?,
    val error: String?,
    val loading: Boolean,
)

/** The pure rules behind the secondary mosques: which ones, when to fetch them, what each shows. */
object Secondary {
    const val FULL_RELOAD_MS = 6 * 3_600_000L

    /** The slugs to load, in order: the main mosque is never loaded twice. */
    fun slugs(source: SourceSettings): List<String> = source.secondary.filter { it != source.mosque }

    /** After a failure, 2, 4, 8 … minutes before the next try, at most an hour: three mosques must not hammer Mawaqit. */
    fun retryDelayMs(failures: Int): Long =
        if (failures <= 0) 0L else minOf(120_000L shl (failures - 1).coerceAtMost(5), 3_600_000L)

    /** When to fetch a mosque next: 6 h after the page shown, sooner after a failure; now when it was never loaded. */
    fun nextAttempt(updatedAt: Long?, failures: Int, lastTry: Long): Long = when {
        failures > 0 -> lastTry + retryDelayMs(failures)
        updatedAt == null -> 0L
        else -> updatedAt + FULL_RELOAD_MS
    }

    /** The Jumu'a times of [date] if it is a Friday, else of the Friday after it (empty if the mosque has none). */
    fun jumua(times: TimesSource?, date: LocalDate): List<PrayerTime> =
        times?.day(date.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)))?.jumua.orEmpty()

    fun entry(slug: String, name: String, times: TimesSource?, now: Instant, updatedAt: Long?, error: String?, loading: Boolean): SecondaryEntry {
        if (times == null) return SecondaryEntry(slug, name, null, null, null, emptyList(), updatedAt, error, loading)
        val at = now.atZone(times.zone)
        val date = at.toLocalDate()
        return SecondaryEntry(
            slug = slug, name = name,
            today = times.day(date), tomorrow = times.day(date.plusDays(1)),
            next = Timeline.nextPrayer(at, times), jumua = jumua(times, date),
            updatedAt = updatedAt, error = error, loading = loading,
        )
    }

    /**
     * The five cells of a day's row (Fajr … Isha) and the index of the one to highlight: the cell whose adhan is
     * [next], 5 for the Jumu'a column, -1 when the next prayer is not on this day.
     */
    fun highlight(day: PrayerDay?, jumua: List<PrayerTime>, next: PrayerTime?): Int {
        if (day == null || next == null) return -1
        val at = next.adhan.toInstant()
        // A Jumu'a "at Dhuhr" has Dhuhr's time: the key decides the column
        if (next.key == PrayerKey.JUMUA) return if (jumua.any { it.adhan.toInstant() == at }) 5 else -1
        return day.five.indexOfFirst { it != null && it.adhan.toInstant() == at }
    }
}
