package dev.framio.pomodoro.core.history

import dev.framio.pomodoro.core.AMSTERDAM
import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.SessionRecord
import dev.framio.pomodoro.core.model.Task
import dev.framio.pomodoro.core.model.TaskId
import dev.framio.pomodoro.core.notes.Entry
import dev.framio.pomodoro.core.notes.EntryId
import dev.framio.pomodoro.core.notes.Journal
import dev.framio.pomodoro.core.notes.JournalOp
import dev.framio.pomodoro.core.notes.NoteBody
import dev.framio.pomodoro.core.notes.SessionAmend
import dev.framio.pomodoro.core.notes.Taxonomy
import dev.framio.pomodoro.core.record
import dev.framio.pomodoro.core.stats.TaskEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class InsightsReportTest {
    private val today = LocalDate.parse("2026-10-18") // a Sunday

    private fun report(
        records: List<SessionRecord>,
        journal: Journal = Journal(),
        range: StatsRange = StatsRange.Week,
        day: LocalDate = today,
        tasks: List<Task> = emptyList(),
    ) = InsightsReport.build(stats(index(records, journal)).buckets, day, range, target = 6, tasks = tasks)

    @Test
    fun `empty data gives zeros and withholds everything that needs a sample`() {
        val r = report(emptyList())
        assertEquals(0, r.today.sessions)
        assertEquals(6, r.today.target)
        assertEquals(7, r.week.size)
        assertEquals(42, r.weekSoFar.target) // Monday..Sunday
        assertEquals(0, r.activeDays7)
        assertEquals(Duration.ZERO, r.focused)
        assertEquals(Outcome.entries.map { it to 0 }, r.outcomes)
        assertNull(r.distractionsPerHour)
        assertNull(r.ratingByHour)
        assertNull(r.flow)
        assertEquals(emptyList<ReasonCount>(), r.reasons)
        assertEquals(24, r.hours.size)
        assertEquals(0, r.unplaced)
    }

    @Test
    fun `today, the week so far and the 7 days`() {
        val records = listOf(
            record("2026-10-18T09:00"),
            record("2026-10-18T10:00", outcome = Outcome.EndedEarly, focused = 10.minutes),
            record("2026-10-12T09:00"), // Monday
            record("2026-10-11T09:00"), // last Sunday: in the 7 days? no (12..18)
        )
        val r = report(records)
        assertEquals(1, r.today.sessions)
        assertEquals(35.minutes, r.today.focused)
        assertEquals(2, r.weekSoFar.sessions)
        assertEquals(LocalDate.parse("2026-10-12"), r.week.first().date)
        assertEquals(listOf(1, 0, 0, 0, 0, 0, 1), r.week.map { it.sessions })
        assertEquals(2, r.activeDays7)
        assertEquals(3, r.activeDays28)
        assertEquals(3, r.started)
        assertEquals(2, r.completed)
        assertEquals(2, r.daysWithFocus)
    }

    @Test
    fun `the week so far restarts on the first day of the week`() {
        val r = report(listOf(record("2026-10-18T09:00"), record("2026-10-19T09:00")), day = LocalDate.parse("2026-10-19"))
        assertEquals(1, r.weekSoFar.sessions)
        assertEquals(6, r.weekSoFar.target)
    }

    @Test
    fun `N of 7 days across the autumn DST change and midnight`() {
        val records = listOf(
            record("2026-10-20T23:59"),
            record("2026-10-21T00:00"),
            record("2026-10-25T02:30"),
            record("2026-10-25T02:30", id = "again"), // the repeated hour: still one day
            record("2026-10-26T09:00", outcome = Outcome.EndedEarly),
        )
        val r = report(records, day = LocalDate.parse("2026-10-27"))
        assertEquals(2, r.activeDays7)
        assertEquals(2, r.week.first { it.date.dayOfMonth == 25 }.sessions)
    }

    @Test
    fun `outcome mix counts every outcome in the range`() {
        val r = report(
            listOf(
                record("2026-10-18T09:00"),
                record("2026-10-18T10:00", outcome = Outcome.Partly),
                record("2026-10-17T10:00", outcome = Outcome.Interrupted),
                record("2026-09-01T10:00", outcome = Outcome.NotYet), // outside
            ),
        )
        assertEquals(listOf(1, 1, 0, 0, 0, 1), r.outcomes.map { it.second })
        assertEquals(1, report(listOf(record("2026-09-01T10:00", outcome = Outcome.NotYet)), range = StatsRange.All).outcomes[2].second)
    }

    @Test
    fun `reasons count each tag once per distraction, sorted, with a cautious trend`() {
        val s = record("2026-10-18T09:00")
        val old = record("2026-10-10T09:00")
        val entries = mutableListOf<Any>()
        repeat(8) { entries += distraction(s, it, Taxonomy.PHONE) }
        entries += distraction(s, 9, Taxonomy.NOISE, Taxonomy.PHONE, Taxonomy.PHONE)
        repeat(2) { entries += distraction(old, it, Taxonomy.PHONE) }
        repeat(4) { entries += distraction(old, it, Taxonomy.NOISE) }
        entries += endReason(s, Taxonomy.DONE)
        val r = report(listOf(s, old), journalOf(*entries.toTypedArray()))
        assertEquals(9, r.distractions)
        assertEquals(1, r.sessionsDistracted)
        assertEquals(
            listOf(ReasonCount(Taxonomy.PHONE, 9, 2, Trend.Up), ReasonCount(Taxonomy.NOISE, 1, 4, Trend.Unknown)),
            r.reasons,
        )
        assertEquals(listOf(ReasonCount(Taxonomy.DONE, 1, 0, Trend.Unknown)), r.endReasons)
        assertEquals(listOf(Trend.Unknown), report(listOf(s), journalOf(*entries.toTypedArray()), range = StatsRange.All).reasons.take(1).map { it.trend })
    }

    @Test
    fun `trend rules`() {
        assertEquals(Trend.Unknown, InsightsReport.trendOf(4, 0))
        assertEquals(Trend.Up, InsightsReport.trendOf(5, 0))
        assertEquals(Trend.Same, InsightsReport.trendOf(12, 10)) // diff below 3
        assertEquals(Trend.Same, InsightsReport.trendOf(40, 34)) // 6 is less than a quarter of 40
        assertEquals(Trend.Down, InsightsReport.trendOf(20, 30))
        assertEquals(Trend.Unknown, InsightsReport.trendOf(20, null))
    }

    @Test
    fun `weekly counts of the top three reasons for small multiples`() {
        val s = record("2026-10-18T09:00")
        val earlier = record("2026-10-04T09:00")
        val j = journalOf(
            distraction(s, 1, Taxonomy.PHONE), distraction(s, 2, Taxonomy.PHONE), distraction(s, 3, Taxonomy.NOISE),
            distraction(s, 4, Taxonomy.TIRED), distraction(s, 5, Taxonomy.TECH), distraction(earlier, 1, Taxonomy.PHONE),
        )
        val r = report(listOf(s, earlier), j)
        assertEquals(3, r.reasonWeeks.size)
        assertEquals(Taxonomy.PHONE, r.reasonWeeks[0].tag)
        assertEquals(listOf(0, 0, 0, 0, 0, 1, 0, 2), r.reasonWeeks[0].weeks)
    }

    @Test
    fun `distractions by hour use the start plus the focus offset, and recalled ones stay unplaced`() {
        val s = record("2026-10-18T09:50", focused = 25.minutes)
        val j = journalOf(distraction(s, 5, Taxonomy.PHONE), distraction(s, 15, Taxonomy.NOISE), distraction(s, null, Taxonomy.TIRED))
        val r = report(listOf(s), j)
        assertEquals(1, r.hours[9].distractions)
        assertEquals(1, r.hours[10].distractions)
        assertEquals(10.minutes, r.hours[9].focused)
        assertEquals(15.minutes, r.hours[10].focused)
        assertEquals(1, r.unplaced)
    }

    @Test
    fun `focus spreads over the repeated hour on the autumn DST night`() {
        // 02:30 CEST, 90 minutes: 30 min in the first 02:xx, 60 in the second 02:xx (CET)
        val s = record("2026-10-25T02:30", focused = 90.minutes)
        val r = report(listOf(s), day = LocalDate.parse("2026-10-25"))
        assertEquals(90.minutes, r.hours[2].focused)
        assertEquals(Duration.ZERO, r.hours[3].focused)
    }

    @Test
    fun `distractions per focus hour only from 5 hours of focus`() {
        val sessions = (0 until 12).map { record("2026-10-18T%02d:00".format(it), id = "f$it") }
        val j = journalOf(distraction(sessions[0], 1, Taxonomy.PHONE), distraction(sessions[1], 1, Taxonomy.PHONE))
        assertNull(report(sessions.take(11), j).distractionsPerHour) // 4 h 35 min
        assertEquals(0.4, report(sessions, j).distractionsPerHour!!, 1e-9) // 2 in 5 h
    }

    @Test
    fun `focus rating by hour appears at 20 rated sessions, with a mean only from 3 in an hour`() {
        val rated = (0 until 19).map { i ->
            record("2026-09-%02dT%s".format(1 + i, if (i < 17) "09:00" else "15:00"), rating = 1 + i % 5, id = "r$i")
        }
        val unrated = record("2026-10-18T09:00", id = "plain")
        assertNull(report(rated + unrated, range = StatsRange.All).ratingByHour) // 19 rated
        val r = report(rated + unrated + record("2026-10-18T10:00", rating = 5, id = "r19"), range = StatsRange.All)
        val cells = assertNotNullAnd(r.ratingByHour)
        assertEquals(20, r.rated)
        assertEquals(17, cells[9].count)
        assertEquals((0 until 17).sumOf { 1 + it % 5 } / 17f, cells[9].mean!!, 1e-6f)
        assertEquals(2, cells[15].count)
        assertNull(cells[15].mean) // 2 sessions: no mean
        assertNull(cells[10].mean)
        assertEquals(1, cells[10].count)
    }

    @Test
    fun `the rating threshold counts the range only`() {
        val rated = (0 until 20).map { record("2026-09-%02dT09:00".format(1 + it), rating = 3, id = "r$it") }
        assertNull(report(rated, range = StatsRange.Week).ratingByHour)
        assertNotNull(report(rated, range = StatsRange.All).ratingByHour)
    }

    @Test
    fun `flow's typical block is the middle half of finished flow blocks, from 5 blocks`() {
        fun flow(min: Int, i: Int, outcome: Outcome = Outcome.Done) =
            record("2026-10-18T%02d:00".format(i), focused = min.minutes, outcome = outcome, id = "fl$i").copy(planned = null, mode = ModeId("flow"))
        val four = listOf(20, 35, 41, 55).mapIndexed { i, m -> flow(m, i) }
        assertNull(report(four + flow(90, 9, Outcome.EndedEarly)).flow) // ended early doesn't count
        val r = report(four + flow(90, 9) + record("2026-10-18T20:00")) // a classic block doesn't count either
        assertEquals(FlowSummary(5, 41.minutes, 35.minutes, 55.minutes), r.flow)
    }

    @Test
    fun `per mode and per task totals, tasks folded after the top 7`() {
        val records = (0 until 10).map { i ->
            record("2026-10-18T%02d:00".format(i), task = TaskId("t$i"), focused = (10 + i).minutes, id = "k$i")
                .copy(taskTitle = "Task $i", mode = if (i < 3) ModeId("deep") else ModeId("classic"), modeName = if (i < 3) "Deep" else "Classic")
        }
        val r = report(records)
        assertEquals(listOf("classic", "deep"), r.modes.map { it.key })
        assertEquals(listOf("Classic" to (13..19).sum().minutes, "Deep" to 33.minutes), r.modes.map { it.title to it.focused })
        assertEquals(8, r.tasks.size)
        assertEquals((9 downTo 3).map { "t$it" } + InsightsReport.OTHER, r.tasks.map { it.key })
        assertEquals(Total(InsightsReport.OTHER, null, 33.minutes, 3, 3), r.tasks.last())
    }

    @Test
    fun `estimates count finished sessions of all time, not just the range`() {
        val t = TaskId("thesis")
        val records = listOf(
            record("2026-01-05T09:00", task = t),
            record("2026-10-18T09:00", task = t),
            record("2026-10-18T10:00", task = t, outcome = Outcome.EndedEarly),
        )
        val tasks = listOf(Task(t, "Thesis", estimateSessions = 4), Task(TaskId("none"), "No estimate"))
        assertEquals(listOf(TaskEstimate(t, "Thesis", 4, 2)), report(records, tasks = tasks).estimates)
    }

    @Test
    fun `incremental refresh after appends and edits equals a full rebuild`() {
        val rnd = Random(7)
        val records = mutableListOf<SessionRecord>()
        val journal = Journal()
        val live = StatsIndex(AMSTERDAM)
        repeat(300) { n ->
            val touched = mutableSetOf<LocalDate>()
            when (rnd.nextInt(6)) {
                0, 1, 2 -> {
                    val r = record(
                        "2026-%02d-%02dT%02d:%02d".format(1 + rnd.nextInt(10), 1 + rnd.nextInt(28), rnd.nextInt(24), rnd.nextInt(60)),
                        focused = (5 + rnd.nextInt(60)).minutes,
                        outcome = Outcome.entries[rnd.nextInt(Outcome.entries.size)],
                        rating = if (rnd.nextBoolean()) 1 + rnd.nextInt(5) else null,
                        id = "r$n",
                    )
                    records += r
                    touched += r.date()
                }
                3 -> records.randomOrNull(rnd)?.let { r ->
                    journal.apply(JournalOp.Put(distraction(r, rnd.nextInt(30), Taxonomy.Defaults[rnd.nextInt(9)].id)))
                    touched += r.date()
                }
                4 -> records.randomOrNull(rnd)?.let { r ->
                    journal.apply(JournalOp.Amend(SessionAmend(r.id, outcome = Outcome.Partly, rating = rnd.nextInt(6))))
                    touched += r.date()
                }
                else -> records.randomOrNull(rnd)?.let { r ->
                    journal.apply(if (journal.isDropped(r.id)) JournalOp.Restore(r.id) else JournalOp.Drop(r.id))
                    touched += r.date()
                }
            }
            live.refresh(index(records, journal), touched)
        }
        val full = stats(index(records, journal))
        assertEquals(full.buckets.sortedBy { it.date }, live.buckets.sortedBy { it.date })
        val day = LocalDate.parse("2026-10-18")
        assertEquals(
            InsightsReport.build(full.buckets, day, StatsRange.All, 6),
            InsightsReport.build(live.buckets, day, StatsRange.All, 6),
        )
    }

    @Test
    fun `a day whose last session is dropped disappears`() {
        val r = record("2026-10-18T09:00")
        val live = stats(index(listOf(r)))
        live.refresh(index(listOf(r), journalOf(JournalOp.Drop(r.id))), listOf(today))
        assertNull(live.bucket(today))
    }

    @Test
    fun `notes and resume notes don't count as distractions`() {
        val r = record("2026-10-18T09:00").copy(resumeNote = "next")
        val j = journalOf(Entry.Note(EntryId("n"), r.id, 3.minutes, null, NoteBody.text("x")))
        assertEquals(0, report(listOf(r), j).distractions)
    }

    private fun SessionRecord.date(): LocalDate = dev.framio.pomodoro.core.stats.Insights.dateOf(this, AMSTERDAM)!!

    private fun <T : Any> assertNotNullAnd(v: T?): T {
        assertNotNull(v)
        return v!!
    }
}
