package dev.framio.lab.labs.system

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import dev.framio.lab.core.Sysfs
import java.io.File

/** Reads CPU, memory, storage and build facts through public APIs and /proc + /sys. */
object SystemInfo {

    data class Core(val index: Int, val online: Boolean, val curKhz: Long?, val maxKhz: Long?)

    fun cores(): List<Core> {
        val present = Sysfs.read("/sys/devices/system/cpu/present") // "0-3"
        val count = present?.substringAfter('-')?.toIntOrNull()?.plus(1) ?: Runtime.getRuntime().availableProcessors()
        return (0 until count).map { i ->
            val base = "/sys/devices/system/cpu/cpu$i"
            // cpu0 has no "online" file: it can't be unplugged
            val online = Sysfs.read("$base/online")?.let { it == "1" } ?: (i == 0)
            Core(i, online, Sysfs.readLong("$base/cpufreq/scaling_cur_freq"), Sysfs.readLong("$base/cpufreq/cpuinfo_max_freq"))
        }
    }

    fun cpuTimes(): CpuTimes? = Sysfs.read("/proc/stat")?.lineSequence()?.firstOrNull()?.let(CpuTimes::parse)

    val governor get() = Sysfs.read("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
    val frequencies get() = Sysfs.read("/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_frequencies")
    val cpuHardware get() = Sysfs.read("/proc/cpuinfo")?.lineSequence()?.firstOrNull { it.startsWith("Hardware") }?.substringAfter(':')?.trim()
    val cpuPart get() = Sysfs.read("/proc/cpuinfo")?.lineSequence()?.firstOrNull { it.startsWith("CPU part") }?.substringAfter(':')?.trim()

    /** 0xc07 is ARM's id for Cortex-A7. */
    fun coreName(part: String?) = when (part) {
        "0xc07" -> "Cortex-A7"
        "0xc05" -> "Cortex-A5"
        "0xc09" -> "Cortex-A9"
        "0xd03" -> "Cortex-A53"
        "0xd04" -> "Cortex-A35"
        else -> part ?: "?"
    }

    data class Memory(
        val total: Long, val avail: Long, val threshold: Long, val low: Boolean,
        val swapTotal: Long?, val swapFree: Long?, val cached: Long?,
    )

    fun memory(context: Context): Memory {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val meminfo = Sysfs.read("/proc/meminfo")?.lines().orEmpty().associate { line ->
            line.substringBefore(':') to (line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()?.times(1024))
        }
        return Memory(mi.totalMem, mi.availMem, mi.threshold, mi.lowMemory, meminfo["SwapTotal"], meminfo["SwapFree"], meminfo["Cached"])
    }

    fun memoryClassMb(context: Context): Pair<Int, Int> {
        val am = context.getSystemService(ActivityManager::class.java)
        return am.memoryClass to am.largeMemoryClass
    }

    fun isLowRam(context: Context) = context.getSystemService(ActivityManager::class.java).isLowRamDevice

    data class Volume(val name: String, val path: File, val total: Long, val free: Long)

    fun volumes(context: Context): List<Volume> = listOf(
        "Data" to Environment.getDataDirectory(),
        "System" to Environment.getRootDirectory(),
        "Cache" to Environment.getDownloadCacheDirectory(),
        "Shared storage" to Environment.getExternalStorageDirectory(),
    ).mapNotNull { (name, dir) ->
        try {
            val s = StatFs(dir.path)
            Volume(name, dir, s.totalBytes, s.availableBytes)
        } catch (_: Exception) {
            null
        }
    }

    fun build(): List<Pair<String, String>> = listOf(
        "Manufacturer / brand" to "${Build.MANUFACTURER} / ${Build.BRAND}",
        "Model / device" to "${Build.MODEL} / ${Build.DEVICE}",
        "Board / hardware" to "${Build.BOARD} / ${Build.HARDWARE}",
        "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "Security patch" to Build.VERSION.SECURITY_PATCH,
        "Build" to Build.DISPLAY,
        "Fingerprint" to Build.FINGERPRINT,
        "ABIs" to Build.SUPPORTED_ABIS.joinToString(),
        "Kernel" to (System.getProperty("os.version") ?: "?"),
        "Runtime" to "ART ${System.getProperty("java.vm.version")}",
        "Bootloader" to Build.BOOTLOADER,
    )

    fun webViewVersion(context: Context): String? = listOf("com.google.android.webview", "com.android.webview").firstNotNullOfOrNull {
        try {
            context.packageManager.getPackageInfo(it, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun uptimeMs() = SystemClock.elapsedRealtime()

    fun adbEnabled(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    fun features(context: Context): List<String> =
        context.packageManager.systemAvailableFeatures.mapNotNull { it.name }.sorted()
}
