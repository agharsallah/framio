package dev.framio.mawaqit.audio

import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Log
import dev.framio.mawaqit.core.AdhanFiles
import dev.framio.mawaqit.core.AdhanSettings
import dev.framio.mawaqit.core.AudioType
import dev.framio.mawaqit.core.CustomAdhan
import dev.framio.mawaqit.data.SettingsStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * The adhans the user adds (LAN upload, a link, or the frame's file picker), all through [import]: the bytes go
 * to a temporary file in files/audio, the content (never the client's file name) must be a format Android 6 plays
 * and must decode, then the file is renamed to custom-<random id>.<ext> and listed in settings.adhan.custom.
 * Blocking: call from a worker thread.
 */
class CustomAdhans(private val dir: File, private val store: SettingsStore) {

    /** A file or request we refuse; [code] is the RPC error code. */
    class Rejected(val code: String, message: String) : Exception(message)

    private val lock = Any()
    private val random = SecureRandom()

    init {
        dir.mkdirs()
        cleanUp()
    }

    val list: List<CustomAdhan> get() = store.current.adhan.custom

    fun file(id: String): File? = fileOf(dir, id)

    /** Before reading a body: a full list or an announced size over the limit is refused before the bytes come. */
    fun checkRoom(announcedBytes: Long = -1) {
        if (announcedBytes > AdhanFiles.MAX_BYTES) throw Rejected("too_large", "The file is over ${AdhanFiles.MAX_BYTES / 1024 / 1024} MB")
        if (list.size >= AdhanFiles.MAX_COUNT) throw Rejected("too_many", "Already ${AdhanFiles.MAX_COUNT} adhans: delete one first")
    }

    /** Reads [input] to its end (at most [AdhanFiles.MAX_BYTES]), validates and stores it. */
    fun import(input: InputStream, name: String?): CustomAdhan {
        checkRoom()
        val part = File(dir, ".upload-" + AdhanFiles.newId(random::nextInt) + ".part")
        try {
            val head = ByteArray(AudioType.HEAD)
            var headLen = 0
            var total = 0L
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > AdhanFiles.MAX_BYTES) throw Rejected("too_large", "The file is over ${AdhanFiles.MAX_BYTES / 1024 / 1024} MB")
                    if (headLen < head.size) {
                        val k = minOf(n, head.size - headLen)
                        System.arraycopy(buf, 0, head, headLen, k)
                        headLen += k
                    }
                    out.write(buf, 0, n)
                }
            }
            if (total == 0L) throw Rejected("bad_request", "The file is empty")
            val type = AudioType.sniff(head.copyOf(headLen))
                ?: throw Rejected("bad_format", "Not an audio file the frame can play (mp3, m4a/aac, ogg vorbis, wav, flac)")
            val seconds = measure(part)
            synchronized(lock) {
                checkRoom()
                var id: String
                do id = AdhanFiles.newId(random::nextInt) while (list.any { it.id == id } || fileOf(dir, id) != null)
                val target = File(dir, AdhanFiles.fileName(id, type))
                if (!part.renameTo(target)) throw IOException("rename failed: $target")
                val adhan = CustomAdhan(id, AdhanFiles.cleanName(name), seconds)
                save(list + adhan)
                Log.i(TAG, "added ${target.name} (${total / 1024} KB, $seconds s, ${type.mime})")
                return adhan
            }
        } finally {
            part.delete()
        }
    }

    /** Downloads an https link (the way in from ntfy, whose messages can't carry a file). */
    fun fetch(link: String, name: String?): CustomAdhan {
        val url = try {
            URL(link.trim())
        } catch (_: MalformedURLException) {
            throw Rejected("bad_request", "Not a link")
        }
        if (url.protocol != "https") throw Rejected("bad_request", "Only https:// links")
        checkRoom()
        val c = url.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = true // never from https to http: HttpURLConnection doesn't cross protocols
            c.setRequestProperty("User-Agent", "framio-mawaqit/0.1")
            val code = try {
                c.responseCode
            } catch (e: IOException) {
                throw Rejected("network", e.message ?: "Can't reach ${url.host}")
            }
            if (code !in 200..299) throw Rejected("network", "${url.host} answered HTTP $code")
            if (c.url.protocol != "https") throw Rejected("bad_request", "Only https:// links")
            checkRoom(c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1) // getContentLengthLong is API 24
            val label = name?.takeIf { it.isNotBlank() } ?: runCatching { URLDecoder.decode(c.url.path.substringAfterLast('/'), "UTF-8") }.getOrElse { "" }
            return try {
                Limited(c.inputStream, AdhanFiles.MAX_BYTES + 1, DOWNLOAD_MS).use { import(it, label) }
            } catch (e: IOException) {
                throw Rejected("network", e.message ?: "Download failed")
            }
        } finally {
            c.disconnect()
        }
    }

    fun rename(id: String, name: String): CustomAdhan = synchronized(lock) {
        val old = list.firstOrNull { it.id == id } ?: throw Rejected("not_found", "No adhan $id")
        val renamed = old.copy(name = AdhanFiles.cleanName(name))
        save(list.map { if (it.id == id) renamed else it })
        renamed
    }

    /** Removes the file; a voice that used it goes back to the default. */
    fun delete(id: String) = synchronized(lock) {
        if (list.none { it.id == id }) throw Rejected("not_found", "No adhan $id")
        val a = store.current.adhan
        val patch = JSONObject().put("custom", JSONArray(list.filter { it.id != id }.map { it.toJson() }))
        if (a.voice == "custom:$id") patch.put("voice", AdhanSettings().voice)
        if (a.fajrVoice == "custom:$id") patch.put("fajrVoice", AdhanSettings().fajrVoice)
        store.patch(JSONObject().put("adhan", patch))
        fileOf(dir, id)?.delete()
        Log.i(TAG, "deleted $id")
    }

    private fun save(custom: List<CustomAdhan>) {
        store.patch(JSONObject().put("adhan", JSONObject().put("custom", JSONArray(custom.map { it.toJson() }))))
    }

    /** MediaPlayer, as at the adhan: a file it can't prepare is refused now rather than failing at Fajr. */
    private fun measure(f: File): Int {
        val mp = MediaPlayer()
        val ms = try {
            mp.setDataSource(f.path)
            mp.prepare()
            mp.duration
        } catch (e: Exception) { // IOException, or IllegalStateException / RuntimeException from the native side
            Log.w(TAG, "doesn't decode: ${e.message}")
            -1
        } finally {
            mp.release()
        }
        if (ms <= 0) throw Rejected("bad_format", "The frame can't play this file")
        val seconds = ((ms + 999) / 1000)
        if (seconds > AdhanFiles.MAX_SECONDS) throw Rejected("too_long", "Longer than ${AdhanFiles.MAX_SECONDS / 60} minutes")
        return seconds
    }

    /** Leftovers of an interrupted upload, and files no longer listed (settings restored, crash between steps). */
    private fun cleanUp() {
        val ids = list.map { it.id }.toSet()
        for (f in dir.listFiles().orEmpty()) {
            val n = f.name
            val stale = n.startsWith(".upload-") || (n.startsWith("custom-") && n.removePrefix("custom-").substringBefore('.') !in ids)
            if (stale && f.delete()) Log.i(TAG, "removed stale $n")
        }
    }

    /** At most [limit] bytes, and the whole read within [totalMs]: a slow client can't hold a worker forever. */
    class Limited(input: InputStream, private var limit: Long, totalMs: Long) : FilterInputStream(input) {
        private val deadline = SystemClock.elapsedRealtime() + totalMs

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (limit <= 0) return -1
            if (SystemClock.elapsedRealtime() > deadline) throw IOException("too slow")
            val n = super.read(b, off, minOf(len.toLong(), limit).toInt())
            if (n > 0) limit -= n
            return n
        }
    }

    companion object {
        private const val TAG = "Adhan"
        private const val DOWNLOAD_MS = 75_000L
        private val ID = Regex("[a-z0-9]{4,32}")

        /** The stored file of adhan [id], whatever its extension; null if none (or [id] isn't an id). */
        fun fileOf(dir: File, id: String): File? =
            if (!ID.matches(id)) null else AudioType.entries.map { File(dir, AdhanFiles.fileName(id, it)) }.firstOrNull { it.isFile }
    }
}
