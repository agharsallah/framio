package dev.framio.pomodoro.core.history

import dev.framio.pomodoro.core.AMSTERDAM
import dev.framio.pomodoro.core.log.SessionLog
import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.SessionId
import dev.framio.pomodoro.core.model.TaskId
import dev.framio.pomodoro.core.notes.Entry
import dev.framio.pomodoro.core.notes.EntryId
import dev.framio.pomodoro.core.notes.JournalCodec
import dev.framio.pomodoro.core.notes.JournalOp
import dev.framio.pomodoro.core.notes.NoteBody
import dev.framio.pomodoro.core.notes.SessionAmend
import dev.framio.pomodoro.core.notes.Taxonomy
import dev.framio.pomodoro.core.record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes

class HistoryIndexTest {
    private val thesis = TaskId("thesis")
    private val a = record("2026-10-18T09:00", task = thesis).copy(taskTitle = "Thesis")
    private val b = record("2026-10-18T14:00", outcome = Outcome.EndedEarly, focused = 12.minutes)
    private val c = record("2026-10-17T23:59:30").copy(mode = ModeId("deep"))
    private val undated = record("2026-10-16T10:00", id = "u").let { it.copy(start = it.start.copy(wallMs = null)) }

    @Test
    fun `an empty log and journal`() {
        val i = index(emptyList())
        assertEquals(emptyList<DayGroup>(), i.query())
        assertEquals(0, i.size)
        assertNull(i.latestResume())
    }

    @Test
    fun `sessions are grouped by local day, newest first, unknown dates last`() {
        val groups = index(listOf(c, a, undated, b)).query()
        assertEquals(listOf(LocalDate.parse("2026-10-18"), LocalDate.parse("2026-10-17"), null), groups.map { it.date })
        assertEquals(listOf(b.id, a.id), groups[0].sessions.map { it.id })
        assertEquals(37.minutes, groups[0].focused)
        assertEquals(1, groups[0].completed)
    }

    @Test
    fun `a session belongs to the local day it started, across midnight and DST`() {
        val late = record("2026-10-24T23:50", focused = 50.minutes) // runs into the 25th
        val twice = record("2026-10-25T02:30") // the autumn hour that happens twice
        val groups = index(listOf(late, twice)).query()
        assertEquals(listOf("2026-10-25", "2026-10-24"), groups.map { it.date.toString() })
    }

    @Test
    fun `entries are joined in timeline order, recalled ones last`() {
        val late = Entry.Note(EntryId("n"), a.id, null, 5L, NoteBody.text("after"))
        val early = distraction(a, 3, Taxonomy.PHONE)
        val mid = distraction(a, 11, Taxonomy.NOISE)
        val recalled = distraction(a, null, Taxonomy.TIRED)
        val view = index(listOf(a), journalOf(late, mid, recalled, early)).session(a.id)!!
        assertEquals(listOf(early, mid, late, recalled), view.entries) // unknown write time last
        assertEquals(3, view.distractions)
        assertTrue(view.hasNotes)
    }

    @Test
    fun `an old record's resume note and parked count show up, and can be edited or deleted`() {
        val old = a.copy(resumeNote = " rewrite intro ", parkedCount = 2)
        val id = EntryId.legacyResume(old.id)
        val plain = index(listOf(old)).session(old.id)!!
        assertEquals(listOf(Entry.ResumeNote(id, old.id, old.focused, null, NoteBody.text("rewrite intro"))), plain.entries)
        assertEquals(2, plain.legacyParked)

        val edited = Entry.ResumeNote(id, old.id, old.focused, 9L, NoteBody.text("figures first"))
        assertEquals(listOf(edited), index(listOf(old), journalOf(edited)).session(old.id)!!.entries)

        val deleted = journalOf(JournalOp.Delete(id, old.id))
        assertEquals(emptyList<Entry>(), index(listOf(old), deleted).session(old.id)!!.entries)
        // …and stays deleted after compaction
        val compacted = JournalCodec.read(deleted.compacted().joinToString("\n"))
        assertEquals(emptyList<Entry>(), index(listOf(old), compacted).session(old.id)!!.entries)
    }

    @Test
    fun `a handwritten resume note on the record shows as ink`() {
        val r = a.copy(resumeInk = "a0b1")
        assertEquals(NoteBody(null, "a0b1"), (index(listOf(r)).session(r.id)!!.entries.single() as Entry.ResumeNote).body)
    }

    @Test
    fun `kept parked thoughts replace the legacy count`() {
        val r = a.copy(parkedCount = 2)
        val p = Entry.Parked(EntryId("p"), r.id, 4.minutes, null, NoteBody.ink("0a1b"))
        assertEquals(0, index(listOf(r), journalOf(p)).session(r.id)!!.legacyParked)
    }

