package dev.framio.lab

import android.app.Application
import dev.framio.lab.core.Events
import dev.framio.lab.labs.background.BackgroundService
import dev.framio.lab.labs.background.BackgroundState

/**
 * Counts process starts. A start that wasn't caused by the user opening the app means Android
 * killed the process and brought it back (a sticky service, an alarm, the boot broadcast).
 */
class LabApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = BackgroundState.prefs(this)
        val n = prefs.getInt(BackgroundState.PROCESS_STARTS, 0) + 1
        val lastBeat = prefs.getLong(BackgroundState.LAST_BEAT, 0L)
        val serviceWanted = prefs.getBoolean(BackgroundState.SERVICE_WANTED, false)
        prefs.edit().putInt(BackgroundState.PROCESS_STARTS, n).putLong(BackgroundState.LAST_PROCESS_START, System.currentTimeMillis()).apply()
        Events.log(
            BackgroundService.LAB,
            "process start #$n, pid ${BackgroundState.pid}" +
                if (serviceWanted && lastBeat > 0) ", service was running until ${(System.currentTimeMillis() - lastBeat) / 1000} s ago" else "",
        )
    }
}
