package dev.framio.meadow.remote

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A deliberately small HTTP/1.1: one request per connection (every response says
 * `Connection: close`, so four worker threads are never held by idle keep-alive sockets), a request
 * line and headers of at most [MAX_HEADER] bytes, and a body only with `Content-Length`.
 * Pure Java I/O, unit-tested.
 */
class HttpException(val status: Int, message: String) : IOException(message)

class Request(
    val method: String,
    /** Decoded path, always starting with "/". */
    val path: String,
    val query: Map<String, String>,
    /** Lower-case names. */
    val headers: Map<String, String>,
    val contentLength: Long,
    private val rawBody: InputStream,
    private val onFirstRead: () -> Unit = {},
) {
    var remote: String = ""

    /** The body, at most [contentLength] bytes. Sends "100 Continue" first if the client asked for it. */
    val body: InputStream by lazy {
        onFirstRead()
        rawBody
    }

    fun header(name: String): String? = headers[name.lowercase()]

    fun cookie(name: String): String? = header("cookie")?.split(';')?.firstNotNullOfOrNull { part ->
        val i = part.indexOf('=')
        if (i > 0 && part.substring(0, i).trim() == name) part.substring(i + 1).trim() else null
    }

    /** The body as text, refused when larger than [max]. */
    fun text(max: Int = 64 * 1024): String {
        if (contentLength > max) throw HttpException(413, "Request too large")
        val out = ByteArrayOutputStream()
        body.copyTo(out)
        return out.toString("UTF-8")
    }

    fun json(): JSONObject = try {
        val t = text()
        if (t.isBlank()) JSONObject() else JSONObject(t)
    } catch (e: HttpException) {
        throw e
    } catch (e: Exception) {
        throw HttpException(400, "That wasn't valid JSON")
    }
}

class Response(
    val status: Int,
    val contentType: String = "application/octet-stream",
    val body: ByteArray = ByteArray(0),
    val file: File? = null,
    val headers: MutableMap<String, String> = LinkedHashMap(),
) {
    val length: Long get() = file?.length() ?: body.size.toLong()

    fun header(name: String, value: String) = apply { headers[name] = value }

    /** For files and sprites that never change while the app runs. */
    fun cacheFor(seconds: Int) = header("Cache-Control", "private, max-age=$seconds")

    companion object {
        fun json(j: JSONObject, status: Int = 200) = Response(status, "application/json; charset=utf-8", j.toString().toByteArray())
        fun json(a: JSONArray, status: Int = 200) = Response(status, "application/json; charset=utf-8", a.toString().toByteArray())
        fun ok() = json(JSONObject().put("ok", true))
        fun error(status: Int, message: String) = json(JSONObject().put("error", message), status)
        fun bytes(data: ByteArray, type: String) = Response(200, type, data)
        fun file(f: File, type: String) = Response(200, type, file = f)
    }
}

object Http {
    const val MAX_HEADER = 16 * 1024
    private const val MAX_HEADERS = 100

    /**
     * Reads the request line and headers from [input] (which must support single-byte reads cheaply,
     * e.g. a BufferedInputStream). The body is left in the stream, wrapped to stop at Content-Length.
     * [deadlineMs] bounds the whole header phase, so a client trickling one byte at a time can't hold
     * a worker forever. Returns null when the client closed the connection before sending anything.
     */
    fun parse(
        input: InputStream,
        maxBody: Long,
        deadlineMs: Long = Long.MAX_VALUE,
        now: () -> Long = System::currentTimeMillis,
        onContinue: () -> Unit = {},
    ): Request? {
        var budget = MAX_HEADER
        fun line(): String? {
            val b = ByteArrayOutputStream(128)
            while (true) {
                if (now() > deadlineMs) throw HttpException(408, "Too slow")
                val c = input.read()
                if (c < 0) return if (b.size() == 0) null else throw HttpException(400, "Unexpected end of request")
                if (--budget < 0) throw HttpException(431, "Headers too large")
                if (c == '\n'.code) break
                b.write(c)
            }
            val bytes = b.toByteArray()
            val n = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
            return String(bytes, 0, n, Charsets.ISO_8859_1)
        }

        var first = line() ?: return null
        while (first.isEmpty()) first = line() ?: return null // tolerate a stray CRLF before the request
        val parts = first.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpException(400, "Bad request line")
        val method = parts[0]
        if (method.isEmpty() || !method.all { it in 'A'..'Z' }) throw HttpException(400, "Bad method")
        val target = parts[1]
        if (!target.startsWith("/")) throw HttpException(400, "Bad target")

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val h = line() ?: throw HttpException(400, "Unexpected end of headers")
            if (h.isEmpty()) break
            if (headers.size >= MAX_HEADERS) throw HttpException(431, "Too many headers")
            val i = h.indexOf(':')
            if (i <= 0) throw HttpException(400, "Bad header")
            val name = h.substring(0, i).trim().lowercase()
            val value = h.substring(i + 1).trim()
            headers[name] = headers[name]?.let { if (name == "cookie") "$it; $value" else "$it, $value" } ?: value
        }

