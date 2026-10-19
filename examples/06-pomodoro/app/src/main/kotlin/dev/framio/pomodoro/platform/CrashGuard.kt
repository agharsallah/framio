package dev.framio.pomodoro.platform

import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * Crashes: restore the backlight first (a crash in Blackout must not leave the frame dark), write
 * `files/last-crash.txt`, then let the process die as usual.
 */
object CrashGuard {
    fun install(context: Context, beforeDying: () -> Unit) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                beforeDying()
                File(context.filesDir, "last-crash.txt").writeText(
                    "time ${System.currentTimeMillis()} elapsed ${SystemClock.elapsedRealtime()} thread ${thread.name}\n" +
                        error.stackTraceToString(),
                )
                Logs.warn("crash", "uncaught on ${thread.name}", error)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
