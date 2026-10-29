package dev.framio.launcher

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/** An app with a live process: what the Apps panel lists and closes. */
data class RunningApp(val pkg: String, val label: String, val rssKb: Long)

/**
 * The frame's own controls: volumes, brightness, memory, power. Ordinary APIs first; root (see [Root]) only for what
 * Android 6 keeps from apps.
 */
object Device {

    // --- Sound

    /** Media is what apps play (Frameo's videos, previews); alarm is what Mawaqit and Pomodoro ring on. */
    enum class Stream(val id: Int, val label: String) {
        MEDIA(AudioManager.STREAM_MUSIC, "Media"),
        ALARM(AudioManager.STREAM_ALARM, "Alarms"),
    }

    fun volume(context: Context, s: Stream): Float {
        val am = context.getSystemService(AudioManager::class.java)
        return am.getStreamVolume(s.id).toFloat() / am.getStreamMaxVolume(s.id)
    }

    /** [level] 0..1, rounded to the stream's steps (media 15, alarm 7 here). Returns the level that was set. */
    fun setVolume(context: Context, s: Stream, level: Float): Float {
        val am = context.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(s.id)
        val steps = Math.round(level.coerceIn(0f, 1f) * max)
        am.setStreamVolume(s.id, steps, 0)
        return steps.toFloat() / max
    }

    // --- Brightness