        if (headers.containsKey("transfer-encoding")) throw HttpException(411, "Please send a Content-Length")
        val length = headers["content-length"]?.let { it.toLongOrNull()?.takeIf { n -> n >= 0 } ?: throw HttpException(400, "Bad Content-Length") } ?: 0L
        if (length > maxBody) throw HttpException(413, "That file is too large (max ${maxBody / (1024 * 1024)} MB)")

        val q = target.indexOf('?')
        val rawPath = if (q >= 0) target.substring(0, q) else target
        val path = decode(rawPath, plus = false)
        if (path.contains('\u0000')) throw HttpException(400, "Bad path")
        val query = if (q >= 0) parseQuery(target.substring(q + 1)) else emptyMap()
        val wantsContinue = headers["expect"]?.equals("100-continue", ignoreCase = true) == true && length > 0
        return Request(method, path, query, headers, length, Bounded(input, length), if (wantsContinue) onContinue else ({}))
    }

    fun parseQuery(s: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (pair in s.split('&')) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            val k = decode(if (i >= 0) pair.substring(0, i) else pair, plus = true)
            val v = if (i >= 0) decode(pair.substring(i + 1), plus = true) else ""
            if (k.isNotEmpty() && k !in m) m[k] = v
        }
        return m
    }

    /** Percent-decoding as UTF-8; malformed escapes are a 400, not a crash. */
    fun decode(s: String, plus: Boolean): String {
        if ('%' !in s && !(plus && '+' in s)) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '%' -> {
                    if (i + 2 >= s.length) throw HttpException(400, "Bad escape")
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16) ?: throw HttpException(400, "Bad escape")
                    out.write(v)
                    i += 3
                    continue
                }
                c == '+' && plus -> out.write(' '.code)
                else -> out.write(c.toString().toByteArray(Charsets.UTF_8))
            }
            i++
        }
        return out.toString("UTF-8")
    }

    fun reason(status: Int): String = when (status) {
        100 -> "Continue"
        200 -> "OK"
        204 -> "No Content"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        408 -> "Request Timeout"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        415 -> "Unsupported Media Type"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        503 -> "Service Unavailable"
        else -> if (status >= 500) "Server Error" else "Status"
    }

    /** Writes [r] (headers only for HEAD). */
    fun write(out: OutputStream, r: Response, head: Boolean = false) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n")
        sb.append("Content-Type: ").append(r.contentType).append("\r\n")
        sb.append("Content-Length: ").append(r.length).append("\r\n")
        sb.append("Connection: close\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        if ("Cache-Control" !in r.headers) sb.append("Cache-Control: no-store\r\n")
        for ((k, v) in r.headers) {
            require('\r' !in v && '\n' !in v) { "header injection" }
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (!head) {
            val f = r.file
            if (f != null) f.inputStream().use { it.copyTo(out, 32 * 1024) } else out.write(r.body)
        }
        out.flush()
    }

    fun writeContinue(out: OutputStream) {
        out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    /** By file extension, for the few kinds the dashboard serves. */
    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "ttf" -> "font/ttf"
        "ico" -> "image/x-icon"
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "mp3" -> "audio/mpeg"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "webm" -> "audio/webm"
        "3gp" -> "audio/3gpp"
        "amr" -> "audio/amr"
        else -> "application/octet-stream"
    }

    /** The file extension for an upload, from its Content-Type, else from a file name; null if not allowed. */
    fun uploadExt(contentType: String?, fileName: String?, photo: Boolean): String? {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val byType = if (photo) when (type) {
            "image/jpeg", "image/jpg", "image/pjpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> null
        } else when (type) {
            "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac-adts", "video/mp4" -> "m4a"
            "audio/aac", "audio/x-aac" -> "aac"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/ogg", "application/ogg", "audio/opus" -> "ogg"
            "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "wav"
            "audio/webm", "video/webm" -> "webm"
            "audio/3gpp", "video/3gpp", "audio/3gpp2" -> "3gp"
            "audio/amr" -> "amr"
            else -> null
        }
        if (byType != null) return byType
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val ok = if (photo) setOf("jpg", "jpeg", "png", "webp") else setOf("m4a", "mp4", "aac", "mp3", "ogg", "oga", "opus", "wav", "webm", "3gp", "amr")
        return ext.takeIf { it in ok }
    }

    /** Stops at [left] bytes, so a handler can never read into the next request (or block past the body). */
    private class Bounded(input: InputStream, private var left: Long) : FilterInputStream(input) {
        override fun read(): Int {
            if (left <= 0) return -1
            val c = super.read()
            if (c < 0) throw IOException("The connection closed before the whole body arrived")
            left--
            return c
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            if (len == 0) return 0
            val n = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) throw IOException("The connection closed before the whole body arrived")
            left -= n
            return n
        }

        override fun available(): Int = minOf(super.available().toLong(), left).toInt()
        override fun close() = Unit // the socket owns the stream
    }
}
