package dev.framio.meadow.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * Things the family sends from the dashboard: voice messages ("Goodnight from Grandma"), photos
 * (for the family faces game and the mailbox) and a recording of the child's name, which the
 * friends then say in greetings. Stored in files/family/ with an index.json.
 */
class Family(val dir: File) {

    enum class Kind { Voice, Photo }

    data class Item(val id: String, val kind: Kind, val from: String, val file: File, val createdMs: Long, val seen: Boolean) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind.name.lowercase()).put("from", from)
            .put("file", file.name).put("createdMs", createdMs).put("seen", seen).put("bytes", file.length())
    }

    private val index = File(dir, "index.json")
    private var seq = 0
    private val state = MutableStateFlow(load())
    val items: StateFlow<List<Item>> = state.asStateFlow()

    init {
        dir.mkdirs()
    }

    val unseen: List<Item> get() = state.value.filter { !it.seen }

    /** Stores [data] (at most [maxBytes]) and returns the new item. */
    @Synchronized fun add(kind: Kind, from: String, ext: String, data: InputStream, maxBytes: Long): Item {
        // time-ordered and unique even for two uploads in the same millisecond
        val id = System.currentTimeMillis().toString(36) + (seq++ % 1296).toString(36).padStart(2, '0')
        val file = File(dir, "$id.${safeExt(ext, kind)}")
        copyLimited(data, file, maxBytes)
        val item = Item(id, kind, from.take(30).trim().ifEmpty { "Family" }, file, System.currentTimeMillis(), false)
        state.value = listOf(item) + state.value
        save()
        return item
    }

    @Synchronized fun remove(id: String) {
        state.value.firstOrNull { it.id == id }?.file?.delete()
        state.value = state.value.filterNot { it.id == id }
        save()
    }

    @Synchronized fun markSeen(id: String) {
        state.value = state.value.map { if (it.id == id) it.copy(seen = true) else it }
        save()
    }

    fun nameClip(): File? = dir.listFiles { f -> f.name.startsWith("name.") }?.firstOrNull()

    @Synchronized fun setNameClip(ext: String, data: InputStream, maxBytes: Long) {
        dir.listFiles { f -> f.name.startsWith("name.") }?.forEach { it.delete() }
        copyLimited(data, File(dir, "name.${safeExt(ext, Kind.Voice)}"), maxBytes)
    }

    fun photos(): List<Item> = state.value.filter { it.kind == Kind.Photo }

    private fun safeExt(ext: String, kind: Kind): String {
        val e = ext.lowercase().filter { it.isLetterOrDigit() }.take(5)
        val ok = if (kind == Kind.Photo) setOf("jpg", "jpeg", "png", "webp") else setOf("m4a", "mp4", "aac", "ogg", "oga", "opus", "mp3", "wav", "3gp", "amr", "webm")
        return if (e in ok) e else if (kind == Kind.Photo) "jpg" else "m4a"
    }

    private fun copyLimited(input: InputStream, file: File, maxBytes: Long) {
        val tmp = File(dir, file.name + ".part")
        var total = 0L
        tmp.outputStream().use { out ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) {
                    out.close()
                    tmp.delete()
                    throw IllegalArgumentException("too large (max ${maxBytes / 1024 / 1024} MB)")
                }
                out.write(buf, 0, n)
            }
        }
        tmp.renameTo(file)
    }

    private fun load(): List<Item> {
        val a = JsonFile.read(index)?.optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val j = a.optJSONObject(i) ?: return@mapNotNull null
            val f = File(dir, j.optString("file"))
            if (!f.exists()) return@mapNotNull null
            Item(j.optString("id"), if (j.optString("kind") == "photo") Kind.Photo else Kind.Voice, j.optString("from"), f, j.optLong("createdMs"), j.optBoolean("seen"))
        }
    }

    private fun save() {
        val a = JSONArray()
        state.value.forEach { a.put(it.toJson()) }
        JsonFile.write(index, JSONObject().put("items", a))
    }
}
