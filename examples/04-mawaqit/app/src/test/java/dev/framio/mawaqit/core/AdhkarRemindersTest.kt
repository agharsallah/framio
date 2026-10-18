package dev.framio.mawaqit.core

import dev.framio.mawaqit.core.AdhkarCategory.EVENING
import dev.framio.mawaqit.core.AdhkarCategory.MORNING
import dev.framio.mawaqit.core.AdhkarCategory.SLEEP
import dev.framio.mawaqit.core.AdhkarReminders.Step
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class AdhkarRemindersTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val day = LocalDate.of(2026, 10, 20) // a Tuesday
    private val b = Behavior()
    private val times = MawaqitTimes(
        List(12) { (1..31).associateWith { listOf("06:30", "08:00", "13:00", "16:00", "18:45", "20:15") } },
        List(12) { (1..31).associateWith { listOf("+20", "+10", "+10", "+5", "+10") } },
        true, null, null, null, false, 0, paris,
    )

    private fun at(h: Int, m: Int, date: LocalDate = day) = ZonedDateTime.of(date, LocalTime.of(h, m), paris)
    private fun t(at: String, c: AdhkarCategory = MORNING, days: Set<DayOfWeek> = emptySet(), mode: String = AdhkarTime.REMIND) =
        AdhkarTime("x", c, at, days, mode)
    private fun windows(s: AdhkarSettings = AdhkarSettings()) =
        AdhkarSchedule.windows(day.minusDays(1), times, b, s) + AdhkarSchedule.windows(day, times, b, s)

    @Test
    fun timesAreNormalized() {
        assertEquals("07:05", AdhkarReminders.normalize("7:05"))
        assertEquals("asr+15", AdhkarReminders.normalize(" Asr + 15 "))
        assertEquals("fajr+0", AdhkarReminders.normalize("fajr"))
        assertEquals("maghrib-30", AdhkarReminders.normalize("maghrib-30"))
        assertNull(AdhkarReminders.normalize("24:00"))
        assertNull(AdhkarReminders.normalize("jumua+5"))
        assertNull(AdhkarReminders.normalize("isha+300")) // after: 240 min at most
        assertNull(AdhkarReminders.normalize("isha-200")) // before: 180
        assertNull(AdhkarReminders.normalize(""))
    }

    @Test
    fun prayerRelativeTimes() {
        // after: from the end of the prayer's screens (Dhuhr 13:00, iqama 13:10, prayer 8 min, azkar 5 → 13:23)
        assertEquals(at(13, 33), AdhkarReminders.resolve(t("dhuhr+10"), day, times, b))
        assertEquals(at(13, 23), AdhkarReminders.resolve(t("dhuhr+0"), day, times, b))
        // before: from the adhan itself
        assertEquals(at(18, 15), AdhkarReminders.resolve(t("maghrib-30"), day, times, b))
        // Shuruq has no screens: from its time
        assertEquals(at(8, 20), AdhkarReminders.resolve(t("shuruq+20"), day, times, b))
        assertEquals(at(21, 0), AdhkarReminders.resolve(t("21:00"), day, times, b))
    }

    @Test
    fun aClockTimeInTheSpringGapMovesAfterIt() {
        val spring = LocalDate.of(2026, 3, 29)
        assertEquals(at(3, 30, spring), AdhkarReminders.resolve(t("02:30"), spring, times, b))
        // and on the autumn day 02:30 happens twice: the first one (summer time) counts
        val autumn = LocalDate.of(2026, 10, 25)
        val z = AdhkarReminders.resolve(t("02:30"), autumn, times, b)!!
        assertEquals(java.time.ZoneOffset.ofHours(2), z.offset)
    }

    @Test
    fun daysOfTheWeek() {
        val tuesday = t("09:00", days = setOf(DayOfWeek.TUESDAY))
        val friday = t("09:00", days = setOf(DayOfWeek.FRIDAY))
        assertEquals(1, AdhkarReminders.occurrences(day, listOf(tuesday), times, b, windows()).size)
        assertTrue(AdhkarReminders.occurrences(day, listOf(friday), times, b, windows()).isEmpty())
        assertTrue(AdhkarReminders.occurrences(day, listOf(tuesday.copy(enabled = false)), times, b, windows()).isEmpty())
        // all seven = every day
        val j = tuesday.toJson().put("days", org.json.JSONArray(listOf(1, 2, 3, 4, 5, 6, 7)))
        assertTrue(AdhkarTime.fromJson(j)!!.days.isEmpty())
    }

    @Test
    fun howLongACardStays() {
        val w = windows()
        // inside the morning's own time: until its end; outside any: two hours
        assertEquals(at(11, 0), AdhkarReminders.occurrences(day, listOf(t("09:00")), times, b, w).single().until)
        assertEquals(at(14, 0), AdhkarReminders.occurrences(day, listOf(t("12:00")), times, b, w).single().until)
        assertEquals(at(23, 0), AdhkarReminders.occurrences(day, listOf(t("21:00", EVENING)), times, b, w).single().until)
    }

    @Test
    fun firesOnTimeCatchesUpAfterThePrayerThenSkips() {
        val d = AdhkarReminders.occurrences(day, listOf(t("13:05")), times, b, windows()).single()
        assertEquals(Step.WAIT, AdhkarReminders.step(d, at(13, 4), blocked = false, settled = false))
        assertEquals(Step.FIRE, AdhkarReminders.step(d, at(13, 5), blocked = false, settled = false))
        // Dhuhr's screens until 13:23: it waits, then fires late
        assertEquals(Step.WAIT, AdhkarReminders.step(d, at(13, 10), blocked = true, settled = false))
        assertEquals(Step.FIRE, AdhkarReminders.step(d, at(13, 23), blocked = false, settled = false))
        // 45 min after its time: skipped, blocked or not
        assertEquals(Step.SKIP, AdhkarReminders.step(d, at(13, 50), blocked = true, settled = false))
        assertEquals(Step.SKIP, AdhkarReminders.step(d, at(13, 50), blocked = false, settled = false))
        // fired, done or dismissed: nothing more
        assertEquals(Step.NONE, AdhkarReminders.step(d, at(13, 6), blocked = false, settled = true))
        // the frame starting at 09:00 skips the 07:25 chime but the card still shows
        val m = AdhkarReminders.occurrences(day, AdhkarReminders.legacy(null), times, b, windows()).first { it.category == MORNING }
        assertEquals(Step.SKIP, AdhkarReminders.step(m, at(9, 0), blocked = false, settled = false))
        assertTrue(m.showing(at(9, 0)))
    }

    @Test
    fun theEarliestCardAndTheNextOne() {
        val list = listOf(t("09:00").copy(id = "a"), t("10:00", EVENING).copy(id = "b"), t("15:00", SLEEP).copy(id = "c"))
        val dues = AdhkarReminders.occurrences(day, list, times, b, windows())
        assertEquals(MORNING, AdhkarReminders.showing(at(10, 30), dues) { true }?.category)
        assertEquals(EVENING, AdhkarReminders.showing(at(10, 30), dues) { it.category != MORNING }?.category)
        assertEquals("c", AdhkarReminders.next(at(10, 30), dues)?.time?.id)
        assertEquals("$day/morning/a", dues.first().key)
    }

    @Test
    fun oldSettingsBecomeTheSameSchedules() {
        val old = JSONObject().put("enabled", true).put("morningReminder", true).put("morningAfterFajrMinutes", 30)
            .put("eveningReminder", false).put("eveningAfterAsrMinutes", 10).put("sleepTime", "22:15").put("chime", false)
        val s = AdhkarSettings.fromJson(old)
        assertEquals(listOf("fajr+30", "asr+10", "22:15"), s.schedules.map { it.at })
        assertEquals(listOf(MORNING, EVENING, SLEEP), s.schedules.map { it.category })
        assertEquals(listOf(true, false, true), s.schedules.map { it.enabled })
        assertTrue(s.schedules.all { it.mode == AdhkarTime.REMIND && it.days.isEmpty() })
        assertFalse(s.chime)
        // no sleep time: no sleep schedule; nothing at all: the defaults
        assertEquals(2, AdhkarSettings.fromJson(JSONObject().put("sleepTime", "")).schedules.size)
        assertEquals(listOf("fajr+20", "asr+15"), AdhkarSettings().schedules.map { it.at })
        // once written, the schedules win and the old keys are gone
        val written = s.toJson()
        assertFalse(written.has("morningReminder"))
        assertEquals(s, AdhkarSettings.fromJson(JSONObject(written.toString())))
        assertEquals(s.schedules, AdhkarSettings.fromJson(written.put("morningAfterFajrMinutes", 99)).schedules)
        // and the whole Settings round trip keeps them
        val all = Settings(adhkar = s.copy(pushEnabled = true, pushTopic = "my-frame_42"))
        assertEquals(all, Settings.fromJson(JSONObject(all.toJson().toString())))
    }

    @Test
    fun badSchedulesAreDropped() {
        val j = JSONObject().put("schedules", org.json.JSONArray()
            .put(JSONObject().put("category", "nope").put("at", "07:00"))
            .put(JSONObject().put("category", "evening").put("at", "later"))
            .put(JSONObject().put("category", "evening").put("at", "Maghrib - 20").put("mode", "explode").put("days", org.json.JSONArray(listOf(5, 9))))
            .put(JSONObject().put("category", "waking").put("at", "6:40").put("mode", "open").put("id", "w1")))
            .put("pushServer", "ftp://x").put("pushTopic", "a b/c")
        val s = AdhkarSettings.fromJson(j)
        assertEquals(listOf("maghrib-20", "06:40"), s.schedules.map { it.at })
        assertEquals(AdhkarTime.REMIND, s.schedules[0].mode)
        assertEquals(setOf(DayOfWeek.FRIDAY), s.schedules[0].days)
        assertEquals(AdhkarTime.OPEN, s.schedules[1].mode)
        assertEquals("w1", s.schedules[1].id)
        assertEquals("https://ntfy.sh", s.pushServer)
        assertEquals("abc", s.pushTopic)
        assertFalse(s.pushEnabled)
        assertEquals("t3", AdhkarReminders.newId(listOf("t1", "t2")))
        assertEquals("t4", AdhkarReminders.newId(listOf("t1", "t3", "x")))
    }

    @Test
    fun theSleepWindowFollowsTheFirstEnabledSleepSchedule() {
        val s = AdhkarSettings(schedules = listOf(AdhkarTime("a", SLEEP, "22:00", enabled = false), AdhkarTime("b", SLEEP, "isha+60")))
        val w = AdhkarSchedule.windows(day, times, b, s).first { it.category == SLEEP }
        // Isha 20:15, iqama 20:25, prayer 9 min, azkar 5 → 20:39, + 60
        assertEquals(at(21, 39), w.start)
    }
}
