package dev.framio.pomodoro.core.notes

import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.SessionId
import dev.framio.pomodoro.core.model.TaskId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class JournalTest {
    private val s = SessionId("s1")
    private val d = Entry.Distraction(EntryId("e1"), s, 12.minutes + 30.seconds, 1_000L, listOf(Taxonomy.PHONE, Taxonomy.NOISE))
    private val all: List<Entry> = listOf(
        d,
        Entry.EndReason(EntryId("e2"), s, 20.minutes, null, listOf(Taxonomy.DONE)),
        Entry.Note(EntryId("e3"), s, null, 2_000L, NoteBody("line \"one\"\nline two", "a0b0.c1")),
        Entry.Parked(EntryId("e4"), s, 3.minutes, null, NoteBody.ink("0011"), resolved = true),
        Entry.ResumeNote(EntryId("e5"), s, 20.minutes, null, NoteBody.text("figures next")),
    )

    private fun lines(vararg ops: JournalOp) = ops.joinToString("\n") { JournalCodec.encode(it) }

    @Test
    fun `every op and entry kind is one line that reads back the same`() {
        val ops = all.map { JournalOp.Put(it) } + listOf(
            JournalOp.Delete(EntryId("e1"), s),
            JournalOp.Amend(SessionAmend(s, Outcome.Partly, 4, TaskId("t"), "Thesis")),
            JournalOp.Drop(s),
            JournalOp.Restore(s),
        )
        for (op in ops) {
            val line = JournalCodec.encode(op)
            assertFalse(line.contains('\n'))
            assertTrue(line, line.startsWith("{\"v\":1,"))
            assertEquals(JournalCodec.Line.Op(op), JournalCodec.decode(line))
        }
    }

    @Test
    fun `an edit is a later put of the same id, and keeps its place`() {
        val j = JournalCodec.read(lines(JournalOp.Put(d), JournalOp.Put(all[2]), JournalOp.Put(d.copy(tags = listOf(Taxonomy.PERSON)))))
        assertEquals(listOf(EntryId("e1"), EntryId("e3")), j.liveEntries.map { it.id })
        assertEquals(listOf(Taxonomy.PERSON), (j.entry(EntryId("e1")) as Entry.Distraction).tags)
        assertEquals(1, j.superseded)
    }

    @Test
    fun `delete then undo brings the entry back`() {
        val j = Journal()
        j.apply(JournalOp.Put(d))
        j.apply(JournalOp.Delete(d.id, s))
        assertNull(j.entry(d.id))
        assertTrue(j.isDeleted(d.id))
        j.apply(JournalOp.Put(d)) // undo
        assertEquals(d, j.entry(d.id))
        assertFalse(j.isDeleted(d.id))
    }

    @Test
    fun `amends merge, later fields win`() {
        val j = Journal()
        j.apply(JournalOp.Amend(SessionAmend(s, outcome = Outcome.Done)))
        j.apply(JournalOp.Amend(SessionAmend(s, rating = 3)))
        j.apply(JournalOp.Amend(SessionAmend(s, outcome = Outcome.Partly)))
        assertEquals(SessionAmend(s, Outcome.Partly, 3), j.amendOf(s))
    }

    @Test
    fun `drop and restore`() {
        val j = Journal()
        j.apply(JournalOp.Drop(s))
        assertTrue(j.isDropped(s))
        j.apply(JournalOp.Restore(s))
        assertFalse(j.isDropped(s))
    }

    @Test
    fun `a torn last line and garbage are skipped and counted, blanks ignored`() {
        val text = lines(JournalOp.Put(d)) + "\n\n" + "not json\n" + "{\"op\":\"put\"}\n" + JournalCodec.encode(JournalOp.Put(all[1])).take(25)
        val j = JournalCodec.read(text)
        assertEquals(listOf(d), j.liveEntries.toList())
        assertEquals(3, j.skipped)
        assertEquals(JournalCodec.Line.Garbage, JournalCodec.decode("""{"v":1,"op":"del"}"""))
    }

    @Test
    fun `lines from a newer version, unknown ops and unknown entry kinds are kept verbatim`() {
        val future = """{"v":2,"op":"put","e":{"type":"distraction","id":"x","s":"s1","tags":["phone"],"intensity":3}}"""
        val newOp = """{"v":1,"op":"merge","a":"s1","b":"s2"}"""
        val newKind = """{"v":1,"op":"put","e":{"type":"photo","id":"p","s":"s1","uri":"x"}}"""
        val j = JournalCodec.read(listOf(future, newOp, newKind, JournalCodec.encode(JournalOp.Put(d))).joinToString("\n"))
        assertEquals(listOf(future, newOp, newKind), j.unknownLines)
        assertEquals(listOf(d), j.liveEntries.toList())
        assertEquals(listOf(future, newOp, newKind), j.compacted().take(3))
    }

    @Test
    fun `unknown fields of a known entry are ignored`() {
        val line = JournalCodec.encode(JournalOp.Put(d)).dropLast(2) + ",\"mood\":[1]}}"
        assertEquals(JournalCodec.Line.Op(JournalOp.Put(d)), JournalCodec.decode(line))
    }

    @Test
    fun `compaction keeps the meaning, including tombstones and unknown lines`() {
        val ops = mutableListOf<JournalOp>()
        all.forEach { ops += JournalOp.Put(it) }
        ops += JournalOp.Put(d.copy(tags = listOf(Taxonomy.TIRED)))
        ops += JournalOp.Delete(EntryId("e4"), s)
        ops += JournalOp.Delete(EntryId.legacyResume(SessionId("old")), SessionId("old"))
        ops += JournalOp.Amend(SessionAmend(s, rating = 2))
        ops += JournalOp.Amend(SessionAmend(s, outcome = Outcome.NotYet))
        ops += JournalOp.Drop(SessionId("s9"))
        ops += JournalOp.Drop(SessionId("s8"))
        ops += JournalOp.Restore(SessionId("s8"))
        val unknown = """{"v":3,"op":"put"}"""
        val before = JournalCodec.read(unknown + "\n" + ops.joinToString("\n") { JournalCodec.encode(it) })
        val after = JournalCodec.read(before.compacted().joinToString("\n"))
        assertEquals(before.liveEntries.toList(), after.liveEntries.toList())
        assertEquals(before.amendOf(s), after.amendOf(s))
        assertTrue(after.isDropped(SessionId("s9")))
        assertFalse(after.isDropped(SessionId("s8")))
        assertTrue(after.isDeleted(EntryId.legacyResume(SessionId("old"))))
        assertTrue(after.isDeleted(EntryId("e4")))
        assertEquals(listOf(unknown), after.unknownLines)
        assertEquals(0, after.superseded)
        assertTrue(after.lines < before.lines)
    }

    @Test
    fun `compaction is suggested once a quarter of a big journal is dead weight`() {
        val j = Journal()
        repeat(Journal.MIN_SUPERSEDED) { j.apply(JournalOp.Put(d.copy(id = EntryId("n$it")))) }
        repeat(Journal.MIN_SUPERSEDED - 1) { j.apply(JournalOp.Put(d.copy(id = EntryId("n$it"), at = 1.minutes))) }
        assertFalse(j.shouldCompact) // superseded 499
        j.apply(JournalOp.Put(d.copy(id = EntryId("n0"), at = 2.minutes)))
        assertTrue(j.shouldCompact) // 500 of 1000
        repeat(1_200) { j.apply(JournalOp.Put(d.copy(id = EntryId("m$it")))) }
        assertFalse(j.shouldCompact) // 500 of 2200
    }

    @Test
    fun `derived ids are stable`() {
        assertEquals(EntryId("rn:s1"), EntryId.legacyResume(s))
        assertEquals(EntryId("pk:boot-1:4200"), EntryId.parked(dev.framio.pomodoro.core.time.Moment("boot-1", 4_200, 99L)))
    }

    @Test
    fun `note bodies are cleaned`() {
        assertNull(NoteBody("   ", "").cleaned())
        assertEquals(NoteBody("hi", null), NoteBody("  hi ", "").cleaned())
        assertEquals(NoteBody.MAX_TEXT, NoteBody("x".repeat(900)).cleaned()?.text?.length)
        assertTrue(NoteBody().isEmpty)
    }
}
