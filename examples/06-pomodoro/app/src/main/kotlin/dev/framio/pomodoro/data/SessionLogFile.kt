package dev.framio.pomodoro.data

import dev.framio.pomodoro.core.log.SessionLog
import dev.framio.pomodoro.core.model.SessionRecord
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * The session log: JSON Lines, one finished session per line, append-only, fsynced on every append (a power cut is
 * an ordinary event on a photo frame). If the last line was torn by a power cut, the next append starts on a new
 * line, so only the torn record is lost; [SessionLog.decode] skips it. Not thread-safe: one writer (the container
 * hands appends to a single coroutine).
 */
class SessionLogFile(private val file: File) {
    fun append(record: SessionRecord) {
        val line = SessionLog.encode(record)
        val torn = endsWithoutNewline()
        FileOutputStream(file, true).use { out ->
            val text = (if (torn) "\n" else "") + line + "\n"
            out.write(text.encodeToByteArray())
            out.flush()
            out.fd.sync()
        }
    }

    /** Bytes in the file: grows with every append (the stats cache's checkpoint). */
    val length: Long get() = if (file.exists()) file.length() else 0L

    /** Several lines at once, one fsync (debug seeding). */
    fun appendLines(lines: List<String>) = LineFile(file).append(lines)

    /** The raw non-blank lines (debug seeding). */
    fun lines(): List<String> = if (!file.exists()) emptyList() else file.readLines().filter { it.isNotBlank() }

    /** Replaces the file atomically (debug seeding removes its lines). */
    fun rewrite(lines: List<String>) = LineFile(file).rewrite(lines)

    /** Every readable record; a missing file is an empty log. */
    fun read(): SessionLog.Decoded =
        if (!file.exists()) SessionLog.Decoded(emptyList(), 0) else SessionLog.decode(file.readText())

    private fun endsWithoutNewline(): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return RandomAccessFile(file, "r").use { raf ->
            raf.seek(raf.length() - 1)
            raf.read() != '\n'.code
        }
    }
}
