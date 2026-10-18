package dev.framio.mawaqit.core

import dev.framio.mawaqit.core.AdhkarCategory.AFTER_PRAYER
import dev.framio.mawaqit.core.AdhkarCategory.EVENING
import dev.framio.mawaqit.core.AdhkarCategory.MORNING
import dev.framio.mawaqit.core.AdhkarCategory.SLEEP
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

class AdhkarLogTest {
    private val today = LocalDate.of(2026, 10, 18) // a Sunday
    /** A small book: morning has three adhkar (1×, 3×, 1×), after the prayer two (33×, 1×), sleep one. */
    private val book = mapOf(
        MORNING to listOf(Dhikr("m1", "ا", 1), Dhikr("m2", "ب", 3), Dhikr("m3", "ت", 1)),
        EVENING to listOf(Dhikr("e1", "ا", 1)),
        AFTER_PRAYER to listOf(Dhikr("a1", "ا", 33), Dhikr("a2", "ب", 1)),
        SLEEP to listOf(Dhikr("s1", "ا", 1)),
    )
    private val items: (AdhkarCategory, PrayerKey?) -> List<Dhikr> = { c, _ -> book[c].orEmpty() }
    private val morningAndEvening: (LocalDate) -> Set<AdhkarCategory> = { setOf(MORNING, EVENING) }

    private fun complete(vararg c: AdhkarCategory) = DayLog(c.associate { it.id to SessionLog(1, 1, 1, 1, true, 480) })
    private fun history(vararg days: Pair<Int, DayLog>) = days.fold(AdhkarHistory()) { h, (back, log) -> h.with(today.minusDays(back.toLong()), log) }

    @Test
    fun aDayFromTheProgress() {
        var p = AdhkarProgress()
            .withCount(today, "morning", "m1", 1).withCount(today, "morning", "m2", 2)
            .withCount(today, "after_prayer:fajr", "a1", 33).withCount(today, "after_prayer:fajr", "a2", 1).withDone(today, "after_prayer:fajr")
            .withTasbih(today, 99)
        val log = AdhkarLog.dayFrom(p, today, items, null, 7 * 60 + 12)
        // m1 in full, m2 two of three, m3 not yet: (1 + 2/3 + 0) / 3 of the morning
        assertEquals(SessionLog(done = 1, total = 3, said = 3, reps = 5, complete = false, part = 556), log.sessions["morning"])
        assertEquals(SessionLog(2, 2, 34, 34, true, 432, 1000), log.sessions["after_prayer:fajr"])
        assertEquals(99, log.tasbih)
        assertEquals(0.556f, log.share(MORNING), 1e-4f)
        assertEquals(0.2f, log.share(AFTER_PRAYER), 1e-4f) // one prayer of five
        assertEquals(0.2f, log.completion(AFTER_PRAYER), 1e-4f)
        // completed later: that time is kept on every rebuild after it
        p = p.withCount(today, "morning", "m2", 3).withCount(today, "morning", "m3", 1).withDone(today, "morning")
        val done = AdhkarLog.dayFrom(p, today, items, log, 7 * 60 + 40)
        assertEquals(460, done.sessions.getValue("morning").at)
        assertEquals(432, AdhkarLog.dayFrom(p, today, items, done, 9 * 60).sessions.getValue("after_prayer:fajr").at)
        assertEquals(460, AdhkarLog.dayFrom(p, today, items, done, 9 * 60).sessions.getValue("morning").at)
        // "read again": the session goes
        val reset = AdhkarLog.dayFrom(p.reset(today, "morning"), today, items, done, 9 * 60)
        assertNull(reset.sessions["morning"])
        // counts more than the number don't count twice
        val over = AdhkarLog.dayFrom(AdhkarProgress().withCount(today, "morning", "m2", 7), today, items, null, 0)
        assertEquals(3, over.sessions.getValue("morning").said)
    }

    @Test
    fun aBeforeSleepSessionPastMidnightIsLoggedOnItsDay() {
        assertEquals(1440 + 35, AdhkarLog.minuteOf(today, LocalDateTime.of(today.plusDays(1), java.time.LocalTime.of(0, 35))))
        assertEquals(22 * 60, AdhkarLog.minuteOf(today, LocalDateTime.of(today, java.time.LocalTime.of(22, 0))))
    }

    @Test
    fun daysThatLeftTheProgressKeepTheirLog() {
        val old = today.minusDays(5)
        val stored = DayLog(mapOf("morning" to SessionLog(3, 3, 5, 5, true, 450)), tasbih = 33)
        // the progress only knows (for the streaks) that the morning was done that day
        val p = AdhkarProgress().withDone(old, "morning")
        assertEquals(stored, AdhkarLog.dayFrom(p, old, items, stored, 0))
    }

