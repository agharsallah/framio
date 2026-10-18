package dev.framio.mawaqit.core

import dev.framio.mawaqit.core.PhaseKind.ADHAN
import dev.framio.mawaqit.core.PhaseKind.AZKAR
import dev.framio.mawaqit.core.PhaseKind.DUA_AFTER_ADHAN
import dev.framio.mawaqit.core.PhaseKind.IQAMA
import dev.framio.mawaqit.core.PhaseKind.IQAMA_COUNTDOWN
import dev.framio.mawaqit.core.PhaseKind.JUMUA
import dev.framio.mawaqit.core.PhaseKind.NIGHT
import dev.framio.mawaqit.core.PhaseKind.NORMAL
import dev.framio.mawaqit.core.PhaseKind.PRAYER
import dev.framio.mawaqit.core.PhaseKind.REMINDER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class TimelineTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val day = LocalDate.of(2026, 10, 20) // a Tuesday
    private val friday = LocalDate.of(2026, 10, 23)
    private val row = listOf("06:30", "08:00", "13:00", "16:00", "18:45", "20:15")
    private val iqama = listOf("+20", "+10", "+10", "+5", "+10")
    private val b = Behavior() // adhan 150 s, dua 30 s, iqama 45 s, prayer 10/8/8/8/9 min, azkar 5 min

    /** The same times every day of the year. */
    private fun everyDay(row: List<String>, iqama: List<String>? = null, jumua: String? = null): TimesSource {
        val cal = List(12) { (1..31).associateWith { row } }
        val iq = List(12) { (1..31).associateWith { iqama ?: emptyList() } }
        return MawaqitTimes(cal, iq, iqama != null, jumua, null, null, false, 0, paris)
    }

    private fun at(date: LocalDate, h: Int, m: Int, s: Int = 0) = ZonedDateTime.of(date, LocalTime.of(h, m, s), paris)
    private fun at(h: Int, m: Int, s: Int = 0) = at(day, h, m, s)

    /** Asserts the phase at [now] and its bounds. */
    private fun assertPhase(
        kind: PhaseKind, start: ZonedDateTime?, end: ZonedDateTime?, now: ZonedDateTime,
        source: TimesSource, behavior: Behavior = b, prayer: PrayerKey? = null,
    ) {
        val p = Timeline.phaseAt(now, source, behavior)
        assertEquals("kind at $now", kind, p.kind)
        assertEquals("start at $now", start, p.start)
        assertEquals("end at $now", end, p.end)
        if (prayer != null) assertEquals(prayer, p.prayer!!.key)
    }

    @Test
    fun prayerWithIqamaGoesThroughEveryPhase() {
        val s = everyDay(row, iqama)
        assertPhase(NORMAL, at(7, 5), at(13, 0), at(12, 59, 59), s)
        assertPhase(ADHAN, at(13, 0), at(13, 2, 30), at(13, 0), s, prayer = PrayerKey.DHUHR)
        assertPhase(ADHAN, at(13, 0), at(13, 2, 30), at(13, 2, 29), s)
        assertPhase(DUA_AFTER_ADHAN, at(13, 2, 30), at(13, 3), at(13, 2, 30), s, prayer = PrayerKey.DHUHR)
        assertPhase(IQAMA_COUNTDOWN, at(13, 3), at(13, 10), at(13, 3), s)
        assertPhase(IQAMA_COUNTDOWN, at(13, 3), at(13, 10), at(13, 9, 59), s)
        assertPhase(IQAMA, at(13, 10), at(13, 10, 45), at(13, 10), s, prayer = PrayerKey.DHUHR)
        assertPhase(PRAYER, at(13, 10, 45), at(13, 18), at(13, 10, 45), s)
        assertPhase(AZKAR, at(13, 18), at(13, 23), at(13, 18), s, prayer = PrayerKey.DHUHR)
        assertPhase(NORMAL, at(13, 23), at(16, 0), at(13, 23), s)
        assertNull(Timeline.phaseAt(at(13, 23), s, b).prayer)
    }

    @Test
    fun prayerWithoutIqamaPraysAfterTheDua() {
        val s = everyDay(row)
        assertPhase(DUA_AFTER_ADHAN, at(13, 2, 30), at(13, 3), at(13, 2, 45), s)
        assertPhase(PRAYER, at(13, 3), at(13, 11), at(13, 3), s, prayer = PrayerKey.DHUHR)
        assertPhase(AZKAR, at(13, 11), at(13, 16), at(13, 15, 59), s)
        assertPhase(NORMAL, at(13, 16), at(16, 0), at(13, 16), s)
    }

    @Test
    fun disabledScreensAreSkipped() {
        val s = everyDay(row, iqama)
        val bare = b.copy(adhanScreen = false, duaAfterAdhan = false, iqamaCountdown = false, azkar = false)
        assertPhase(NORMAL, at(7, 0), at(13, 10), at(13, 0), s, bare)
        assertPhase(IQAMA, at(13, 10), at(13, 10, 45), at(13, 10), s, bare)
        assertPhase(PRAYER, at(13, 10, 45), at(13, 18), at(13, 17), s, bare)
        assertPhase(NORMAL, at(13, 18), at(16, 10), at(13, 18), s, bare) // to Asr's iqama
    }

    @Test
    fun iqamaDuringTheAdhanScreensComesAfterThem() {
        val s = everyDay(row, listOf("+20", "+10", "+10", "+0", "+10")) // Maghrib +0
        assertPhase(DUA_AFTER_ADHAN, at(18, 47, 30), at(18, 48), at(18, 47, 30), s)
        assertPhase(IQAMA, at(18, 48), at(18, 48, 45), at(18, 48), s, prayer = PrayerKey.MAGHRIB)
        assertPhase(PRAYER, at(18, 48, 45), at(18, 56), at(18, 50), s)
    }

    @Test
    fun reminderBeforeEachAdhan() {
        val s = everyDay(row, iqama)
        val r = b.copy(reminderMinutes = 10)
        assertPhase(NORMAL, at(7, 5), at(12, 50), at(12, 49), s, r)
        assertPhase(REMINDER, at(12, 50), at(13, 0), at(12, 50), s, r, PrayerKey.DHUHR)
        assertPhase(ADHAN, at(13, 0), at(13, 2, 30), at(13, 0), s, r)
    }

    @Test
    fun fridayJumuaReplacesTheDhuhrFlow() {
        val s = everyDay(row, iqama, jumua = "13:30")
        assertPhase(NORMAL, at(friday, 7, 5), at(friday, 13, 30), at(friday, 13, 0), s)
        assertPhase(JUMUA, at(friday, 13, 30), at(friday, 14, 10), at(friday, 13, 30), s, prayer = PrayerKey.JUMUA)
        assertPhase(AZKAR, at(friday, 14, 10), at(friday, 14, 15), at(friday, 14, 12), s, prayer = PrayerKey.JUMUA)
        assertPhase(NORMAL, at(friday, 14, 15), at(friday, 16, 0), at(friday, 14, 15), s)
        // The other days keep Dhuhr
        assertPhase(ADHAN, at(13, 0), at(13, 2, 30), at(13, 1), s)
    }

    @Test
    fun nightAcrossMidnight() {
        val s = everyDay(row)
        val n = b.copy(nightMode = NightMode.DIM, nightAfterIshaMinutes = 60, nightBeforeFajrMinutes = 30)
        val tomorrow = day.plusDays(1)
        assertPhase(NORMAL, at(20, 15 + 3 + 9 + 5), at(21, 15), at(21, 0), s, n)
        assertPhase(NIGHT, at(21, 15), at(tomorrow, 6, 0), at(21, 15), s, n)
        assertPhase(NIGHT, at(21, 15), at(tomorrow, 6, 0), at(23, 59, 59), s, n)
        assertPhase(NIGHT, at(21, 15), at(tomorrow, 6, 0), at(tomorrow, 0, 0), s, n)
        assertPhase(NIGHT, at(21, 15), at(tomorrow, 6, 0), at(tomorrow, 5, 59, 59), s, n)
        assertPhase(NORMAL, at(tomorrow, 6, 0), at(tomorrow, 6, 30), at(tomorrow, 6, 0), s, n)
        assertNull(Timeline.phaseAt(at(23, 0), s, n).prayer)

        // Off: the same hours are NORMAL
        assertPhase(NORMAL, at(20, 15 + 3 + 9 + 5), at(tomorrow, 6, 30), at(tomorrow, 1, 0), s)
    }

    @Test
    fun nightStartsAfterTheIshaFlowAndEndsBeforeFajrs() {
        val s = everyDay(row, iqama)
        val n = b.copy(nightMode = NightMode.BLACK, nightAfterIshaMinutes = 5, nightBeforeFajrMinutes = 30, reminderMinutes = 15)
        val tomorrow = day.plusDays(1)
        // Isha flow: reminder from 20:00, adhan 20:15, iqama 20:25, prayer until 20:34, azkar until 20:39
        assertPhase(AZKAR, at(20, 34), at(20, 39), at(20, 35), s, n)
        assertPhase(NIGHT, at(20, 39), at(tomorrow, 6, 0), at(20, 39), s, n)
        assertPhase(NORMAL, at(tomorrow, 6, 0), at(tomorrow, 6, 15), at(tomorrow, 6, 10), s, n)
        assertPhase(REMINDER, at(tomorrow, 6, 15), at(tomorrow, 6, 30), at(tomorrow, 6, 20), s, n, PrayerKey.FAJR)

        // Ending after the Fajr reminder starts: cut there
        val late = n.copy(nightBeforeFajrMinutes = 5)
        assertPhase(NIGHT, at(20, 39), at(tomorrow, 6, 15), at(tomorrow, 6, 14), s, late)
        assertPhase(REMINDER, at(tomorrow, 6, 15), at(tomorrow, 6, 30), at(tomorrow, 6, 15), s, late)
    }

    @Test
    fun overlappingFlowsAreCutAtTheNextPrayer() {
        val close = listOf("06:30", "08:00", "13:00", "13:15", "18:45", "20:15")
        val s = everyDay(close, iqama)
        assertPhase(PRAYER, at(13, 10, 45), at(13, 15), at(13, 14), s)
        assertPhase(ADHAN, at(13, 15), at(13, 17, 30), at(13, 15), s, prayer = PrayerKey.ASR)

        val r = b.copy(reminderMinutes = 10)
        assertPhase(IQAMA_COUNTDOWN, at(13, 3), at(13, 5), at(13, 4), s, r)
        assertPhase(REMINDER, at(13, 5), at(13, 15), at(13, 10), s, r, PrayerKey.ASR)
    }

    @Test
    fun ishaAfterMidnightBelongsToTheDayBefore() {
        val s = everyDay(listOf("03:30", "05:30", "13:45", "18:00", "22:40", "00:20"))
        val tomorrow = day.plusDays(1)
        assertPhase(ADHAN, at(tomorrow, 0, 20), at(tomorrow, 0, 22, 30), at(tomorrow, 0, 21), s, prayer = PrayerKey.ISHA)
        assertEquals(at(tomorrow, 0, 20), Timeline.nextPrayer(at(23, 30), s)!!.adhan)
    }

    @Test
    fun nextPrayer() {
        val s = everyDay(row, iqama, jumua = "13:30")
        assertEquals(at(13, 0), Timeline.nextPrayer(at(6, 30), s)!!.adhan)
        assertEquals(PrayerKey.DHUHR, Timeline.nextPrayer(at(12, 0), s)!!.key)
        val afterIsha = Timeline.nextPrayer(at(20, 15), s)!!
        assertEquals(PrayerKey.FAJR, afterIsha.key)
        assertEquals(at(day.plusDays(1), 6, 30), afterIsha.adhan)

        val onFriday = Timeline.nextPrayer(at(friday, 12, 0), s)!!
        assertEquals(PrayerKey.JUMUA, onFriday.key)
        assertEquals(at(friday, 13, 30), onFriday.adhan)
        assertEquals(PrayerKey.ASR, Timeline.nextPrayer(at(friday, 13, 30), s)!!.key)

        // Another zone for "now" is the same instant
        assertEquals(at(13, 0), Timeline.nextPrayer(at(12, 0).withZoneSameInstant(ZoneId.of("UTC")), s)!!.adhan)
        assertNull(Timeline.nextPrayer(at(12, 0), MawaqitTimes(emptyList(), emptyList(), false, null, null, null, false, 0, paris)))
    }
}
