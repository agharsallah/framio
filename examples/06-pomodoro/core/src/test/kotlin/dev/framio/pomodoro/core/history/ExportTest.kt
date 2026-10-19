package dev.framio.pomodoro.core.history

import dev.framio.pomodoro.core.AMSTERDAM
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.SessionRecord
import dev.framio.pomodoro.core.notes.Entry
import dev.framio.pomodoro.core.notes.EntryId
import dev.framio.pomodoro.core.notes.JournalCodec
import dev.framio.pomodoro.core.notes.JournalOp
import dev.framio.pomodoro.core.notes.NoteBody
import dev.framio.pomodoro.core.notes.TagId
import dev.framio.pomodoro.core.notes.Taxonomy
import dev.framio.pomodoro.core.record
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ExportTest {
    private val tax = Taxonomy().rename(Taxonomy.PHONE, "Phone, again")
    private val a = record("2026-10-18T09:00", focused = 24.minutes + 31.seconds, rating = 4)
        .copy(intention = "=HYPERLINK(\"x\")", taskTitle = "Thesis \"ch.3\"", resumeNote = "line1\nline2")
    private val b = record("2026-10-18T10:00", outcome = Outcome.EndedEarly, focused = 12.minutes)
    private val gone = record("2026-10-18T11:00")
    private val d = Entry.Distraction(EntryId("d1"), a.id, 3.minutes + 50.seconds, null, listOf(Taxonomy.PHONE, TagId("u-mail")))
    private val p = Entry.Parked(EntryId("p1"), a.id, 5.minutes, null, NoteBody.ink("A0b1.c2"))
    private val e = Entry.EndReason(EntryId("x1"), b.id, 12.minutes, null, listOf(Taxonomy.LEAVE))
    private val i = index(listOf(a, b, gone), journalOf(d, p, e, JournalOp.Drop(gone.id)))

    @Test
    fun `RFC 4180 fields`() {
        assertEquals("plain", Export.cell("plain"))
        assertEquals("\"a,b\"", Export.cell("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Export.cell("say \"hi\""))
        assertEquals("\"two\nlines\"", Export.cell("two\nlines"))
        assertEquals("\"cr\rlf\"", Export.cell("cr\rlf"))
        assertEquals("\" padded \"", Export.cell(" padded "))
        assertEquals("", Export.cell(""))
        assertEquals("héllo · 集中", Export.cell("héllo · 集中"))
    }

    @Test
    fun `formula-looking text is defused, numbers are not`() {
        assertEquals("'=1+1", Export.cell("=1+1"))
        assertEquals("'+call", Export.cell("+call"))
        assertEquals("'@sum", Export.cell("@sum"))
        assertEquals("'-x", Export.cell("-x"))
        assertEquals("-12", Export.cell("-12"))
        assertEquals("+3.5", Export.cell("+3.5"))
        assertEquals("\"'=A1,B1\"", Export.cell("=A1,B1"))
    }

    @Test
    fun `sessions csv has a BOM, CRLF rows, one row per kept session, local times and whole seconds`() {
        val csv = Export.sessionsCsv(i, tax)
        assertTrue(csv.startsWith("﻿session_id,date,start_local,"))
        val rows = csv.removePrefix("﻿").split("\r\n")
        assertEquals("", rows.last())
        assertEquals(4, rows.size) // header, 2 sessions, and the empty string after the last CRLF
        val aRow = rows.first { it.startsWith(a.id.value) }
        assertTrue(aRow, aRow.contains(",2026-10-18,2026-10-18 09:00:00,classic,"))
        assertTrue(aRow, aRow.contains(",\"Thesis \"\"ch.3\"\"\","))
        assertTrue(aRow, aRow.contains(",\"'=HYPERLINK(\"\"x\"\")\","))
        assertTrue(aRow, aRow.contains(",1500,1471,Done,4,1,\"Phone, again; mail\",,"))
        assertTrue(aRow, aRow.endsWith(",0,1,\"line1\nline2\""))
        assertFalse(csv.contains(gone.id.value))
        val bRow = rows.first { it.startsWith(b.id.value) }
        assertTrue(bRow, bRow.contains(",EndedEarly,,0,,Had to leave,"))
        assertFalse(Export.sessionsCsv(i, tax, bom = false).startsWith("﻿"))
    }

    @Test
    fun `entries csv has one row per entry with ink as text`() {
        val rows = Export.entriesCsv(i, tax, bom = false).split("\r\n").filter { it.isNotEmpty() }
        assertEquals("session_id,entry_id,kind,minute,written_local,tags,text,ink,resolved", rows[0])
        assertTrue(rows.contains("${a.id.value},d1,distraction,3,,\"Phone, again; mail\",,,"))
        assertTrue(rows.contains("${a.id.value},p1,parked,5,,,,A0b1.c2,false"))
        assertTrue(rows.contains("${b.id.value},x1,end,12,,Had to leave,,,"))
        assertTrue(rows.any { it.startsWith("${a.id.value},rn:${a.id.value},resume,") }) // the old record's resume note
    }

    @Test
    fun `json export is complete and reads back`() {
        val text = Export.json(i, tax, exportedAtMs = 1_792_310_400_123L)
        val doc = JournalCodec.json.parseToJsonElement(text).jsonObject
        assertEquals("pomodoro-history", doc.getValue("format").jsonPrimitive.content)
        assertEquals("Europe/Amsterdam", doc.getValue("zone").jsonPrimitive.content)
        assertTrue(doc.getValue("exportedAt").jsonPrimitive.content.endsWith(":00Z"))
        assertEquals(tax, JournalCodec.json.decodeFromJsonElement(Taxonomy.serializer(), doc.getValue("tags")))
        val sessions = doc.getValue("sessions").jsonArray.map { it.jsonObject }
        assertEquals(listOf(b.id.value, a.id.value), sessions.map { it.getValue("id").jsonPrimitive.content })
        val sa = sessions[1]
        assertEquals(a, JournalCodec.json.decodeFromJsonElement(SessionRecord.serializer(), JsonObject(sa - "entries" - "date" - "startLocal")))
        assertEquals("2026-10-18T09:00", sa.getValue("startLocal").jsonPrimitive.content)
        val entries = sa.getValue("entries").jsonArray.map { JournalCodec.json.decodeFromJsonElement(Entry.serializer(), it) }
        assertEquals(listOf(d, p), entries.take(2))
        assertEquals(3, entries.size)
    }

    @Test
    fun `an empty history exports headers and an empty list`() {
        val empty = index(emptyList())
        assertEquals("﻿" + "session_id,date,start_local,mode,task,intention,planned_s,focused_s,outcome,rating,distractions," +
            "distraction_reasons,end_reasons,notes,parked,resume_note\r\n", Export.sessionsCsv(empty, tax))
        val doc = JournalCodec.json.parseToJsonElement(Export.json(empty, tax, 0)).jsonObject
        assertEquals(0, doc.getValue("sessions").jsonArray.size)
    }

    @Test
    fun `unknown dates export as empty cells`() {
        val u = record("2026-10-18T09:00", id = "u").let { it.copy(start = it.start.copy(wallMs = null)) }
        val row = Export.sessionsCsv(index(listOf(u)), tax, bom = false).split("\r\n")[1]
        assertTrue(row, row.startsWith("u,,,classic,"))
        assertEquals(LocalDate.parse("2026-10-18"), index(listOf(a)).session(a.id)?.date)
        assertEquals(AMSTERDAM, i.zone)
    }
}
