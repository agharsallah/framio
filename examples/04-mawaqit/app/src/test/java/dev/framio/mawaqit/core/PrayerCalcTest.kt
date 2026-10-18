package dev.framio.mawaqit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Reference times come from an independent Python port of PrayTimes.org v2.3 (wall time, rounded to the minute);
 * the Kotlin result must be within 2 minutes of them.
 */
class PrayerCalcTest {
    private val amsterdam = ZoneId.of("Europe/Amsterdam")

    private fun calc(
        date: String, lat: Double, lon: Double, zone: String, method: CalcMethod,
        hanafi: Boolean = false, highLat: HighLatRule = HighLatRule.ANGLE,
    ) = PrayerCalc.compute(LocalDate.parse(date), lat, lon, ZoneId.of(zone), method, hanafi, highLat)!!

    private fun ams(date: String, highLat: HighLatRule = HighLatRule.ANGLE, hanafi: Boolean = false) =
        calc(date, 52.3676, 4.9041, "Europe/Amsterdam", CalcMethod.MWL, hanafi, highLat)

    /** Asserts [actual]'s wall time is [expected] (HH:MM) ± 2 minutes, wrapping around midnight. */
    private fun near(expected: String, actual: ZonedDateTime?, label: String = expected) {
        assertNotNull(label, actual)
        val (h, m) = expected.split(':').map { it.toInt() }
        val diff = Math.floorMod(actual!!.hour * 60 + actual.minute - (h * 60 + m) + 720, 1440) - 720
        assertTrue("$label: expected $expected, got ${actual.toLocalTime()}", Math.abs(diff) <= 2)
    }

    private fun check(r: RawTimes, fajr: String?, sunrise: String, dhuhr: String, asr: String, maghrib: String, isha: String?) {
        if (fajr == null) assertNull(r.fajr) else near(fajr, r.fajr, "fajr")
        near(sunrise, r.sunrise, "sunrise")
        near(dhuhr, r.dhuhr, "dhuhr")
        near(asr, r.asr, "asr")
        near(maghrib, r.maghrib, "maghrib")
        if (isha == null) assertNull(r.isha) else near(isha, r.isha, "isha")
    }

    @Test
    fun makkahUmmAlQura() {
        val oct = calc("2026-10-18", 21.4225, 39.8262, "Asia/Riyadh", CalcMethod.MAKKAH)
        check(oct, "05:01", "06:17", "12:06", "15:26", "17:54", "19:24")
        near("00:06", oct.midnight)
        check(calc("2026-06-21", 21.4225, 39.8262, "Asia/Riyadh", CalcMethod.MAKKAH), "04:11", "05:39", "12:22", "15:42", "19:06", "20:36")
    }

    @Test
    fun makkahIshaIs120MinutesInRamadan() {
        val r = calc("2026-02-20", 21.4225, 39.8262, "Asia/Riyadh", CalcMethod.MAKKAH) // 4 Ramadan 1447
        near("18:21", r.maghrib)
        assertEquals(120, Duration.between(r.maghrib, r.isha).toMinutes())
    }

    @Test
    fun amsterdamSummerAndWinter() {
        val summer = ams("2026-07-15")
        check(summer, "03:18", "05:37", "13:46", "18:06", "21:56", "00:06")
        assertEquals(ZoneOffset.ofHours(2), summer.dhuhr.offset)
        assertEquals(LocalDate.parse("2026-07-16"), summer.isha!!.toLocalDate()) // after midnight
        val winter = ams("2026-01-15")
        check(winter, "06:40", "08:43", "12:50", "14:37", "16:57", "18:53")
        assertEquals(ZoneOffset.ofHours(1), winter.dhuhr.offset)
        near("00:50", winter.midnight)
    }

    @Test
    fun hanafiAsrIsLater() {
        near("15:09", ams("2026-01-15", hanafi = true).asr)
    }

    @Test
    fun otherMethods() {
        check(calc("2026-10-18", 48.8566, 2.3522, "Europe/Paris", CalcMethod.FRANCE), "07:07", "08:15", "13:36", "16:24", "18:55", "20:04")
        val tehran = calc("2026-10-18", 35.6892, 51.3890, "Asia/Tehran", CalcMethod.TEHRAN)
        check(tehran, "04:50", "06:13", "11:50", "14:59", "17:44", "18:31")
        near("17:25", tehran.sunset)
        near("23:08", tehran.midnight) // Jafari: half-way between sunset and Fajr
        check(calc("2026-10-18", 40.7128, -74.0060, "America/New_York", CalcMethod.ISNA), "05:55", "07:11", "12:41", "15:43", "18:11", "19:26")
        check(calc("2026-10-18", 30.0444, 31.2357, "Africa/Cairo", CalcMethod.EGYPT), "05:32", "06:59", "12:40", "15:55", "18:21", "19:39")
    }

