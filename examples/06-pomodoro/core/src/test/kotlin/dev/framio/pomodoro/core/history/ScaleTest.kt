package dev.framio.pomodoro.core.history

import dev.framio.pomodoro.core.AMSTERDAM
import dev.framio.pomodoro.core.log.SessionLog
import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.SessionId
import dev.framio.pomodoro.core.model.SessionRecord
import dev.framio.pomodoro.core.model.TaskId
import dev.framio.pomodoro.core.notes.Entry
import dev.framio.pomodoro.core.notes.EntryId
import dev.framio.pomodoro.core.notes.JournalCodec
import dev.framio.pomodoro.core.notes.JournalOp
import dev.framio.pomodoro.core.notes.NoteBody
import dev.framio.pomodoro.core.notes.Taxonomy
import dev.framio.pomodoro.core.time.Moment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.measureTime

/**
 * About three years of heavy use: 5,000 sessions and 20,000 entries (PLAN §6.1 sized the log at ~4k lines a year).
 * The desktop JVM is a proxy for the frame (expect it to be several times slower there); what matters is that the
 * work is linear and well inside a background-thread budget.
 */
class ScaleTest {
    private val rnd = Random(42)
    private val start = LocalDateTime.parse("2023-11-01T08:00")

    private val records: List<SessionRecord> = (0 until 5_000).map { n ->
        val at = start.plusDays(n / 5L).plusHours((n % 5) * 2L).plusMinutes(rnd.nextLong(60))
            .atZone(AMSTERDAM).toInstant().toEpochMilli()
        SessionRecord(
            id = SessionId("s$n"),
            mode = ModeId(listOf("classic", "deep", "flow", "gentle")[n % 4]),
            modeName = "Mode",
            task = TaskId("t${n % 37}"),
            taskTitle = "Task ${n % 37}",
            intention = "Work on ${n % 37}",
            planned = if (n % 4 == 2) null else 25.minutes,
            focused = (5 + rnd.nextInt(50)).minutes,
            outcome = Outcome.entries[rnd.nextInt(Outcome.entries.size)],
            rating = if (rnd.nextInt(3) == 0) 1 + rnd.nextInt(5) else null,
            resumeNote = if (n % 7 == 0) "next: section ${n % 11}" else null,
            parkedCount = 0,
            start = Moment("b", 0, at),
        )
    }

    private val ink = (0 until 120).joinToString("") { "AbC1" } // ~480 characters, a one-word scribble

    private val entries: List<Entry> = (0 until 20_000).map { n ->
        val s = SessionId("s${rnd.nextInt(5_000)}")
        when (n % 10) {
            in 0..6 -> Entry.Distraction(EntryId("e$n"), s, rnd.nextInt(50).minutes, null, listOf(Taxonomy.Defaults[rnd.nextInt(9)].id))
            7 -> Entry.Parked(EntryId("e$n"), s, rnd.nextInt(50).minutes, null, NoteBody.ink(ink))
            8 -> Entry.Note(EntryId("e$n"), s, null, null, NoteBody.text("a short note about the session $n"))
            else -> Entry.EndReason(EntryId("e$n"), s, 20.minutes, null, listOf(Taxonomy.PERSON, Taxonomy.LEAVE))
        }
    }

    private val sessionsText = records.joinToString("\n") { SessionLog.encode(it) }
    private val journalText = entries.joinToString("\n") { JournalCodec.encode(JournalOp.Put(it)) }

    private fun best(times: Int = 5, block: () -> Unit) = (0 until times).minOf { measureTime(block) }

    @Test
    fun `join, rebuild stats and report for 5k sessions and 20k entries in under 200 ms`() {
        val journal = JournalCodec.read(journalText)
        val decoded = SessionLog.decode(sessionsText).records
        lateinit var report: InsightsReport
        val t = best {
            val idx = HistoryIndex.build(decoded, journal, AMSTERDAM)
            val stats = StatsIndex(AMSTERDAM).also { it.rebuild(idx) }
            report = InsightsReport.build(stats.buckets, LocalDate.parse("2026-08-10"), StatsRange.All, 6)
            idx.query(HistoryFilter(tags = setOf(Taxonomy.PHONE)))
        }
        println("ScaleTest: join + stats + report + filtered query: $t")
        assertEquals(5_000, report.started)
        assertTrue("took $t", t.inWholeMilliseconds < 200)
    }

    @Test
    fun `reading both files from text in under 400 ms`() {
        val t = best {
            SessionLog.decode(sessionsText)
            JournalCodec.read(journalText)
        }
        println("ScaleTest: decode ${sessionsText.length / 1024} kB log + ${journalText.length / 1024} kB journal: $t")
        assertTrue("took $t", t.inWholeMilliseconds < 400)
    }

    @Test
    fun `an incremental refresh after one append is cheap`() {
        val journal = JournalCodec.read(journalText)
        val idx = HistoryIndex.build(records, journal, AMSTERDAM)
        val stats = StatsIndex(AMSTERDAM).also { it.rebuild(idx) }
        val day = idx.sessions.first().date!!
        val t = best(20) { stats.refresh(idx, listOf(day)) }
        println("ScaleTest: refresh one day: $t")
        assertTrue("took $t", t.inWholeMilliseconds < 5)
    }

    @Test
    fun `the stats cache is small and reads back fast`() {
        val idx = HistoryIndex.build(records, JournalCodec.read(journalText), AMSTERDAM)
        val stats = StatsIndex(AMSTERDAM).also { it.rebuild(idx) }
        val text = StatsCache.encode(StatsCache(zone = "Europe/Amsterdam", checkpoint = Checkpoint(1, 2), days = stats.buckets.toList()))
        lateinit var back: StatsCache
        val t = best { back = StatsCache.decode(text)!! }
        println("ScaleTest: stats cache ${text.length / 1024} kB for ${back.days.size} days, decode $t")
        assertEquals(stats.buckets.sortedBy { it.date }, back.days.sortedBy { it.date })
        assertTrue("took $t", t.inWholeMilliseconds < 200)
    }
}
