package dev.framio.pomodoro.data

import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.record
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionLogFileTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `a missing file is an empty log`() {
        val log = SessionLogFile(File(tmp.root, "sessions.jsonl")).read()
        assertEquals(0, log.records.size)
        assertEquals(0, log.skipped)
    }

    @Test
    fun `appends are one line each and read back in order`() {
        val file = File(tmp.root, "sessions.jsonl")
        val log = SessionLogFile(file)
        log.append(record("2026-10-18T09:00"))
        log.append(record("2026-10-18T09:30", outcome = Outcome.EndedEarly))
        assertEquals(2, file.readLines().size)
        assertEquals(listOf(Outcome.Done, Outcome.EndedEarly), log.read().records.map { it.outcome })
    }

    @Test
    fun `after a power cut tore the last line, the next record starts on its own line`() {
        val file = File(tmp.root, "sessions.jsonl")
        val log = SessionLogFile(file)
        log.append(record("2026-10-18T09:00"))
        file.appendText("{\"id\":\"s-torn\",\"mode\":\"cla") // the cut, mid-write
        log.append(record("2026-10-18T10:00"))
        val read = log.read()
        assertEquals(listOf("s-2026-10-18T09:00", "s-2026-10-18T10:00"), read.records.map { it.id.value })
        assertEquals(1, read.skipped)
    }
}