    @Test
    fun migrationKeepsTheStreaks() {
        var p = AdhkarProgress()
        for (i in 0 until 12) p = p.withDone(today.minusDays(i.toLong()), "morning")
        for (i in 1 until 4) p = p.withDone(today.minusDays(i.toLong()), "evening")
        p = p.withTasbih(today, 40)
        val h = AdhkarHistory.migrate(p)
        assertEquals(12, h.days.size)
        assertEquals(p.streak("morning", today), AdhkarLog.streak(h, MORNING, today))
        assertEquals(12, AdhkarLog.streak(h, MORNING, today))
        assertEquals(3, AdhkarLog.streak(h, EVENING, today)) // today still open: yesterday's run
        assertEquals(40, h[today]!!.tasbih)
        assertTrue(h[today.minusDays(11)]!!.complete(MORNING))
        // a stored log wins over the progress for the days it has
        val stored = history(0 to DayLog(mapOf("morning" to SessionLog(3, 3, 5, 5, true, 470))))
        assertEquals(470, AdhkarHistory.migrate(p, stored)[today]!!.sessions.getValue("morning").at)
    }

    @Test
    fun streaksAndBest() {
        val h = history(0 to complete(MORNING), 1 to complete(MORNING), 2 to complete(MORNING, EVENING), 4 to complete(MORNING, EVENING),
            5 to complete(MORNING, EVENING), 6 to complete(MORNING, EVENING), 7 to complete(MORNING, EVENING), 9 to complete(EVENING))
        assertEquals(3, AdhkarLog.streak(h, MORNING, today))
        assertEquals(4, AdhkarLog.best(h, MORNING))
        assertEquals(0, AdhkarLog.streak(h, EVENING, today))
        assertEquals(4, AdhkarLog.best(h, EVENING))
        // perfect days (morning and evening): only day 2 since the gap… then 4 in a row before it
        assertEquals(0, AdhkarLog.dayStreak(h, today, morningAndEvening))
        assertEquals(4, AdhkarLog.bestDayStreak(h, today, morningAndEvening))
        // only the morning planned: the morning's run
        assertEquals(3, AdhkarLog.dayStreak(h, today, { setOf(MORNING) }))
        // days with nothing planned (Fridays here) neither break nor count
        val fri = today.minusDays(2) // 16 October 2026 is a Friday
        assertEquals(DayOfWeek.FRIDAY, fri.dayOfWeek)
        val noFriday: (LocalDate) -> Set<AdhkarCategory> = { if (it.dayOfWeek == DayOfWeek.FRIDAY) emptySet() else setOf(MORNING) }
        val gap = history(0 to complete(MORNING), 1 to complete(MORNING), 3 to complete(MORNING))
        assertEquals(3, AdhkarLog.dayStreak(gap, today, noFriday))
        assertEquals(2, AdhkarLog.dayStreak(gap, today, { setOf(MORNING) }))
    }

    @Test
    fun ratesCountFromTheLogsFirstDay() {
        val h = history(0 to complete(MORNING), 1 to complete(MORNING), 2 to DayLog(tasbih = 5), 3 to complete(MORNING, EVENING))
        assertEquals(0.75f, AdhkarLog.rate(h, MORNING, today, 7), 1e-4f) // 3 of 4 days: the log is 4 days old
        assertEquals(0.75f, AdhkarLog.rate(h, MORNING, today, 30), 1e-4f)
        // an evening not done yet today doesn't count against it
        assertEquals(1 / 3f, AdhkarLog.rate(h, EVENING, today, 7), 1e-4f)
        assertEquals(5, AdhkarLog.tasbih(h, today, 7))
        // after the prayer: each prayer a fifth
        val ap = DayLog(mapOf("after_prayer:fajr" to SessionLog(2, 2, 34, 34, true), "after_prayer:dhuhr" to SessionLog(2, 2, 34, 34, true),
            "after_prayer:asr" to SessionLog(0, 2, 10, 34, false, part = 150)))
        assertEquals(0.4f, ap.completion(AFTER_PRAYER), 1e-4f)
        assertEquals((2 + 0.15f) / 5, ap.share(AFTER_PRAYER), 1e-4f)
        assertFalse(ap.complete(AFTER_PRAYER))
    }

