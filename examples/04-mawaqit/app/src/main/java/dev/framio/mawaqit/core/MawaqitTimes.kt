package dev.framio.mawaqit.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The times of a Mawaqit mosque, read the way Mawaqit's own clients read them.
 *
 * @param calendar 12 months, day of month → 6 `HH:MM` (Fajr, Shuruq, Dhuhr, Asr, Maghrib, Isha),
 *   or 7 with Imsak first (then Sabah as Fajr)
 * @param iqamaCalendar 12 months, day of month → 5 iqamas, `HH:MM` or `+N` minutes after the adhan
 * @param imsakMinBeforeFajr Imsak this many minutes before Fajr when the calendar has no Imsak, 0 = none
 * @param eidTimes `HH:MM` of the Eid prayers, given on [eidDates] only (Mawaqit doesn't publish the Eid dates)
 */
class MawaqitTimes(
    private val calendar: List<Map<Int, List<String>>>,
    private val iqamaCalendar: List<Map<Int, List<String>>>,
    private val iqamaEnabled: Boolean,
    private val jumua: String?,
    private val jumua2: String?,
    private val jumua3: String?,
    private val jumuaAsDuhr: Boolean,
    private val imsakMinBeforeFajr: Int,
    override val zone: ZoneId,
    private val eidTimes: List<String> = emptyList(),
    private val eidDates: Set<LocalDate> = emptySet(),
) : TimesSource {

    /** The day's prayers, or null when the calendar has no valid row for it. */
    override fun day(date: LocalDate): PrayerDay? {
        val row = row(calendar, date)?.takeIf { it.size == 6 || it.size == 7 } ?: return null
        val withImsak = row.size == 7
        val times = if (withImsak) row.subList(1, 7) else row
        val iqamas = if (iqamaEnabled) row(iqamaCalendar, date)?.takeIf { it.size == 5 } else null

        val prayers = arrayOfNulls<PrayerTime>(6)
        var last: PrayerTime? = null
        for (i in 0 until 6) {
            val iqama = if (iqamas == null || i == 1) null else iqamas[maxOf(i - 1, 0)]
            prayers[i] = prayer(ORDER[i], times[i], iqama, date, after = if (i == 5) last else null)
            last = prayers[i] ?: last
        }
        val fajr = prayers[0]
        val imsak = when {
            withImsak -> prayer(PrayerKey.IMSAK, row[0], null, date)
            fajr != null && imsakMinBeforeFajr > 0 ->
                PrayerTime(PrayerKey.IMSAK, fajr.adhan.minusMinutes(imsakMinBeforeFajr.toLong()))
            else -> null
        }
        val jumuas = if (date.dayOfWeek != DayOfWeek.FRIDAY) emptyList() else {
            val first = if (jumuaAsDuhr) prayers[2]?.let { PrayerTime(PrayerKey.JUMUA, it.adhan) } else prayer(PrayerKey.JUMUA, jumua, null, date)
            listOfNotNull(first) + listOf(jumua2, jumua3).mapNotNull { prayer(PrayerKey.JUMUA, it, null, date) }
        }
        val eid = if (date in eidDates) eidTimes.mapNotNull { prayer(PrayerKey.EID, it, null, date) } else emptyList()

        return PrayerDay(
            date = date, imsak = imsak, fajr = fajr, shuruq = prayers[1], dhuhr = prayers[2], asr = prayers[3],
            maghrib = prayers[4], isha = prayers[5], jumua = jumuas, eid = eid,
        )
    }

    private fun row(months: List<Map<Int, List<String>>>, date: LocalDate): List<String>? =
        months.getOrNull(date.monthValue - 1)?.get(date.dayOfMonth)

    /** @param after for Isha, the last valid prayer before it: an Isha earlier than it is after midnight */
    private fun prayer(key: PrayerKey, value: String?, iqama: String?, date: LocalDate, after: PrayerTime? = null): PrayerTime? {
        val time = parseTime(value) ?: return null
        var day = date
        var at = zoned(day, time, zone)
        // Not for the other prayers: a typo like 16:30 for Fajr would move the whole day.
        if (after != null && at.isBefore(after.adhan)) {
            val nextDay = zoned(day.plusDays(1), time, zone)
            if (Duration.between(after.adhan, nextDay) < HALF_DAY) {
                day = day.plusDays(1)
                at = nextDay
            }
        }
        val (iqamaAt, offset) = iqama?.let { iqama(at, it, day) } ?: (null to null)
        return PrayerTime(key, at, iqamaAt, offset)
    }

    /** `+N` minutes after the adhan, or `HH:MM` (the next day when earlier than the adhan); null if 12 h or more after. */
    private fun iqama(adhan: ZonedDateTime, value: String, day: LocalDate): Pair<ZonedDateTime, Int?>? {
        val v = value.trim()
        val (at, offset) = if (MINUTES.matches(v)) {
            val minutes = v.removePrefix("+").toIntOrNull() ?: return null
            adhan.plusMinutes(minutes.toLong()) to minutes // on the instant timeline, not the wall clock
        } else {
            val time = parseTime(v) ?: return null
            val sameDay = zoned(day, time, zone)
            (if (sameDay.isBefore(adhan)) zoned(day.plusDays(1), time, zone) else sameDay) to null
        }
        return if (Duration.between(adhan, at) < HALF_DAY) at to offset else null
    }

    companion object {
        private val ORDER = listOf(PrayerKey.FAJR, PrayerKey.SHURUQ, PrayerKey.DHUHR, PrayerKey.ASR, PrayerKey.MAGHRIB, PrayerKey.ISHA)
        private val TIME = Regex("([0-9]{1,2}):([0-9]{2})")
        private val MINUTES = Regex("\\+?[0-9]+")
        private val HALF_DAY: Duration = Duration.ofHours(12)

        /** `HH:MM`, or null for "", "--:--", "25:00"… */
        fun parseTime(value: String?): LocalTime? {
            val m = TIME.matchEntire(value?.trim() ?: return null) ?: return null
            val (h, min) = m.destructured
            return if (h.toInt() in 0..23 && min.toInt() in 0..59) LocalTime.of(h.toInt(), min.toInt()) else null
        }

        /** [time] on [date] in [zone]; a time the clocks skip (DST) moves forward by the gap. */
        fun zoned(date: LocalDate, time: LocalTime, zone: ZoneId): ZonedDateTime = ZonedDateTime.of(date, time, zone)
    }
}

/**
 * [base] with each adhan moved by [offsets] minutes (Fajr, Shuruq, Dhuhr, Asr, Maghrib, Isha; Imsak follows Fajr).
 * An iqama given as `+N` moves with its adhan, an `HH:MM` one stays. [iqamaOverride] (minutes after the adhan, for
 * the five prayers) replaces the mosque's iqamas. Jumu'a and Eid are not moved.
 */
class AdjustedTimes(
    private val base: TimesSource,
    private val offsets: List<Int>,
    private val iqamaOverride: List<Int>?,
) : TimesSource {
    override val zone: ZoneId get() = base.zone

    override fun day(date: LocalDate): PrayerDay? {
        val d = base.day(date) ?: return null
        if (iqamaOverride == null && offsets.all { it == 0 }) return d
        return d.copy(
            imsak = shift(d.imsak, 0), fajr = shift(d.fajr, 0), shuruq = shift(d.shuruq, 1), dhuhr = shift(d.dhuhr, 2),
            asr = shift(d.asr, 3), maghrib = shift(d.maghrib, 4), isha = shift(d.isha, 5),
        )
    }

    private fun shift(p: PrayerTime?, index: Int): PrayerTime? {
        if (p == null) return null
        val adhan = p.adhan.plusMinutes(offsets.getOrElse(index) { 0 }.toLong())
        val override = iqamaOverride?.getOrNull(p.key.five)
        return when {
            override != null -> PrayerTime(p.key, adhan, adhan.plusMinutes(override.toLong()), override)
            p.iqamaOffset != null -> p.copy(adhan = adhan, iqama = adhan.plusMinutes(p.iqamaOffset.toLong()))
            else -> p.copy(adhan = adhan)
        }
    }
}
