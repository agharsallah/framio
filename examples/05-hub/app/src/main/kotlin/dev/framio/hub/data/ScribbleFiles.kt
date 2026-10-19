package dev.framio.hub.data

import android.content.Context
import android.util.AtomicFile
import dev.framio.hub.core.board.ScribbleCodec
import dev.framio.hub.core.board.Stroke
import dev.framio.hub.platform.Logs
import java.io.File
import java.io.IOException

/** Scribble strokes, one small binary file each (`files/scribbles/<id>.bin`), written atomically. */
class ScribbleFiles(context: Context) {
    private val dir = File(context.filesDir, "scribbles").apply { mkdirs() }
    private val cache = HashMap<String, List<Stroke>>()

    private fun file(id: String): AtomicFile {
        require(id.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "bad scribble id" }
        return AtomicFile(File(dir, "$id.bin"))
    }

    fun write(id: String, strokes: List<Stroke>) {
        val f = file(id)
        val out = f.startWrite()
        try {
            out.write(ScribbleCodec.encode(strokes))
            f.finishWrite(out)
            synchronized(cache) { cache[id] = strokes }
        } catch (e: IOException) {
            f.failWrite(out)
            throw e
        }
    }

    /** Empty when the file is missing or unreadable (a note without its drawing is still shown). */
    fun read(id: String): List<Stroke> = synchronized(cache) { cache[id] } ?: try {
        ScribbleCodec.decode(file(id).readFully()).also { synchronized(cache) { cache[id] = it } }
    } catch (e: IOException) {
        Logs.warn("board", "scribble $id unreadable: ${e.message}")
        emptyList()
    }

    fun delete(id: String) {
        synchronized(cache) { cache.remove(id) }
        file(id).delete()
    }
}