    @Test
    fun highLatitudeRulesInJune() {
        // At 52°N the sun never gets 17-18° below the horizon around the solstice.
        check(ams("2026-06-21", HighLatRule.ANGLE), "03:09", "05:18", "13:42", "18:07", "22:06", "00:09")
        check(ams("2026-06-21", HighLatRule.MIDDLE), "01:42", "05:18", "13:42", "18:07", "22:06", "01:42")
        check(ams("2026-06-21", HighLatRule.SEVENTH), "04:16", "05:18", "13:42", "18:07", "22:06", "23:08")
        check(ams("2026-06-21", HighLatRule.NONE), null, "05:18", "13:42", "18:07", "22:06", null)
        val oslo = { rule: HighLatRule -> calc("2026-06-21", 59.9139, 10.7522, "Europe/Oslo", CalcMethod.MWL, highLat = rule) }
        check(oslo(HighLatRule.ANGLE), "02:21", "03:54", "13:19", "18:00", "22:44", "00:12")
        check(oslo(HighLatRule.NONE), null, "03:54", "13:19", "18:00", "22:44", null)
    }

    @Test
    fun polarNightAndMidnightSunDoNotCrash() {
        val night = calc("2026-12-21", 69.6492, 18.9553, "Europe/Oslo", CalcMethod.MWL)
        assertNull(night.sunrise)
        assertNull(night.sunset)
        assertNull(night.maghrib)
        near("11:42", night.dhuhr)
        val day = calc("2026-06-21", 69.6492, 18.9553, "Europe/Oslo", CalcMethod.MWL)
        assertNull(day.sunrise)
        assertNull(day.isha)
        val s = SourceSettings(latitude = 69.6492, longitude = 18.9553)
        val calcDay = CalcTimes(s, ZoneId.of("Europe/Oslo")).day(LocalDate.parse("2026-12-21"))!!
        assertNull(calcDay.shuruq)
        assertNotNull(calcDay.dhuhr)
    }

    @Test
    fun invalidPositionGivesNull() {
        assertNull(PrayerCalc.compute(LocalDate.parse("2026-01-01"), Double.NaN, 0.0, amsterdam, CalcMethod.MWL))
        assertNull(PrayerCalc.compute(LocalDate.parse("2026-01-01"), 95.0, 0.0, amsterdam, CalcMethod.MWL))
        assertNull(CalcTimes(SourceSettings(latitude = 0.0, longitude = 200.0), amsterdam).day(LocalDate.parse("2026-01-01")))
    }

    @Test
    fun dstTransitionDays() {
        // Spring forward on 29 March 2026, fall back on 25 October 2026.
        check(ams("2026-03-28"), "04:25", "06:24", "12:45", "16:13", "19:07", "20:59")
        val march = ams("2026-03-29")
        check(march, "05:22", "07:22", "13:45", "17:14", "20:09", "22:02")
        assertEquals(ZoneOffset.ofHours(1), ams("2026-03-28").dhuhr.offset)
        assertEquals(ZoneOffset.ofHours(2), march.fajr!!.offset)
        check(ams("2026-10-24"), "06:27", "08:21", "13:25", "15:56", "18:27", "20:14")
        val october = ams("2026-10-25")
        check(october, "05:29", "07:23", "12:24", "14:54", "17:25", "19:12")
        assertEquals(ZoneOffset.ofHours(1), october.fajr!!.offset)
    }

    @Test
    fun orderOverAWholeYear() {
        val places = listOf(
            Triple(52.3676, 4.9041, "Europe/Amsterdam"),
            Triple(21.4225, 39.8262, "Asia/Riyadh"),
            Triple(40.7128, -74.0060, "America/New_York"),
            Triple(59.9139, 10.7522, "Europe/Oslo"),
            Triple(-33.8688, 151.2093, "Australia/Sydney"),
            Triple(-6.2088, 106.8456, "Asia/Jakarta"),
        )
        for ((lat, lon, zone) in places) for (method in CalcMethod.entries) {
            var date = LocalDate.parse("2026-01-01")
            while (date.year == 2026) {
                val r = PrayerCalc.compute(date, lat, lon, ZoneId.of(zone), method)!!
                val h = PrayerCalc.compute(date, lat, lon, ZoneId.of(zone), method, hanafi = true)!!
                val label = "$zone $method $date"
                val times = listOf(r.fajr, r.sunrise, r.dhuhr, r.asr, r.sunset, r.maghrib, r.isha, r.midnight)
                assertTrue("$label: missing time $r", times.all { it != null })
                val fajr = r.fajr!!; val sunrise = r.sunrise!!; val dhuhr = r.dhuhr; val asr = r.asr!!
                val sunset = r.sunset!!; val maghrib = r.maghrib!!; val isha = r.isha!!
                assertTrue("$label: fajr < sunrise", fajr < sunrise)
                assertTrue("$label: sunrise < dhuhr", sunrise < dhuhr)
                assertTrue("$label: dhuhr < asr", dhuhr < asr)
                assertTrue("$label: asr < sunset", asr < sunset)
                assertTrue("$label: sunset <= maghrib", sunset <= maghrib)
                assertTrue("$label: maghrib < isha", maghrib < isha)
                // With the angle rule Isha stays within the night, before the next sunrise.
                assertTrue("$label: isha < next sunrise", isha < sunrise.plusDays(1))
                assertTrue("$label: hanafi asr later", h.asr!! > asr && h.asr < h.sunset!!)
                assertEquals(label, 0, r.dhuhr.second)
                date = date.plusDays(1)
            }
        }
    }

