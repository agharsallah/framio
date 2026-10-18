package dev.framio.mawaqit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SecondaryTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val times = MawaqitPage.parse(javaClass.classLoader!!.getResource("paris.html")!!.readText(), "grande-mosquee-de-paris").times

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = ZonedDateTime.of(y, m, d, h, min, 0, 0, paris)

    @Test
    fun slugsSkipTheMainMosque() {
        val s = SourceSettings(mosque = "a", secondary = listOf("b", "a", "c"))
        assertEquals(listOf("b", "c"), Secondary.slugs(s))
        assertEquals(emptyList<String>(), Secondary.slugs(SourceSettings(mosque = "a")))
    }

    @Test
    fun settingsKeepAtMostThreeDistinctSlugs() {
        val j = org.json.JSONObject("""{"secondary":["https://mawaqit.net/de/hassan-moschee-frankfurt-am-main?x","b","b","","c","d"]}""")
        assertEquals(listOf("hassan-moschee-frankfurt-am-main", "b", "c"), SourceSettings.fromJson(j).secondary)
    }

    @Test
    fun retriesBackOffUpToAnHour() {
        assertEquals(0L, Secondary.retryDelayMs(0))
        assertEquals(120_000L, Secondary.retryDelayMs(1))
        assertEquals(240_000L, Secondary.retryDelayMs(2))
        assertEquals(1_920_000L, Secondary.retryDelayMs(5))
        assertEquals(3_600_000L, Secondary.retryDelayMs(6))
        assertEquals(3_600_000L, Secondary.retryDelayMs(40))
    }

    @Test
    fun fetchesNeverLoadedNowEverySixHoursAndSoonerAfterAFailure() {
        assertEquals(0L, Secondary.nextAttempt(updatedAt = null, failures = 0, lastTry = 0L))
        assertEquals(1_000L + 6 * 3_600_000L, Secondary.nextAttempt(updatedAt = 1_000L, failures = 0, lastTry = 5_000L))
        // A failed reload of a cached mosque: the cached copy's age doesn't matter, the backoff does
        assertEquals(5_000L + 240_000L, Secondary.nextAttempt(updatedAt = 1_000L, failures = 2, lastTry = 5_000L))
    }

    @Test
    fun entryHoldsTodayTomorrowAndTheNextPrayer() {
        val now = at(2026, 1, 1, 10, 0).toInstant()
        val e = Secondary.entry("paris", "GRANDE MOSQUÉE DE PARIS", times, now, updatedAt = 42L, error = null, loading = false)
        assertEquals(LocalDate.of(2026, 1, 1), e.today!!.date)
        assertEquals(LocalDate.of(2026, 1, 2), e.tomorrow!!.date)
        assertEquals(PrayerKey.DHUHR, e.next!!.key)
        assertEquals(e.today!!.dhuhr, e.next)
        assertEquals(42L, e.updatedAt)
        // Thursday 1 January: the Jumu'a of Friday 2 January
        assertTrue(e.jumua.isNotEmpty())
        assertEquals(LocalDate.of(2026, 1, 2), e.jumua.first().adhan.toLocalDate())
    }

    @Test
    fun entryWithoutTimesKeepsItsNameAndError() {
        val e = Secondary.entry("x", "x", null, at(2026, 1, 1, 10, 0).toInstant(), null, "not_found", loading = false)
        assertNull(e.today)
        assertNull(e.next)
        assertEquals("not_found", e.error)
        assertTrue(e.jumua.isEmpty())
    }

    @Test
    fun jumuaIsTodaysOnAFridayElseTheComingFridays() {
        val friday = LocalDate.of(2026, 10, 23)
        assertEquals(listOf("13:50", "14:30"), Secondary.jumua(times, friday).map { it.adhan.toLocalTime().toString() })
        assertEquals(friday, Secondary.jumua(times, LocalDate.of(2026, 10, 18)).first().adhan.toLocalDate())
        assertEquals(LocalDate.of(2026, 10, 30), Secondary.jumua(times, LocalDate.of(2026, 10, 24)).first().adhan.toLocalDate())
        assertTrue(Secondary.jumua(null, friday).isEmpty())
    }

    @Test
    fun highlightsTheNextPrayersCell() {
        val day = times.day(LocalDate.of(2026, 1, 1))!!
        assertEquals(0, Secondary.highlight(day, emptyList(), day.fajr))
        assertEquals(4, Secondary.highlight(day, emptyList(), day.isha))
        // After Isha the next prayer is tomorrow's Fajr: nothing lit today
        assertEquals(-1, Secondary.highlight(day, emptyList(), times.day(LocalDate.of(2026, 1, 2))!!.fajr))
        assertEquals(-1, Secondary.highlight(null, emptyList(), day.fajr))
        assertEquals(-1, Secondary.highlight(day, emptyList(), null))
    }

    @Test
    fun onAFridayTheJumuaColumnIsLit() {
        val friday = LocalDate.of(2026, 10, 23)
        val day = times.day(friday)!!
        val next = Timeline.nextPrayer(at(2026, 10, 23, 12, 0), times)!!
        assertEquals(PrayerKey.JUMUA, next.key)
        assertEquals(5, Secondary.highlight(day, Secondary.jumua(times, friday), next))
        // A Jumu'a "at Dhuhr" has Dhuhr's time but still lights the Jumu'a column
        val atDhuhr = PrayerTime(PrayerKey.JUMUA, day.dhuhr!!.adhan)
        assertEquals(5, Secondary.highlight(day, listOf(atDhuhr), atDhuhr))
    }
}
