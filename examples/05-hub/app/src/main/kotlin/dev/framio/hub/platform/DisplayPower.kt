package dev.framio.hub.platform

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

/**
 * Turns the backlight off at night and back on, through [RootShell]. Day and evening dimming use the
 * window's own brightness instead (no permission, only our window), which the activity sets.
 *
 * The rule: whenever the Hub leaves the screen, crashes or starts, the backlight is restored first. The level
 * restored is the one read from the frame in day mode, never a number hard-coded here.
 */
class DisplayPower(private val context: Context, private val root: RootShell) {
    private val mutex = Mutex()

    /** What we last wrote: null = we haven't touched the backlight (it's whatever Android set). */
    @Volatile var written: Int? = null
        private set

    /** The day level, set from settings (dayBacklight) or read from the frame before the first night. */
    @Volatile var dayLevel: Int? = null

    val isOff get() = written == 0

    /** Reads the current level while the frame is in day mode, so night knows what to restore. */
    suspend fun learnDayLevel(): Int? = mutex.withLock {
        if (written != null) return@withLock dayLevel
        root.readBacklight()?.takeIf { it > 0 }?.also { dayLevel = it }
    }

    suspend fun off() = set(0)

    /** The morning ramp: [fraction] 0..1 of the day level, never fully off once it has started. */
    suspend fun ramp(fraction: Float) = set(((restoreLevel()) * fraction.coerceIn(0f, 1f)).roundToInt().coerceAtLeast(1))

    /** Back to day brightness. Does nothing if we never changed it. */
    suspend fun restore() {
        if (written == null) return
        set(restoreLevel())
        written = null
    }

    /** At start: if a killed Hub left the backlight off (kill -9 at night skips every handler), turn it on. */
    suspend fun recoverAtStart() = mutex.withLock {
        val now = root.readBacklight()
        if (now == 0) {
            root.backlight(restoreLevel())
            Logs.event("display", "backlight was left off; restored")
        }
    }

    private suspend fun set(level: Int) = mutex.withLock {
        if (written == level) return@withLock
        if (root.backlight(level)) written = level
    }

    private fun restoreLevel(): Int = dayLevel ?: systemLevel()

    /** Android's own brightness setting, the fallback when the Hub never saw the frame in day mode. */
    private fun systemLevel(): Int = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS).coerceIn(10, 255)
    } catch (_: Settings.SettingNotFoundException) {
        FALLBACK
    }

    /**
     * For the crash handler: restore synchronously, without coroutines (the process is about to die).
     * Only when we had turned it down.
     */
    fun restoreBlocking() {
        if (written == null) return
        try {
            val p = ProcessBuilder("su", "-c", "echo ${restoreLevel()} > ${RootShell.BACKLIGHT}").start()
            val deadline = System.currentTimeMillis() + 1_500
            while (System.currentTimeMillis() < deadline) {
                try {
                    p.exitValue()
                    break
                } catch (_: IllegalThreadStateException) {
                    Thread.sleep(20)
                }
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        /** Only if Android has no brightness setting at all; a mid level that's readable in a room. */
        private const val FALLBACK = 128
    }
}
