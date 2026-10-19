package dev.framio.meadow.hw

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.Window
import dev.framio.meadow.core.TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * The frame's own controls: speaker volume, screen brightness and the backlight itself.
 * Window brightness needs no permission; the backlight file needs root, which every app on this
 * frame gets silently (see docs/device-log.md, "Security").
 */
object Device {

    private const val BACKLIGHT = "/sys/class/backlight/rk28_bl/brightness"

    fun setVolume(context: Context, percent: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = (percent / 100f * max).roundToInt().coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
    }

    fun volumePercent(context: Context): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    /** 0..1 for this window only; Android restores the system level when we leave. */
    fun setWindowBrightness(window: Window, level: Float) {
        val lp = window.attributes
        lp.screenBrightness = level.coerceIn(0.02f, 1f)
        window.attributes = lp
    }

    /** Backlight completely off (true) or back to what it was. Root; no-op if su is missing. */
    suspend fun backlightOff(off: Boolean) = withContext(Dispatchers.IO) {
        if (off) {
            saved = readBacklight() ?: saved
            root("echo 0 > $BACKLIGHT")
        } else {
            root("echo ${saved.coerceIn(30, 255)} > $BACKLIGHT")
        }
    }

    @Volatile private var saved = 160

    private fun readBacklight(): Int? = try {
        File(BACKLIGHT).readText().trim().toIntOrNull()?.takeIf { it > 0 }
    } catch (_: Exception) {
        null
    }

    /** Takes a PNG of the screen (for the dashboard's "what's on the frame" view). */
    suspend fun screenshot(out: File): Boolean = withContext(Dispatchers.IO) {
        val ok = root("screencap -p ${out.absolutePath}") && out.length() > 0
        if (ok) root("chmod 644 ${out.absolutePath}")
        ok
    }

    /** One command per `su -c`: this SuperSU ignores multi-line scripts. Polls for exit (waitFor(timeout) is API 26). */
    private suspend fun root(cmd: String, timeoutMs: Long = 8000): Boolean = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val deadline = System.currentTimeMillis() + timeoutMs
        var exit: Int? = null
        while (exit == null && System.currentTimeMillis() < deadline) {
            try {
                exit = p.exitValue()
            } catch (_: IllegalThreadStateException) {
                delay(30)
            }
        }
        if (exit == null) p.destroy()
        exit == 0
    } catch (e: Exception) {
        Log.w(TAG, "root '$cmd' failed: ${e.message}")
        false
    }
}
