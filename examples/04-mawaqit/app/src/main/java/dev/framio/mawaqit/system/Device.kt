package dev.framio.mawaqit.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

/** The frame itself: root shell, backlight, IP address, Frameo. */
object Device {

    private const val TAG = "Device"
    const val FRAMEO = "net.frameo.frame"
    private const val BACKLIGHT = "/sys/class/backlight/rk28_bl/brightness"

    /** Runs one command as root (this SuperSU grants every app silently). Null when there is no su or it timed out. */
    suspend fun su(command: String, timeoutMs: Long = 10_000): String? = withContext(Dispatchers.IO) {
        try {
            // One command per `su -c`: this SuperSU (2.45) ignores multi-line scripts passed with -c
            val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = Thread { p.inputStream.bufferedReader().forEachLine { synchronized(out) { out.appendLine(it) } } }
                .apply { isDaemon = true; start() }
            val exit = waitFor(p, timeoutMs)
            if (exit == null) {
                p.destroy()
                null
            } else {
                reader.join(500)
                if (exit == 0) synchronized(out) { out.toString().trim() } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** `screencap -p` through root: the PNG bytes of what the frame shows right now. */
    suspend fun screenshot(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val p = ProcessBuilder("su", "-c", "screencap -p").start()
            val bytes = p.inputStream.readBytes()
            if (waitFor(p, 10_000) == 0 && bytes.size > 8) bytes else null
        } catch (_: Exception) {
            null
        }
    }

    @Volatile
    private var savedBacklight: String? = null

    /** Backlight fully off (window brightness 0 still glows). Android doesn't notice: [backlightOn] puts it back. */
    suspend fun backlightOff(): Boolean {
        if (savedBacklight == null) savedBacklight = su("cat $BACKLIGHT")?.takeIf { it.toIntOrNull()?.let { v -> v > 0 } == true }
        delay(400) // let a brightness change already on its way to the lights HAL land first
        return su("echo 0 > $BACKLIGHT") != null
    }

    /**
     * Puts back what [backlightOff] saved. With nothing saved (the app was restarted, reinstalled or killed while it
     * was off) a backlight found at 0 gets the full value, or it would stay dark until a reboot.
     */
    suspend fun backlightOn() {
        val v = savedBacklight ?: if (su("cat $BACKLIGHT") == "0") "255" else return
        su("echo $v > $BACKLIGHT")
        savedBacklight = null
    }

    suspend fun hasRoot(): Boolean = su("id")?.contains("uid=0") == true

    suspend fun reboot() {
        su("reboot")
    }

    /** The Wi-Fi IPv4 address, for the panel URL. */
    fun ipAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    } catch (_: Exception) {
        null
    }

    /** No RTC: the frame boots at 1970/01-01 and gets the time from NTP. Before then, every time we show is wrong. */
    fun clockLooksSet(now: Long = System.currentTimeMillis()) = now > 1_767_225_600_000L // 2026-01-01

    fun frameoInstalled(context: Context) = try {
        context.packageManager.getPackageInfo(FRAMEO, 0)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Frameo's task was started as HOME (MAIN + HOME): sending exactly that root intent brings the slideshow forward
     * untouched (see example 02, Apps.openPhotos). A LAUNCHER intent would re-show Frameo's red notice.
     */
    fun openPhotos(context: Context): Boolean {
        val explicit = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setComponent(ComponentName(FRAMEO, "$FRAMEO.ui.activities.AWaitForDeferredInitCompleted"))
        for (intent in listOfNotNull(explicit, context.packageManager.getLaunchIntentForPackage(FRAMEO))) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            try {
                context.startActivity(intent)
                return true
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not open Frameo", e)
            }
        }
        return false
    }

    /** The home screen (on this frame example 02's launcher, set as HOME): what HOME on a remote would show. */
    fun openHome(context: Context): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "could not open the home screen", e)
        false
    }

    /** Process.waitFor(timeout) is API 26: poll exitValue(). Null on timeout. */
    private suspend fun waitFor(p: Process, timeoutMs: Long): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                return p.exitValue()
            } catch (_: IllegalThreadStateException) {
                delay(50)
            }
        }
        return null
    }
}
