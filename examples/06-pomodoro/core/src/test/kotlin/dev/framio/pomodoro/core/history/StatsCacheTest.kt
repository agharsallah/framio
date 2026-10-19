package dev.framio.pomodoro.core.history

import dev.framio.pomodoro.core.notes.Taxonomy
import dev.framio.pomodoro.core.record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StatsCacheTest {
    private val r = record("2026-10-18T09:00", rating = 3)
    private val days = stats(index(listOf(r), journalOf(distraction(r, 4, Taxonomy.PHONE)))).buckets.toList()
    private val cache = StatsCache(zone = "Europe/Amsterdam", checkpoint = Checkpoint(1200, 300), days = days)

    @Test
    fun `round trip`() {
        assertEquals(cache, StatsCache.decode(StatsCache.encode(cache)))
    }

    @Test
    fun `garbage, a torn file or another version decode as null`() {
        assertNull(StatsCache.decode(""))
        assertNull(StatsCache.decode("{"))
        assertNull(StatsCache.decode(StatsCache.encode(cache).dropLast(10)))
        assertNull(StatsCache.decode(StatsCache.encode(cache.copy(version = 2))))
    }

    @Test
    fun `fresh only for the same file lengths and zone`() {
        assertTrue(cache.isFresh(Checkpoint(1200, 300), "Europe/Amsterdam"))
        assertFalse(cache.isFresh(Checkpoint(1300, 300), "Europe/Amsterdam"))
        assertFalse(cache.isFresh(Checkpoint(1200, 100), "Europe/Amsterdam")) // a compaction shrinks the journal
        assertFalse(cache.isFresh(Checkpoint(1200, 300), "Europe/London"))
    }
}

class FoldMonthsTest {
    private val today = LocalDate.parse("2026-10-18")
    private val seeded = HistorySeed.generate(500, today, dev.framio.pomodoro.core.AMSTERDAM)
    private val days = StatsIndex(dev.framio.pomodoro.core.AMSTERDAM).also { s ->
        s.rebuild(HistoryIndex.build(seeded.records, dev.framio.pomodoro.core.notes.Journal().also { j -> seeded.ops.forEach { j.apply(it) } }, dev.framio.pomodoro.core.AMSTERDAM))
    }.buckets

    @Test
    fun `folding old days into months changes no report, for every range`() {
        val cutoff = today.minusDays(DayBucket.KEEP_DAYS).withDayOfMonth(1)
        val folded = DayBucket.foldMonths(days, cutoff)
        assertTrue("${folded.size} of ${days.size}", folded.size < days.size / 2)
        for (r in StatsRange.entries) {
            assertEquals(r.name, InsightsReport.build(days, today, r, 6), InsightsReport.build(folded, today, r, 6))
        }
    }

    @Test
    fun `a folded month keeps its active days`() {
        val folded = DayBucket.foldMonths(days, today.withDayOfMonth(1))
        val sep = folded.single { it.date == LocalDate.parse("2026-09-01") }
        assertEquals(days.count { it.date.monthValue == 9 && it.date.year == 2026 && it.started > 0 }, sep.daysWithFocus)
        assertEquals(days.filter { it.date.monthValue == 9 && it.date.year == 2026 }.sumOf { it.started }, sep.started)
    }
}
