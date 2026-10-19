package dev.framio.pomodoro.core.history

import dev.framio.pomodoro.core.AMSTERDAM
import dev.framio.pomodoro.core.log.SessionLog
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.notes.Journal
import dev.framio.pomodoro.core.notes.JournalCodec
import dev.framio.pomodoro.core.notes.Taxonomy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class SeedTest {
    private val today = LocalDate.parse("2026-10-18")
    private val seeded = HistorySeed.generate(400, today, AMSTERDAM)

    @Test
    fun `a year of plausible history, all marked as seed, none today`() {
        val r = seeded.records
        assertTrue("${r.size}", r.size in 1_000..1_600) // about 3 a day
        assertTrue(r.all { it.id.value.startsWith(HistorySeed.PREFIX) })
        val idx = HistoryIndex.build(r, Journal().also { j -> seeded.ops.forEach { j.apply(it) } }, AMSTERDAM)
        assertTrue(idx.sessions.none { it.date == today })
        assertTrue(idx.orphans.isEmpty())
        val report = InsightsReport.build(StatsIndex(AMSTERDAM).also { it.rebuild(idx) }.buckets, today, StatsRange.Quarter, 6)
        assertEquals(Taxonomy.PHONE, report.reasons.first().tag)
        assertNotNull(report.ratingByHour)
        assertNotNull(report.flow)
        assertTrue(report.outcomes.first { it.first == Outcome.Done }.second > report.outcomes.first { it.first == Outcome.EndedEarly }.second)
        assertEquals(seeded, HistorySeed.generate(400, today, AMSTERDAM)) // deterministic
    }

    /** `POMODORO_SEED_OUT=<dir> ./gradlew :core:test --tests '*SeedTest*' --rerun` writes the files for a device. */
    @Test
    fun `optionally writes seed files for a device`() {
        val out = System.getenv("POMODORO_SEED_OUT") ?: return
        val days = System.getenv("POMODORO_SEED_DAYS")?.toIntOrNull() ?: 400
        val s = HistorySeed.generate(days, LocalDate.now(AMSTERDAM), AMSTERDAM)
        File(out).mkdirs()
        File(out, "sessions.jsonl").writeText(s.records.joinToString("") { SessionLog.encode(it) + "\n" })
        File(out, "history.jsonl").writeText(s.ops.joinToString("") { JournalCodec.encode(it) + "\n" })
        println("SeedTest: wrote ${s.records.size} sessions, ${s.ops.size} journal lines to $out")
    }
}
