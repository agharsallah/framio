package dev.framio.meadow.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Leaves a trace of what went wrong, for a frame that isn't attached to a computer when it happens:
 * crashes (the stack, before Android closes the app) and freezes (the main thread stuck for more
 * than [HANG_MS]: its stack while stuck). Files in files/diagnostics/, newest kept; read them with
 * over adb as root (`ls` / `cat` in /data/data/dev.framio.meadow/files/diagnostics).
 */
object Diagnostics {
    private const val HANG_MS = 3000L
    private const val KEEP = 8
    private lateinit var dir: File

    fun install(filesDir: File) {
        dir = File(filesDir, "diagnostics").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            write("crash", "thread ${t.name}\n" + stack(e))
            previous?.uncaughtException(t, e)
        }
        startWatchdog()
    }

    /** Recent reports, newest first: (file name, text). */
    fun reports(): List<Pair<String, String>> =
        (dir.listFiles()?.sortedByDescending { it.name } ?: emptyList()).map { it.name to it.readText() }

    private fun startWatchdog() {
        val main = Handler(Looper.getMainLooper())
        Thread({
            var reportedAt = 0L
            while (true) {
                val answered = AtomicBoolean(false)
                main.post { answered.set(true) }
                Thread.sleep(HANG_MS)
                if (!answered.get()) {
                    val now = SystemClock.uptimeMillis()
                    if (now - reportedAt > 30_000) { // one report per freeze, not one every 3 s
                        reportedAt = now
                        val trace = Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "  at $it" }
                        write("hang", "main thread stuck for over ${HANG_MS / 1000} s\n$trace")
                    }
                    while (!answered.get()) Thread.sleep(200) // wait until it recovers before probing again
                }
            }
        }, "watchdog").apply { isDaemon = true; start() }
    }

    private fun write(kind: String, body: String) {
        Log.e(TAG, "$kind: $body")
        try {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            File(dir, "$stamp-$kind.txt").writeText(body)
            dir.listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    private fun stack(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
}