    @Test
    fun theHeatmap() {
        val h = history(0 to complete(MORNING), 1 to complete(MORNING, EVENING), 3 to DayLog(mapOf("morning" to SessionLog(1, 3, 2, 5, false, part = 400))))
        val m = AdhkarLog.heatmap(h, today, 18, morningAndEvening)
        assertEquals(18 * 7, m.cells.size)
        assertEquals(DayOfWeek.MONDAY, m.start.dayOfWeek)
        // today (Sunday) closes the last column
        assertEquals(today, m.day(m.cells.lastIndex))
        assertEquals(0.5f, m.cells.last()!!, 1e-4f) // morning done, evening not
        assertEquals(1f, m.cells[m.cells.lastIndex - 1]!!, 1e-4f)
        assertEquals(0f, m.cells[m.cells.lastIndex - 2]!!, 1e-4f) // in the log's time, nothing said
        assertEquals(0.2f, m.cells[m.cells.lastIndex - 3]!!, 1e-4f) // 40 % of the morning, of two collections
        // without the per-dhikr part (an older entry): adhkar done, then repetitions
        assertEquals(1 / 3f, SessionLog(1, 3, 2, 5, false).share, 1e-4f)
        assertEquals(0.4f, SessionLog(0, 0, 2, 5, false).share, 1e-4f)
        assertNull(m.cells[m.cells.lastIndex - 4]) // before the log
        // on a Wednesday the last column ends with empty days
        val wed = today.plusDays(3)
        val w = AdhkarLog.heatmap(h, wed, 4, morningAndEvening)
        assertEquals(wed, w.day(w.cells.indexOfLast { it != null }))
        assertNull(w.cells.last())
        // nothing planned: no value
        assertNull(AdhkarLog.score(complete(MORNING), emptySet()))
    }

    @Test
    fun usualTime() {
        val times = listOf(440, 450, 470, 500, 455)
        val h = times.foldIndexed(AdhkarHistory()) { i, acc, m -> acc.with(today.minusDays(i.toLong()), DayLog(mapOf("morning" to SessionLog(1, 1, 1, 1, true, m)))) }
        assertEquals(455, AdhkarLog.usualMinute(h, MORNING, today))
        assertNull(AdhkarLog.usualMinute(h, EVENING, today))
        assertNull(AdhkarLog.usualMinute(history(0 to complete(MORNING)), MORNING, today)) // too few
    }

    @Test
    fun roundTripAndBound() {
        val h = history(0 to DayLog(mapOf("morning" to SessionLog(3, 3, 5, 5, true, 450), "after_prayer:isha" to SessionLog(1, 2, 33, 34, false)), 99),
            1 to DayLog(tasbih = 7))
        assertEquals(h, AdhkarHistory.fromJson(JSONObject(h.toJson().toString())))
        // 400 days kept, counted from the newest
        var big = AdhkarHistory()
        for (i in 0 until 450) big = big.with(today.minusDays(i.toLong()), complete(MORNING))
        assertEquals(400, big.days.size)
        assertEquals(today.minusDays(399), big.first)
        // empty days aren't stored; a damaged entry is dropped, not the log
        assertEquals(1, h.with(today.minusDays(1), DayLog()).days.size)
        val broken = JSONObject("""{"v":1,"days":{"2026-10-18":{"s":{"morning":[1,2]},"t":0},"nonsense":{},"2026-10-17":{"t":5}}}""")
        assertEquals(setOf(today.minusDays(1)), AdhkarHistory.fromJson(broken).days.keys)
    }

    @Test
    fun statsAndGoals() {
        val schedules = listOf(AdhkarTime("a", MORNING, "fajr+20"), AdhkarTime("b", SLEEP, "22:00", days = setOf(DayOfWeek.SATURDAY)),
            AdhkarTime("c", EVENING, "asr+15", enabled = false))
        val goals = adhkarGoals(schedules)
        assertEquals(setOf(MORNING), goals(today)) // Sunday
        assertEquals(setOf(MORNING, SLEEP), goals(today.minusDays(1)))
        assertEquals(setOf(MORNING, EVENING), adhkarGoals(emptyList())(today))
        val h = history(0 to complete(MORNING).copy(tasbih = 10), 1 to complete(MORNING, SLEEP), 2 to complete(EVENING))
        val s = AdhkarLog.stats(h, today, goals, size = { book[it]?.size ?: 0 })
        assertEquals(listOf(MORNING, EVENING, SLEEP), s.collections.map { it.category })
        assertEquals(2, s.streak)
        assertEquals(10, s.tasbihToday)
        assertEquals(14, s.tasbih14.size)
        val m = s.collections.first()
        assertTrue(m.goal && m.todayComplete)
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 1f, 1f), m.week)
        assertEquals(1, s.collections.first { it.category == EVENING }.todayTotal) // not started: the book's size
        val j = s.toJson()
        assertEquals(2, j.getInt("streak"))
        assertEquals(0.67, j.getJSONObject("collections").getJSONObject("morning").getDouble("rate30"), 1e-9)
        assertTrue(AdhkarLog.stats(AdhkarHistory(), today, goals).empty)
    }
}
