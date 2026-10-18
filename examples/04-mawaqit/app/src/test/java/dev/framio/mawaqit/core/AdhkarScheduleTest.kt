package dev.framio.mawaqit.core

import dev.framio.mawaqit.core.AdhkarCategory.EVENING
import dev.framio.mawaqit.core.AdhkarCategory.MORNING
import dev.framio.mawaqit.core.AdhkarCategory.SLEEP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class AdhkarScheduleTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val day = LocalDate.of(2026, 10, 20)
    private val row = listOf("06:30", "08:00", "13:00", "16:00", "18:45", "20:15")
    private val iqama = listOf("+20", "+10", "+10", "+5", "+10")
    private val b = Behavior() // adhan 150 s, dua 30 s, iqama 45 s, prayer 10/8/8/8/9 min, azkar 5 min
    private val s = AdhkarSettings(schedules = AdhkarReminders.legacy(null) + AdhkarTime("sleep", SLEEP, "22:30"))
    private val never: (AdhkarCategory, LocalDate) -> Boolean = { _, _ -> false }

    private fun everyDay(row: List<String>, iqama: List<String>? = null): TimesSource {
        val cal = List(12) { (1..31).associateWith { row } }
        val iq = List(12) { (1..31).associateWith { iqama ?: emptyList() } }
        return MawaqitTimes(cal, iq, iqama != null, null, null, null, false, 0, paris)
    }

    private fun at(date: LocalDate, h: Int, m: Int) = ZonedDateTime.of(date, LocalTime.of(h, m), paris)
    private fun at(h: Int, m: Int) = at(day, h, m)

    private val times = everyDay(row, iqama)

    private fun sleepAt(hhmm: String) = s.copy(schedules = AdhkarReminders.legacy(null) + AdhkarTime("sleep", SLEEP, hhmm))

    /** The card shown at [now], as the frame computes it (data/AdhkarScheduler). */
    private fun reminderAt(now: ZonedDateTime, settings: AdhkarSettings = s, source: TimesSource = times,
                           done: (AdhkarCategory, LocalDate) -> Boolean = never,
                           dismissed: (AdhkarCategory, LocalDate) -> Boolean = never): AdhkarCategory? {
        if (!settings.enabled) return null
        val today = now.toLocalDate()
        val windows = AdhkarSchedule.windows(today.minusDays(1), source, b, settings) + AdhkarSchedule.windows(today, source, b, settings)
        val dues = AdhkarReminders.occurrences(today.minusDays(1), settings.schedules, source, b, windows) +
            AdhkarReminders.occurrences(today, settings.schedules, source, b, windows)
        return AdhkarReminders.showing(now, dues) { !done(it.category, it.day) && !dismissed(it.category, it.day) }?.category
    }

    @Test
    fun windowsOfAnOrdinaryDay() {
        val w = AdhkarSchedule.windows(day, times, b, s).associateBy { it.category }
        assertEquals(at(6, 30), w.getValue(MORNING).start)
        assertEquals(at(11, 0), w.getValue(MORNING).end) // Shuruq 08:00 + 3 h
        assertEquals(at(16, 0), w.getValue(EVENING).start)
        assertEquals(at(19, 45), w.getValue(EVENING).end) // Maghrib + 1 h
        assertEquals(at(22, 30), w.getValue(SLEEP).start)
        assertEquals(at(day.plusDays(1), 1, 30), w.getValue(SLEEP).end)
        assertNull(AdhkarSchedule.windows(day, times, b, s.copy(schedules = AdhkarReminders.legacy(null))).firstOrNull { it.category == SLEEP })
        // the default schedules: as the reminders were before them
        val due = AdhkarReminders.occurrences(day, s.schedules, times, b, AdhkarSchedule.windows(day, times, b, s)).associateBy { it.category }
        // Fajr 06:30, iqama 06:50 → prayer until 07:00, azkar until 07:05; reminder 20 min later, until the window's end
        assertEquals(at(7, 25), due.getValue(MORNING).at)
        assertEquals(at(11, 0), due.getValue(MORNING).until)
        // Asr 16:00, iqama 16:10, prayer 8 min → 16:18, azkar → 16:23; reminder 15 min later
        assertEquals(at(16, 38), due.getValue(EVENING).at)
        assertEquals(at(19, 45), due.getValue(EVENING).until)
        assertEquals(at(day.plusDays(1), 1, 30), due.getValue(SLEEP).until)
    }

    @Test
    fun reminderOnlyInsideTheReminderPart() {
        assertNull(reminderAt(at(7, 24)))
        assertEquals(MORNING, reminderAt(at(7, 25)))
        assertEquals(MORNING, reminderAt(at(10, 59)))
        assertNull(reminderAt(at(11, 0)))
        assertNull(reminderAt(at(16, 37)))
        assertEquals(EVENING, reminderAt(at(16, 38)))
        assertEquals(SLEEP, reminderAt(at(23, 0)))
        // the highlight starts with the prayer, the reminder after its screens
        assertEquals(MORNING, AdhkarSchedule.current(AdhkarSchedule.open(at(6, 40), times, b, s)))
        assertNull(AdhkarSchedule.current(AdhkarSchedule.open(at(12, 0), times, b, s)))
    }

    @Test
    fun noReminderWhenDoneDismissedOrOff() {
        val now = at(8, 0)
        assertNull(reminderAt(now, done = { c, d -> c == MORNING && d == day }))
        assertNull(reminderAt(now, dismissed = { c, d -> c == MORNING && d == day }))
        assertEquals(MORNING, reminderAt(now, dismissed = { c, d -> c == MORNING && d == day.minusDays(1) })) // yesterday's
        assertNull(reminderAt(now, s.copy(schedules = s.schedules.map { if (it.category == MORNING) it.copy(enabled = false) else it })))
        assertNull(reminderAt(now, s.copy(enabled = false)))
        assertNull(reminderAt(at(17, 0), s.copy(schedules = s.schedules.filter { it.category != EVENING })))
    }

    @Test
    fun aScreenlessPrayerRemindsFromTheAdhan() {
        val bare = b.copy(adhanScreen = false, duaAfterAdhan = false, iqamaCountdown = false, azkar = false, prayerMinutes = List(5) { 0 })
        val t = AdhkarTime("m", MORNING, "fajr+0")
        assertEquals(at(6, 30), AdhkarReminders.resolve(t, day, everyDay(row), bare))
    }

    @Test
    fun dstDays() {
        // 29 March 2026: Paris skips 02:00–03:00. A sleep time in the gap moves to after it; 3 h stay 3 h.
        val spring = LocalDate.of(2026, 3, 29)
        val sleep = AdhkarSchedule.windows(spring, times, b, sleepAt("02:30")).first { it.category == SLEEP }
        assertEquals(at(spring, 3, 30), sleep.start)
        assertEquals(Duration.ofHours(3), Duration.between(sleep.start, sleep.end))
        val morning = AdhkarReminders.resolve(s.schedules.first { it.category == MORNING }, spring, times, b)
        assertEquals(at(spring, 7, 25), morning) // local times as on any day
        // 25 October 2026: 03:00 → 02:00. 01:00 + 3 real hours is 03:00 on the clock that went back.
        val autumn = LocalDate.of(2026, 10, 25)
        val late = AdhkarSchedule.windows(autumn, times, b, sleepAt("01:00")).first { it.category == SLEEP }
        assertEquals(at(autumn, 3, 0), late.end)
        assertEquals(Duration.ofHours(3), Duration.between(late.start, late.end))
        assertEquals(SLEEP, reminderAt(at(autumn, 2, 30), sleepAt("01:00")))
    }

    @Test
    fun ishaAfterMidnight() {
        // Summer: Isha at 00:20 the next day. Sleep reminder 23:00 → 02:00 belongs to the day it started.
        val summer = LocalDate.of(2026, 6, 20)
        val src = everyDay(listOf("03:00", "04:30", "13:30", "18:00", "22:40", "00:20"))
        val settings = sleepAt("23:00")
        val now = at(summer.plusDays(1), 0, 30)
        val open = AdhkarSchedule.open(now, src, b, settings)
        assertEquals(listOf(SLEEP), open.map { it.category })
        assertEquals(summer, AdhkarSchedule.progressDay(SLEEP, now, open))
        assertEquals(summer.plusDays(1), AdhkarSchedule.progressDay(MORNING, now, open))
        assertEquals(SLEEP, reminderAt(now, settings, src))
        assertNull(reminderAt(now, settings, src, done = { c, d -> c == SLEEP && d == summer }))
        assertEquals(SLEEP, reminderAt(now, settings, src, done = { c, d -> c == SLEEP && d == summer.plusDays(1) }))
        // the evening window (Maghrib 22:40 + 1 h) is unaffected
        assertEquals(at(summer, 23, 40), AdhkarSchedule.windows(summer, src, b, settings).first { it.category == EVENING }.end)
        // Isha (yesterday's) is the last prayer until Fajr
        assertEquals(PrayerKey.ISHA, AdhkarSchedule.lastPrayer(at(summer.plusDays(1), 2, 0), src))
        assertEquals(PrayerKey.FAJR, AdhkarSchedule.lastPrayer(at(summer.plusDays(1), 3, 1), src))
    }

    @Test
    fun lastPrayerOfTheDay() {
        assertEquals(PrayerKey.ISHA, AdhkarSchedule.lastPrayer(at(5, 0), times))
        assertEquals(PrayerKey.DHUHR, AdhkarSchedule.lastPrayer(at(13, 0), times))
        assertEquals(PrayerKey.ASR, AdhkarSchedule.lastPrayer(at(18, 44), times))
    }

    @Test
    fun sleepTimeParsing() {
        assertEquals(LocalTime.of(22, 5), AdhkarSchedule.sleepTime("22:05"))
        assertEquals(LocalTime.of(7, 0), AdhkarSchedule.sleepTime("7:00"))
        assertNull(AdhkarSchedule.sleepTime(""))
        assertNull(AdhkarSchedule.sleepTime("25:00"))
    }
}