    @Test
    fun methodAndRuleIds() {
        assertEquals(CalcMethod.MWL, CalcMethod.of("unknown"))
        for (m in CalcMethod.entries) assertEquals(m, CalcMethod.of(m.id))
        assertEquals(CalcMethod.MAKKAH, CalcMethod.of("MAKKAH"))
        assertEquals(HighLatRule.SEVENTH, HighLatRule.of("seventh"))
        assertEquals(HighLatRule.ANGLE, HighLatRule.of("?"))
    }

    // CalcTimes ------------------------------------------------------------------------------------------------------

    private val friday = LocalDate.parse("2026-10-16") // Fajr 06:14, Shuruq 08:07, Dhuhr 13:26, Asr 16:09, Maghrib 18:44, Isha 20:30

    @Test
    fun calcTimesAppliesOffsetsAndIqama() {
        val s = SourceSettings(offsets = listOf(1, -2, 3, 0, 2, -1), iqama = listOf(20, 10, 15, 5, 0))
        val day = CalcTimes(s, amsterdam).day(friday)!!
        val plain = CalcTimes(SourceSettings(), amsterdam).day(friday)!!
        near("06:15", day.fajr!!.adhan)
        assertEquals(1, Duration.between(plain.fajr!!.adhan, day.fajr.adhan).toMinutes())
        assertEquals(-2, Duration.between(plain.shuruq!!.adhan, day.shuruq!!.adhan).toMinutes())
        assertEquals(3, Duration.between(plain.dhuhr!!.adhan, day.dhuhr!!.adhan).toMinutes())
        assertEquals(2, Duration.between(plain.maghrib!!.adhan, day.maghrib!!.adhan).toMinutes())
        assertEquals(-1, Duration.between(plain.isha!!.adhan, day.isha!!.adhan).toMinutes())
        assertEquals(day.fajr.adhan.minusMinutes(10), day.imsak!!.adhan)
        assertEquals(PrayerKey.IMSAK, day.imsak.key)
        assertNull(day.imsak.iqama)
        assertNull(day.shuruq.iqama)
        assertEquals(20, day.fajr.iqamaOffset)
        assertEquals(day.fajr.adhan.plusMinutes(20), day.fajr.iqama)
        assertEquals(day.dhuhr.adhan.plusMinutes(10), day.dhuhr.iqama)
        assertEquals(day.asr!!.adhan.plusMinutes(15), day.asr.iqama)
        assertEquals(day.maghrib.adhan.plusMinutes(5), day.maghrib.iqama)
        assertEquals(day.isha.adhan, day.isha.iqama)
        assertEquals(listOf(PrayerKey.FAJR, PrayerKey.DHUHR, PrayerKey.ASR, PrayerKey.MAGHRIB, PrayerKey.ISHA), day.five.map { it!!.key })
        assertTrue(day.eid.isEmpty())
    }

    @Test
    fun calcTimesJumua() {
        val atTime = CalcTimes(SourceSettings(jumua = "13:30"), amsterdam).day(friday)!!
        assertEquals(1, atTime.jumua.size)
        assertEquals(ZonedDateTime.of(friday.atTime(13, 30), amsterdam), atTime.jumua[0].adhan)
        assertEquals(PrayerKey.JUMUA, atTime.jumua[0].key)
        assertNull(atTime.jumua[0].iqama)

        for (bad in listOf("", "25:00", "noon", "13")) {
            val atDhuhr = CalcTimes(SourceSettings(jumua = bad, offsets = listOf(0, 0, 4, 0, 0, 0)), amsterdam).day(friday)!!
            assertEquals(bad, atDhuhr.dhuhr!!.adhan, atDhuhr.jumua.single().adhan)
        }
        assertTrue(CalcTimes(SourceSettings(jumua = "13:30"), amsterdam).day(friday.plusDays(1))!!.jumua.isEmpty())
    }

    @Test
    fun calcTimesUsesSettingsAndCaches() {
        val s = SourceSettings(latitude = 21.4225, longitude = 39.8262, method = "MAKKAH", asr = "hanafi")
        val times = CalcTimes(s, ZoneId.of("Asia/Riyadh"))
        val day = times.day(LocalDate.parse("2026-10-18"))!!
        val raw = calc("2026-10-18", 21.4225, 39.8262, "Asia/Riyadh", CalcMethod.MAKKAH, hanafi = true)
        assertEquals(raw.asr, day.asr!!.adhan)
        assertEquals(90, Duration.between(day.maghrib!!.adhan, day.isha!!.adhan).toMinutes())
        assertSame(day, times.day(LocalDate.parse("2026-10-18")))
    }
}
