package dev.framio.lab.labs.network

import android.os.SystemClock
import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tiny HTTP server on the frame (plain java.net, one thread per request).
 * Shows the frame can be reached from the LAN: a phone can control a dashboard through it.
 *   GET /        → small HTML page
 *   GET /status  → JSON
 */
class LocalServer(private val port: Int, private val status: () -> String) {

    private var socket: ServerSocket? = null
    val requests = AtomicInteger()

    val running get() = socket?.isClosed == false
    val boundPort get() = socket?.localPort ?: -1

    fun start() {
        if (running) return
        val s = ServerSocket(port)
        socket = s
        Thread({
            while (!s.isClosed) {
                val client = try { s.accept() } catch (_: Exception) { break }
                Thread { handle(client) }.start()
            }
        }, "LocalServer").apply { isDaemon = true; start() }
    }

    fun stop() {
        socket?.close()
        socket = null
    }

    private fun handle(client: Socket): Unit = client.use { c ->
        try {
            c.soTimeout = 5000
            val requestLine = c.getInputStream().bufferedReader().readLine() ?: return@use
            val path = requestLine.split(' ').getOrNull(1) ?: "/"
            requests.incrementAndGet()
            val (type, body) = when (path) {
                "/status" -> "application/json" to status()
                else -> "text/html; charset=utf-8" to
                    "<!doctype html><meta name=viewport content='width=device-width'><title>Frame</title>" +
                    "<body style='font:18px sans-serif;background:#111;color:#eee;padding:24px'><h1>Veidoo F10</h1>" +
                    "<p>Served by Hardware Lab on the frame.</p><pre id=s></pre>" +
                    "<script>fetch('/status').then(r=>r.json()).then(j=>s.textContent=JSON.stringify(j,null,2))</script>"
            }
            val bytes = body.toByteArray()
            c.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes)
                flush()
            }
        } catch (e: Exception) {
            Log.w("LocalServer", "request failed", e)
            Unit
        }
    }

    companion object {
        fun uptimeJson(extra: Map<String, Any?>): String {
            val fields = mapOf("uptimeMs" to SystemClock.elapsedRealtime(), "time" to System.currentTimeMillis()) + extra
            return fields.entries.joinToString(",", "{", "}") { (k, v) ->
                "\"$k\":" + if (v is Number || v is Boolean) "$v" else "\"${v.toString().replace("\"", "\\\"")}\""
            }
        }
    }
}
