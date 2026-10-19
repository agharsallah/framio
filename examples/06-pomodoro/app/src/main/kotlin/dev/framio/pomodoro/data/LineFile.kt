package dev.framio.pomodoro.data

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * An append-only text file of lines, fsynced on every append, like the session log: after a torn last line (a power
 * cut) the next append starts on a new line, so only the torn line is lost. [rewrite] replaces the whole file
 * atomically (temp file, fsync, rename) for compaction. One writer.
 */
class LineFile(val file: File) {
    fun append(lines: List<String>) {
        if (lines.isEmpty()) return
        val torn = endsWithoutNewline()
        FileOutputStream(file, true).use { out ->
            val text = buildString {
                if (torn) append('\n')
                lines.forEach { append(it).append('\n') }
            }
            out.write(text.encodeToByteArray())
            out.flush()
            out.fd.sync()
        }
    }

    fun append(line: String) = append(listOf(line))

    fun readText(): String = if (file.exists()) file.readText() else ""

    val length: Long get() = if (file.exists()) file.length() else 0L

    fun rewrite(lines: List<String>) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(lines.joinToString("") { "$it\n" }.encodeToByteArray())
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun endsWithoutNewline(): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return RandomAccessFile(file, "r").use { raf ->
            raf.seek(raf.length() - 1)
            raf.read() != '\n'.code
        }
    }
}
