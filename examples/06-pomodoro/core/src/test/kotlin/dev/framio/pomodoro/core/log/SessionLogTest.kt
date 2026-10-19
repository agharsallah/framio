package dev.framio.pomodoro.core.log

import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

class SessionLogTest {
    private val a = record("2026-10-18T09:00", rating = 4).copy(intention = "Thesis\nch.3", resumeNote = "figures")
    private val b = record("2026-10-18T10:00", outcome = Outcome.EndedEarly, focused = 12.minutes)

    private fun file(vararg lines: String) = lines.joinToString("\n", postfix = "\n")

    @Test
    fun `one record is one line and reads back the same`() {
        val line = SessionLog.encode(a)
        assertFalse(line.contains('\n'))
        assertFalse(line.contains("null")) // compact: absent fields are left out
        assertTrue(line.contains("\"outcome\":\"done\""))
        assertEquals(SessionLog.Decoded(listOf(a, b), 0), SessionLog.decode(file(SessionLog.encode(a), SessionLog.encode(b))))
    }

    @Test
    fun `an empty file or blank lines`() {
        assertEquals(SessionLog.Decoded(emptyList(), 0), SessionLog.decode(""))
        assertEquals(SessionLog.Decoded(listOf(a), 0), SessionLog.decode("\n\n" + SessionLog.encode(a) + "\r\n\n"))
    }

    @Test
    fun `a torn last line after a power cut is skipped`() {
        val torn = SessionLog.encode(b).take(30)
        assertEquals(SessionLog.Decoded(listOf(a), 1), SessionLog.decode(SessionLog.encode(a) + "\n" + torn))
    }

    @Test
    fun `unknown fields from a newer version are ignored, unreadable lines skipped`() {
        val future = SessionLog.encode(a).dropLast(1) + ",\"mood\":{\"x\":[1,2]},\"v\":2}"
        val unknownOutcome = SessionLog.encode(b).replace("ended_early", "abandoned")
        val decoded = SessionLog.decode(file(future, unknownOutcome))
        assertEquals(listOf(a), decoded.records)
        assertEquals(1, decoded.skipped)
    }

    @Test
    fun `a session appended twice keeps the last version`() {
        val again = a.copy(rating = 5)
        val decoded = SessionLog.decode(file(SessionLog.encode(a), SessionLog.encode(b), SessionLog.encode(again)))
        assertEquals(listOf(again, b), decoded.records)
    }
}
