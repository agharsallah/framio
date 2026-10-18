package dev.framio.mawaqit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class MawaqitTimesTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val day = LocalDate.of(2026, 10, 20) // a Tuesday
    private val friday = LocalDate.of(2026, 10, 23)
    private val row = listOf("06:30", "08:00", "13:00", "16:00", "18:45", "20:15")

    private fun at(date: LocalDate, h: Int, m: Int) = ZonedDateTime.of(date.atTime(h, m), paris)

    /** A calendar with [rows] on their dates, nothing elsewhere. */
    private fun calendar(rows: Map<LocalDate, List<String>>): List<Map<Int, List<String>>> =
        List(12) { m -> rows.filterKeys { it.monthValue == m + 1 }.mapKeys { it.key.dayOfMonth } }

    private fun times(
        rows: Map<LocalDate, List<String>>,
        iqamas: Map<LocalDate, List<String>> = emptyMap(),
        iqamaEnabled: Boolean = true,
        jumua: String? = null,
        jumua2: String? = null,
        jumua3: String? = null,
        jumuaAsDuhr: Boolean = false,
        imsakMinBeforeFajr: Int = 0,
        zone: ZoneId = paris,
        eidDates: Set<LocalDate> = emptySet(),
    ) = MawaqitTimes(
        calendar(rows), calendar(iqamas), iqamaEnabled, jumua, jumua2, jumua3, jumuaAsDuhr, imsakMinBeforeFajr, zone,
        eidTimes = listOf("07:30", "08:15"), eidDates = eidDates,
    )

    @Test
    fun readsTheSixTimesInTheMosqueZone() {
        val d = times(mapOf(day to row)).day(day)!!
        assertEquals(at(day, 6, 30), d.fajr!!.adhan)
        assertEquals(at(day, 8, 0), d.shuruq!!.adhan)
        assertEquals(listOf(13, 16, 18, 20), listOf(d.dhuhr, d.asr, d.maghrib, d.isha).map { it!!.adhan.hour })
        assertEquals(PrayerKey.ISHA, d.isha!!.key)
        assertNull(d.imsak)
        assertTrue(d.jumua.isEmpty())
        assertTrue(d.eid.isEmpty())
    }

    @Test
    fun iqamaPlusMinutesIsAfterTheAdhan() {
        val d = times(mapOf(day to row), mapOf(day to listOf("+20", "+10", "10", "+0", "+5"))).day(day)!!
        assertEquals(at(day, 6, 50), d.fajr!!.iqama)
        assertEquals(20, d.fajr!!.iqamaOffset)
        assertEquals(at(day, 13, 10), d.dhuhr!!.iqama)
        assertEquals(10, d.asr!!.iqamaOffset) // "10" without a plus is minutes too
        assertEquals(d.maghrib!!.adhan, d.maghrib!!.iqama)
        assertEquals(at(day, 20, 20), d.isha!!.iqama)
        assertNull(d.shuruq!!.iqama)
    }

    @Test
    fun iqamaHhMmIsThatDayOrTheNextWhenEarlier() {
        val late = listOf("06:30", "08:00", "13:00", "16:00", "21:45", "23:50")
        val d = times(mapOf(day to late), mapOf(day to listOf("06:45", "13:30", "16:15", "21:50", "00:05"))).day(day)!!
        assertEquals(at(day, 13, 30), d.dhuhr!!.iqama)
        assertNull(d.dhuhr!!.iqamaOffset)
        assertEquals(at(day.plusDays(1), 0, 5), d.isha!!.iqama)
    }

    @Test
    fun iqamaTwelveHoursOrMoreAfterTheAdhanIsRejected() {
        val d = times(mapOf(day to row), mapOf(day to listOf("+719", "+720", "12:59", "x", ""))).day(day)!!
        assertEquals(at(day, 18, 29), d.fajr!!.iqama)
        assertNull(d.dhuhr!!.iqama) // +720 = 12 h
        assertNull(d.asr!!.iqama)   // 12:59 < 16:00 → tomorrow 12:59, 21 h later
        assertNull(d.maghrib!!.iqama)
        assertNull(d.isha!!.iqama)
    }

    @Test
    fun noIqamaWhenDisabledOrRowIsNotFive() {
        val iqama = listOf("+20", "+10", "+10", "+5", "+10")
        assertNull(times(mapOf(day to row), mapOf(day to iqama), iqamaEnabled = false).day(day)!!.fajr!!.iqama)
        assertNull(times(mapOf(day to row), mapOf(day to iqama.take(4))).day(day)!!.fajr!!.iqama)
        assertNull(times(mapOf(day to row)).day(day)!!.fajr!!.iqama)
    }

    @Test
    fun invalidTimesAreNullAndInvalidRowsInvalidateTheDay() {
        val d = times(mapOf(day to listOf("", "--:--", "25:00", "16:60", "18:45", " 20:15 "))).day(day)!!
        assertNull(d.fajr)
        assertNull(d.shuruq)
        assertNull(d.dhuhr)
        assertNull(d.asr)
        assertEquals(at(day, 18, 45), d.maghrib!!.adhan)
        assertEquals(at(day, 20, 15), d.isha!!.adhan)

        assertNull(times(mapOf(day to row.take(5))).day(day))
        assertNull(times(mapOf(day to row + row.take(2))).day(day))
        assertNull(times(mapOf(day to row)).day(day.plusDays(1)))
        assertNull(MawaqitTimes(emptyList(), emptyList(), true, null, null, null, false, 0, paris).day(day))
    }

    @Test
    fun sevenTimesStartWithImsakThenSabahAsFajr() {
        val d = times(mapOf(day to listOf("06:10") + row), imsakMinBeforeFajr = 20).day(day)!!
        assertEquals(at(day, 6, 10), d.imsak!!.adhan)
        assertEquals(PrayerKey.IMSAK, d.imsak!!.key)
        assertEquals(at(day, 6, 30), d.fajr!!.adhan)
        assertEquals(at(day, 20, 15), d.isha!!.adhan)
    }

    @Test
    fun imsakMinutesBeforeFajrWithoutImsakInTheCalendar() {
        assertEquals(at(day, 6, 20), times(mapOf(day to row), imsakMinBeforeFajr = 10).day(day)!!.imsak!!.adhan)
        // A 7-row calendar with an invalid Imsak keeps it null
        assertNull(times(mapOf(day to listOf("--") + row), imsakMinBeforeFajr = 10).day(day)!!.imsak)
    }

    @Test
    fun ishaEarlierThanMaghribIsAfterMidnight() {
        val summer = LocalDate.of(2026, 6, 21)
        val d = times(
            mapOf(summer to listOf("03:00", "04:30", "13:30", "18:00", "22:40", "00:20")),
            mapOf(summer to listOf("+20", "+10", "+10", "+5", "00:35")),
        ).day(summer)!!
        assertEquals(at(summer.plusDays(1), 0, 20), d.isha!!.adhan)
        assertEquals(at(summer.plusDays(1), 0, 35), d.isha!!.iqama) // HH:MM iqama read on the Isha's day

        // More than 12 h before: a mistake, kept as given
        val wrong = times(mapOf(day to listOf("06:30", "08:00", "13:00", "16:00", "18:45", "06:50"))).day(day)!!
        assertEquals(at(day, 6, 50), wrong.isha!!.adhan)
        // Only Isha moves: a Fajr "after" Shuruq stays that day
        val fajr = times(mapOf(day to listOf("16:30", "08:00", "13:00", "16:00", "18:45", "20:15"))).day(day)!!
        assertEquals(at(day, 16, 30), fajr.fajr!!.adhan)
    }

    @Test
    fun fridayJumuaTimes() {
        val rows = mapOf(friday to row, day to row)
        val f = times(rows, jumua = "13:30", jumua2 = "14:30", jumua3 = "--:--").day(friday)!!
        assertEquals(listOf(at(friday, 13, 30), at(friday, 14, 30)), f.jumua.map { it.adhan })
        assertTrue(f.jumua.all { it.key == PrayerKey.JUMUA && it.iqama == null })
        assertEquals(at(friday, 13, 0), f.dhuhr!!.adhan)
        assertTrue(times(rows, jumua = "13:30").day(day)!!.jumua.isEmpty())

        val asDuhr = times(rows, jumua = "13:30", jumua2 = "14:30", jumuaAsDuhr = true).day(friday)!!
        assertEquals(listOf(at(friday, 13, 0), at(friday, 14, 30)), asDuhr.jumua.map { it.adhan })
        assertTrue(times(rows).day(friday)!!.jumua.isEmpty())
    }

    @Test
    fun dstDaysInParis() {
        val spring = LocalDate.of(2026, 3, 29) // 02:00 → 03:00
        val s = times(
            mapOf(spring to listOf("02:30", "07:30", "13:50", "17:20", "20:15", "21:40")),
            mapOf(spring to listOf("+60", "+10", "+10", "+5", "+10")),
        ).day(spring)!!
        assertEquals(ZonedDateTime.of(spring.atTime(3, 30), paris), s.fajr!!.adhan) // skipped time moves forward
        assertEquals(ZoneOffset.ofHours(2), s.fajr!!.adhan.offset)
        assertEquals(60L, java.time.Duration.between(s.fajr!!.adhan, s.fajr!!.iqama).toMinutes())

        val autumn = LocalDate.of(2026, 10, 25) // 03:00 → 02:00
        val a = times(
            mapOf(autumn to listOf("01:30", "07:30", "13:00", "16:00", "18:00", "19:30")),
            mapOf(autumn to listOf("+120", "+10", "+10", "+5", "+10")),
        ).day(autumn)!!
        assertEquals(ZoneOffset.ofHours(2), a.fajr!!.adhan.offset)
        // +120 minutes of real time: 01:30+02:00 → 02:30+01:00 on the wall clock
        assertEquals(java.time.LocalTime.of(2, 30), a.fajr!!.iqama!!.toLocalTime())
        assertEquals(ZoneOffset.ofHours(1), a.fajr!!.iqama!!.offset)
        assertEquals(ZoneOffset.ofHours(1), a.dhuhr!!.adhan.offset)
    }

    @Test
    fun eidOnlyOnTheGivenDates() {
        val t = times(mapOf(day to row, friday to row), eidDates = setOf(day))
        assertEquals(listOf(at(day, 7, 30), at(day, 8, 15)), t.day(day)!!.eid.map { it.adhan })
        assertTrue(t.day(day)!!.eid.all { it.key == PrayerKey.EID })
        assertTrue(t.day(friday)!!.eid.isEmpty())
    }

    @Test
    fun adjustedTimesShiftAdhansAndRelativeIqamas() {
        val base = times(
            mapOf(friday to row), mapOf(friday to listOf("+20", "13:20", "+10", "+5", "+10")),
            jumua = "13:30", imsakMinBeforeFajr = 10,
        )
        val d = AdjustedTimes(base, listOf(1, 2, 3, 4, 5, 6), null).day(friday)!!
        assertEquals(at(friday, 6, 21), d.imsak!!.adhan)
        assertEquals(at(friday, 6, 31), d.fajr!!.adhan)
        assertEquals(at(friday, 6, 51), d.fajr!!.iqama)
        assertEquals(at(friday, 8, 2), d.shuruq!!.adhan)
        assertEquals(at(friday, 13, 3), d.dhuhr!!.adhan)
        assertEquals(at(friday, 13, 20), d.dhuhr!!.iqama) // absolute: stays
        assertEquals(at(friday, 16, 14), d.asr!!.iqama)
        assertEquals(at(friday, 20, 21), d.isha!!.adhan)
        assertEquals(at(friday, 13, 30), d.jumua.single().adhan) // not shifted
        assertEquals(paris, AdjustedTimes(base, List(6) { 0 }, null).zone)
    }

    @Test
    fun adjustedTimesIqamaOverride() {
        val base = times(mapOf(day to row), mapOf(day to listOf("+20", "13:20", "+10", "+5", "+10")))
        val d = AdjustedTimes(base, listOf(0, 0, 0, 0, 0, -5), listOf(15, 10, 10, 7, 12)).day(day)!!
        assertEquals(at(day, 6, 45), d.fajr!!.iqama)
        assertEquals(15, d.fajr!!.iqamaOffset)
        assertEquals(at(day, 13, 10), d.dhuhr!!.iqama)
        assertEquals(10, d.dhuhr!!.iqamaOffset)
        assertEquals(at(day, 20, 10), d.isha!!.adhan)
        assertEquals(at(day, 20, 22), d.isha!!.iqama)
        assertNull(d.shuruq!!.iqama)

        // Without a mosque iqama, the override still gives one
        val none = AdjustedTimes(times(mapOf(day to row)), List(6) { 0 }, listOf(15, 10, 10, 7, 12)).day(day)!!
        assertEquals(at(day, 18, 52), none.maghrib!!.iqama)
        // No change at all: the base day itself
        assertEquals(base.day(day), AdjustedTimes(base, List(6) { 0 }, null).day(day))
    }
}