    @Test
    fun `corrections apply, rating 0 clears, out-of-range ratings are ignored`() {
        val rated = a.copy(rating = 4)
        fun after(vararg amends: SessionAmend) =
            index(listOf(rated), journalOf(*amends.map { JournalOp.Amend(it) }.toTypedArray())).session(rated.id)!!.record
        assertEquals(Outcome.Partly, after(SessionAmend(rated.id, outcome = Outcome.Partly)).outcome)
        assertNull(after(SessionAmend(rated.id, rating = 0)).rating)
        assertEquals(4, after(SessionAmend(rated.id, rating = 9)).rating)
        assertEquals(TaskId("x") to "X", after(SessionAmend(rated.id, task = TaskId("x"), taskTitle = "X")).let { it.task to it.taskTitle })
    }

    @Test
    fun `a dropped session disappears with its entries, and comes back on restore`() {
        val d = distraction(a, 3, Taxonomy.PHONE)
        val dropped = index(listOf(a, b), journalOf(d, JournalOp.Drop(a.id)))
        assertEquals(listOf(b.id), dropped.sessions.map { it.id })
        assertTrue(dropped.orphans.isEmpty()) // a dropped session's entries aren't orphans
        assertEquals(2, index(listOf(a, b), journalOf(d, JournalOp.Drop(a.id), JournalOp.Restore(a.id))).size)
    }

    @Test
    fun `entries of a session not logged yet are orphans`() {
        val running = distraction(record("2026-10-18T16:00", id = "running"), 2, Taxonomy.PHONE)
        assertEquals(listOf(running), index(listOf(a), journalOf(running)).orphans)
    }

    @Test
    fun `filters by task, mode, outcome, tag, date range and notes`() {
        val tagged = distraction(b, 5, Taxonomy.PERSON)
        val note = Entry.Note(EntryId("n"), c.id, null, null, NoteBody.text("x"))
        val i = index(listOf(a, b, c, undated), journalOf(tagged, note))
        fun ids(f: HistoryFilter) = i.query(f).flatMap { g -> g.sessions.map { it.id.value } }
        assertEquals(4, ids(HistoryFilter.NONE).size)
        assertEquals(listOf(a.id.value), ids(HistoryFilter(tasks = setOf(thesis))))
        assertEquals(listOf(c.id.value), ids(HistoryFilter(modes = setOf(ModeId("deep")))))
        assertEquals(listOf(b.id.value), ids(HistoryFilter(outcomes = setOf(Outcome.EndedEarly, Outcome.Interrupted))))
        assertEquals(listOf(b.id.value), ids(HistoryFilter(tags = setOf(Taxonomy.PERSON, Taxonomy.TECH))))
        assertEquals(emptyList<String>(), ids(HistoryFilter(tags = setOf(Taxonomy.TECH))))
        val d18 = LocalDate.parse("2026-10-18")
        assertEquals(listOf(b.id.value, a.id.value), ids(HistoryFilter(from = d18)))
        assertEquals(listOf(c.id.value), ids(HistoryFilter(to = d18.minusDays(1)))) // undated is left out
        assertEquals(listOf(c.id.value), ids(HistoryFilter(withNotes = true)))
        assertEquals(emptyList<String>(), ids(HistoryFilter(tasks = setOf(thesis), outcomes = setOf(Outcome.EndedEarly))))
    }

    @Test
    fun `task choices, latest resume and open parked thoughts`() {
        val a2 = record("2026-10-18T11:00", task = thesis, id = "a2").copy(taskTitle = "Thesis ch.3")
        val other = record("2026-10-18T12:00", task = TaskId("tax"), focused = 5.minutes).copy(taskTitle = "Taxes")
        val r1 = Entry.ResumeNote(EntryId("r1"), a.id, 25.minutes, null, NoteBody.text("old"))
        val r2 = Entry.ResumeNote(EntryId("r2"), a2.id, 25.minutes, null, NoteBody.text("new"))
        val p1 = Entry.Parked(EntryId("p1"), a.id, 1.minutes, null, NoteBody.ink("00"))
        val p2 = Entry.Parked(EntryId("p2"), a2.id, 2.minutes, null, NoteBody.ink("11"), resolved = true)
        val p3 = Entry.Parked(EntryId("p3"), a2.id, 3.minutes, null, NoteBody.ink("22"))
        val i = index(listOf(a, a2, other), journalOf(r1, r2, p1, p2, p3))
        assertEquals(listOf(thesis to "Thesis ch.3", TaskId("tax") to "Taxes"), i.taskChoices())
        assertEquals(r2, i.latestResume())
        assertEquals(listOf(p3, p1), i.openParked())
    }

    @Test
    fun `reads the existing session log unchanged`() {
        val text = listOf(a, b).joinToString("\n") { SessionLog.encode(it) }
        val i = HistoryIndex.build(SessionLog.decode(text).records, JournalCodec.read(""), AMSTERDAM)
        assertEquals(setOf(a.id, b.id), i.sessions.map { it.id }.toSet())
        assertEquals(SessionId("s-2026-10-18T09:00"), i.session(a.id)?.id)
    }
}
