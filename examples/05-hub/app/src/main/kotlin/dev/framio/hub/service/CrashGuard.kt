package dev.framio.hub.service

import android.content.Context
import android.os.SystemClock
import dev.framio.hub.platform.Logs
import dev.framio.hub.platform.TimeSource
import java.io.File

/**
 * Crashes: restore the backlight, write `files/last-crash.txt`, count the crash, then let the process die.
 * Three crashes within 10 minutes (same boot) start the next Hub in safe mode: clock, ring and timers only,
 * with feeds and servers off, so a bad calendar or a bad network can't crash-loop the screen.
 */
object CrashGuard {
    private const val PREFS = "crashes"
    private const val KEY = "recent"
    private const val WINDOW_MS = 10 * 60_000L
    const val SAFE_MODE_CRASHES = 3

    fun install(context: Context, beforeDying: () -> Unit) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                beforeDying()
                File(context.filesDir, "last-crash.txt").writeText(
                    "time ${System.currentTimeMillis()} elapsed ${SystemClock.elapsedRealtime()} thread ${thread.name}\n" + error.stackTraceToString(),
                )
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val list = recent(context) + "${TimeSource.readBootId()}|${SystemClock.elapsedRealtime()}"
                prefs.edit().putString(KEY, list.takeLast(10).joinToString(",")).commit() // commit: we're dying
                Logs.warn("crash", "uncaught on ${thread.name}", error)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun recent(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "")!!.split(",").filter { it.isNotBlank() }

    /** Crashes in this boot within the last 10 minutes. */
    fun recentCount(context: Context): Int {
        val boot = TimeSource.readBootId()
        val now = SystemClock.elapsedRealtime()
        return recent(context).count { entry ->
            val (b, t) = entry.split("|").let { it[0] to (it.getOrNull(1)?.toLongOrNull() ?: 0) }
            b == boot && now - t < WINDOW_MS
        }
    }

    fun safeMode(context: Context) = recentCount(context) >= SAFE_MODE_CRASHES

    /** The last crash report, if the previous run crashed, for the "Recovered from a problem" chip. */
    fun lastCrash(context: Context): String? = File(context.filesDir, "last-crash.txt").takeIf { it.exists() }?.readText()

    fun acknowledge(context: Context) {
        File(context.filesDir, "last-crash.txt").delete()
    }
}