    /**
     * The system brightness, 0..1. Frameo writes the same setting from its own brightness slider and restores it when
     * it starts, so a change here lasts until someone changes it there.
     */
    fun brightness(context: Context): Float =
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 187) / 255f

    /** Never fully dark: 30/255 is the frame's own minimum (`dumpsys power`), and a black screen looks broken. */
    suspend fun setBrightness(context: Context, level: Float) {
        val v = (MIN_BRIGHTNESS + (255 - MIN_BRIGHTNESS) * level.coerceIn(0f, 1f)).toInt()
        if (Settings.System.canWrite(context)) {
            val cr = context.contentResolver
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v)
        } else {
            Root.run("settings put system screen_brightness $v")
        }
    }

    fun brightnessToLevel(raw: Float): Float = ((raw * 255 - MIN_BRIGHTNESS) / (255 - MIN_BRIGHTNESS)).coerceIn(0f, 1f)

    private const val MIN_BRIGHTNESS = 30

    // --- Backlight off ("sleep screen")

    private const val BACKLIGHT = "/sys/class/backlight/rk28_bl/brightness"

    /** Window brightness 0 still glows on this panel; only the backlight at 0 is really dark. */
    /** On, it goes back to the system brightness setting. */
    suspend fun backlight(context: Context, on: Boolean): Boolean {
        val v = if (on) (brightness(context) * 255).toInt().coerceAtLeast(MIN_BRIGHTNESS) else 0
        return Root.run("echo $v > $BACKLIGHT").ok
    }

    /** A backlight left at 0 by a killed run would stay dark until a reboot: put it back at start. */
    suspend fun recoverBacklight(context: Context) {
        if (Root.run("cat $BACKLIGHT").out.trim() == "0") backlight(context, on = true)
    }

    // --- Memory, storage, network

    data class Memory(val availMb: Long, val totalMb: Long)

    fun memory(context: Context): Memory {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return Memory(info.availMem shr 20, info.totalMem shr 20)
    }

    data class Storage(val freeGb: Float, val totalGb: Float)

    fun storage(context: Context): Storage {
        val fs = StatFs(context.filesDir.absolutePath)
        return Storage(fs.availableBytes / 1e9f, fs.totalBytes / 1e9f)
    }

    /** The Wi-Fi IPv4 address, null when offline. */
    fun ipAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    } catch (_: Exception) {
        null
    }

    fun uptime(): String {
        val m = SystemClock.elapsedRealtime() / 60_000
        return when {
            m < 60 -> "${m} min"
            m < 24 * 60 -> "${m / 60} h ${m % 60} min"
            else -> "${m / (24 * 60)} d ${m / 60 % 24} h"
        }
    }

    fun model(): String = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"

    fun bootId(): String? = try {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    } catch (_: Exception) {
        null
    }

    // --- Apps that are running

    /**
     * Every app process (users `u0_a…`) of an app with a launcher icon, grouped by package, biggest first. Only apps
     * the user opens: System UI, the keyboard and the updaters also run as `u0_a…`, and force-stopping those breaks
     * the frame. Android 6 shows other apps' processes to root only. RSS counts pages shared with zygote, so it reads
     * high; it is for comparing apps with each other.
     */
    suspend fun runningApps(context: Context): List<RunningApp> {
        val out = Root.run("ps").takeIf { it.ok }?.out ?: return emptyList()
        val pm = context.packageManager
        return withContext(Dispatchers.Default) {
            val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .mapTo(HashSet()) { it.activityInfo.packageName }
            parsePs(out)
                .filter { it.first != context.packageName && it.first in launchable }
                .groupBy({ it.first }, { it.second })
                .mapNotNull { (pkg, rss) ->
                    val label = try {
                        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
                    } catch (_: PackageManager.NameNotFoundException) {
                        return@mapNotNull null // a process named like no installed package
                    }
                    RunningApp(pkg, label, rss.sum())
                }
                .sortedByDescending { it.rssKb }
        }
    }

    /** Close an app the way Settings' "Force stop" does. Never this launcher (it is HOME: Android would restart it). */
    suspend fun close(context: Context, pkg: String): Boolean {
        if (pkg == context.packageName) return false
        if (Root.forceStop(pkg)) return true
        // Without root: only background processes, and only if Android agrees
        context.getSystemService(ActivityManager::class.java).killBackgroundProcesses(pkg)
        return false
    }

    /** The app in front (below our overlay), from the accessibility service when on, else from `dumpsys`. */
    suspend fun foregroundPackage(): String? {
        NavService.foreground?.let { return it }
        val out = Root.run("dumpsys activity activities").takeIf { it.ok }?.out ?: return null
        return parseResumed(out)
    }

    // --- Power

    suspend fun reboot() = Root.run("reboot").ok

    suspend fun powerOff() = Root.run("reboot -p").ok

    // --- Permissions the frame has no Settings screen for

    /**
     * The launcher grants itself, through root, what used to be `adb shell appops …` after every fresh install:
     * the overlay (the home button), writing the brightness setting, and the location permission Android 6 asks
     * before it shows Wi-Fi scan results. Each is checked first, so a provisioned frame runs no root command here.
     */
    suspend fun provision(context: Context) {
        val pkg = context.packageName
        if (!Settings.canDrawOverlays(context)) grant("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        if (!Settings.System.canWrite(context)) grant("appops set $pkg WRITE_SETTINGS allow")
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            grant("pm grant $pkg ${Manifest.permission.ACCESS_COARSE_LOCATION}")
        }
    }

    private suspend fun grant(command: String) {
        val r = Root.run(command)
        Log.i(TAG, "$command → ${if (r.ok) "ok" else "failed (${r.exit}: ${r.out})"}")
    }

    // --- The accessibility service behind the home button's Back

    private fun navComponent(context: Context) = "${context.packageName}/${NavService::class.java.name}"

    fun navEnabled(context: Context): Boolean =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { ComponentName.unflattenFromString(it)?.className == NavService::class.java.name } == true

    /** Adds (or removes) our service to the enabled list, keeping any other one there. */
    suspend fun setNavEnabled(context: Context, on: Boolean): Boolean {
        val mine = navComponent(context)
        val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.filter { it.isNotBlank() && it != mine }.orEmpty()
        val list = if (on) current + mine else current
        val value = list.joinToString(":").ifEmpty { "\"\"" }
        // Values come from Settings and our own component name; each one is still checked before it goes to a shell
        require(list.all { ComponentName.unflattenFromString(it)?.packageName?.let(Root::pkg) != null })
        val ok = Root.run("settings put secure enabled_accessibility_services $value").ok
        if (ok && on) Root.run("settings put secure accessibility_enabled 1")
        return ok
    }

    private const val TAG = "Device"
}

/** (package, RSS kB) of each app process in `ps` output. Columns are found by name: toolbox and toybox differ. */
internal fun parsePs(out: String): List<Pair<String, Long>> {
    val lines = out.lines().filter { it.isNotBlank() }
    val header = lines.firstOrNull()?.trim()?.split(Regex("\\s+")) ?: return emptyList()
    val user = header.indexOf("USER")
    val rss = header.indexOf("RSS")
    if (user < 0 || rss < 0) return emptyList()
    return lines.drop(1).mapNotNull { line ->
        val cols = line.trim().split(Regex("\\s+"))
        if (cols.size < header.size || !cols[user].startsWith("u0_a")) return@mapNotNull null
        val name = cols.last() // NAME is the last column in both formats
        val pkg = name.substringBefore(':')
        if (!pkg.contains('.')) return@mapNotNull null
        pkg to (cols[rss].toLongOrNull() ?: 0L)
    }
}

/** The package of `mResumedActivity: ActivityRecord{… u0 pkg/.Activity t12}` in `dumpsys activity activities`. */
internal fun parseResumed(out: String): String? {
    val line = out.lineSequence().firstOrNull { it.contains("mResumedActivity") } ?: return null
    return Regex("\\s([A-Za-z][\\w.]*)/").find(line)?.groupValues?.get(1)
}
