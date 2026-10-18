package dev.framio.mawaqit.remote

import android.content.Context
import android.util.Log
import dev.framio.mawaqit.audio.CustomAdhans
import dev.framio.mawaqit.core.AudioType
import dev.framio.mawaqit.system.Device
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * The LAN side of the remote panel (plain java.net, like example 03's LocalServer):
 *   GET  /                    → the panel (assets/panel/index.html)
 *   POST /api/rpc             → [Rpc], header X-Pin
 *   GET  /api/screenshot      → PNG, ?pin=
 *   POST /api/adhan/upload    → a new adhan, raw body streamed to disk, header X-Pin, ?name=
 *   GET  /api/adhan/file      → an uploaded adhan (Range requests, for the panel's player), ?id=&pin=
 * Four worker threads: enough for a phone or two, and a slow client can't starve the frame.
 */
class HttpServer(private val context: Context, private val rpc: Rpc, private val pin: () -> String) {

    private var socket: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(4)

    val port get() = socket?.localPort ?: -1

    fun start(port: Int) {
        stop()
        val s = try {
            ServerSocket(port)
        } catch (e: java.io.IOException) {
            Log.e(TAG, "port $port busy", e)
            return
        }
        socket = s
        Thread({
            while (!s.isClosed) {
                val client = try { s.accept() } catch (_: SocketException) { break }
                workers.execute { handle(client) }
            }
        }, "HttpServer").apply { isDaemon = true; start() }
        Log.i(TAG, "panel on port $port")
    }

    fun stop() {
        socket?.close()
        socket = null
    }

    private class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>) {
        var body = ByteArray(0)
    }

    private fun handle(client: Socket) = client.use { c ->
        try {
            c.soTimeout = 15_000
            val input = BufferedInputStream(c.getInputStream())
            val out = c.getOutputStream()
            val req = readHead(input) ?: return@use
            // The upload streams its body to a file; every other route gets it in memory, at most 256 KiB
            if (req.method == "POST" && req.path == "/api/adhan/upload") return@use upload(req, input, out)
            if (!readBody(req, input)) return@use
            route(req, out)
        } catch (e: Exception) {
            Log.d(TAG, "request failed: ${e.message}")
        }
    }

    private fun route(req: Request, out: OutputStream) {
        when {
            req.method == "GET" && (req.path == "/" || req.path == "/index.html") ->
                send(out, 200, "text/html; charset=utf-8", context.assets.open("panel/index.html").use(InputStream::readBytes), cache = false)
            req.method == "POST" && req.path == "/api/rpc" -> rpc(req, out)
            req.method == "GET" && req.path == "/api/screenshot" -> {
                if (!authorized(req.query["pin"])) return json(out, 401, JSONObject().put("ok", false).put("error", "pin"))
                val png = runBlocking { Device.screenshot() }
                if (png == null) json(out, 503, JSONObject().put("ok", false).put("error", "no_root"))
                else send(out, 200, "image/png", png, cache = false)
            }
            req.method == "GET" && req.path == "/api/adhan/file" -> adhanFile(req, out)
            req.method == "GET" && req.path == "/favicon.ico" -> send(out, 204, "text/plain", ByteArray(0))
            else -> json(out, 404, JSONObject().put("ok", false).put("error", "not_found"))
        }
    }

    private fun rpc(req: Request, out: OutputStream) {
        if (!authorized(req.headers["x-pin"])) return json(out, 401, JSONObject().put("ok", false).put("error", "pin"))
        val body = try {
            JSONObject(String(req.body, Charsets.UTF_8))
        } catch (_: JSONException) {
            return json(out, 400, JSONObject().put("ok", false).put("error", "bad_request"))
        }
        val cmd = body.optString("cmd")
        val response = try {
            val result = runBlocking { rpc.call(cmd, body.optJSONObject("args") ?: JSONObject(), lan = true) }
            JSONObject().put("ok", true).put("result", result)
        } catch (e: Rpc.Failure) {
            JSONObject().put("ok", false).put("error", e.code).put("message", e.message)
        } catch (e: Exception) {
            Log.e(TAG, "rpc $cmd failed", e)
            JSONObject().put("ok", false).put("error", "internal").put("message", e.toString())
        }
        json(out, 200, response)
    }

    /**
     * Raw body = the audio file, Content-Length required (no chunked bodies). PIN, size and room are checked before
     * the body is read; with "Expect: 100-continue" (curl, over 1 MB) a refused file is never even sent.
     */
    private fun upload(req: Request, input: InputStream, out: OutputStream) {
        fun fail(code: Int, error: String, message: String) =
            json(out, code, JSONObject().put("ok", false).put("error", error).put("message", message))
        if (!authorized(req.headers["x-pin"])) return fail(401, "pin", "wrong PIN")
        val length = req.headers["content-length"]?.toLongOrNull()
        if (length == null || req.headers["transfer-encoding"] != null) return fail(411, "bad_request", "Content-Length required")
        try {
            rpc.adhans.checkRoom(length)
        } catch (e: CustomAdhans.Rejected) {
            return fail(if (e.code == "too_large") 413 else 409, e.code, e.message ?: e.code)
        }
        if (req.headers["expect"].equals("100-continue", ignoreCase = true)) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
            out.flush()
        }
        val adhan = try {
            rpc.adhans.import(CustomAdhans.Limited(input, length, UPLOAD_MS), req.query["name"])
        } catch (e: CustomAdhans.Rejected) {
            Log.i(TAG, "upload refused: ${e.code} ${e.message}")
            return fail(when (e.code) { "too_large" -> 413; "too_many" -> 409; "bad_format", "too_long" -> 415; else -> 400 }, e.code, e.message ?: e.code)
        }
        json(out, 200, JSONObject().put("ok", true).put("result", adhan.toJson()))
    }

    /** Streams the file, honouring one "bytes=a-b" range: browsers need 206 answers to seek in an <audio>. */
    private fun adhanFile(req: Request, out: OutputStream) {
        if (!authorized(req.query["pin"])) return json(out, 401, JSONObject().put("ok", false).put("error", "pin"))
        val file = rpc.adhans.file(req.query["id"].orEmpty())
            ?: return json(out, 404, JSONObject().put("ok", false).put("error", "not_found"))
        val type = AudioType.ofExt(file.extension)?.mime ?: "application/octet-stream"
        val size = file.length()
        val range = req.headers["range"]?.let { Regex("bytes=(\\d*)-(\\d*)").matchEntire(it.trim()) }
        var start = 0L
        var end = size - 1
        if (range != null) {
            val (a, b) = range.destructured
            when {
                a.isEmpty() && b.isNotEmpty() -> start = (size - b.toLong()).coerceAtLeast(0) // the last b bytes
                a.isNotEmpty() -> {
                    start = a.toLong()
                    if (b.isNotEmpty()) end = minOf(b.toLong(), size - 1)
                }
            }
            if (start >= size || start > end) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return out.flush()
            }
        }
        val head = buildString {
            append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: $type\r\nContent-Length: ${end - start + 1}\r\nAccept-Ranges: bytes\r\n")
            if (range != null) append("Content-Range: bytes $start-$end/$size\r\n")
            append("Connection: close\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nCache-Control: private, max-age=3600\r\n\r\n")
        }
        out.write(head.toByteArray())
        copyRange(file, start, end - start + 1, out)
        out.flush()
    }

    private fun copyRange(file: File, start: Long, count: Long, out: OutputStream) = RandomAccessFile(file, "r").use { f ->
        f.seek(start)
        val buf = ByteArray(64 * 1024)
        var left = count
        while (left > 0) {
            val n = f.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }

    /** Constant-time compare; a wrong PIN costs a second, so 6 digits can't be guessed in a useful time. */
    private fun authorized(given: String?): Boolean {
        val ok = given != null && MessageDigest.isEqual(given.trim().toByteArray(), pin().toByteArray())
        if (!ok) Thread.sleep(1000)
        return ok
    }

    private fun json(out: OutputStream, code: Int, j: JSONObject) = send(out, code, "application/json; charset=utf-8", j.toString().toByteArray(), cache = false)

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray, cache: Boolean = true) {
        val status = when (code) {
            200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 409 -> "Conflict"
            411 -> "Length Required"; 413 -> "Payload Too Large"; 415 -> "Unsupported Media Type"; else -> "Error"
        }
        val head = buildString {
            append("HTTP/1.1 $code $status\r\n")
            append("Content-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n")
            append("X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n")
            if (!cache) append("Cache-Control: no-store\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    /** HTTP/1.1 request line and headers. */
    private fun readHead(input: InputStream): Request? {
        val requestLine = line(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val l = line(input) ?: return null
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            if (headers.size > 64) return null
        }
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = try {
            target.substringAfter('?', "").split('&').filter { '=' in it }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
        } catch (_: IllegalArgumentException) {
            return null // a broken %-escape
        }
        return Request(parts[0], path, query, headers)
    }

    /** A Content-Length body of at most 256 KiB; false drops the connection. */
    private fun readBody(req: Request, input: InputStream): Boolean {
        val length = req.headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY) return false
        val body = ByteArray(length)
        var n = 0
        while (n < length) {
            val r = input.read(body, n, length - n)
            if (r < 0) return false
            n += r
        }
        req.body = body
        return true
    }

    private fun line(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }

    private companion object {
        const val TAG = "HttpServer"
        const val MAX_BODY = 256 * 1024
        /** 20 MB in 5 minutes: ~70 KB/s, slower than any Wi-Fi that can reach the frame. */
        const val UPLOAD_MS = 5 * 60_000L
    }
}
