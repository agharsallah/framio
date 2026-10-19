package dev.framio.lab.labs.system

import android.content.Context
import android.webkit.WebSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.framio.lab.core.Check
import dev.framio.lab.core.CheckList
import dev.framio.lab.core.Format
import dev.framio.lab.core.Lab
import dev.framio.lab.core.LocalHost
import dev.framio.lab.core.Ring
import dev.framio.lab.core.ui.ActionButton
import dev.framio.lab.core.ui.Actions
import dev.framio.lab.core.ui.Amber
import dev.framio.lab.core.ui.Good
import dev.framio.lab.core.ui.InkFaint
import dev.framio.lab.core.ui.KeyValues
import dev.framio.lab.core.ui.LabPage
import dev.framio.lab.core.ui.Mono
import dev.framio.lab.core.ui.Note
import dev.framio.lab.core.ui.Section
import dev.framio.lab.core.ui.SelfTestSection
import dev.framio.lab.core.ui.Sky
import dev.framio.lab.core.ui.Sparkline
import dev.framio.lab.core.ui.StatGrid
import dev.framio.lab.core.ui.Surface2
import dev.framio.lab.core.ui.rememberPolled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** SoC, cores, memory, storage, build: what the frame is made of. */
object SystemLab {

    val lab = Lab(
        id = "system",
        title = "System",
        summary = "CPU cores and clocks, RAM, storage, build, WebView, benchmark",
        accent = Amber,
        selfTest = ::selfTest,
        content = { Content() },
    )

    private class Live {
        val usage = Ring(120)
        val avail = Ring(120)
        var prev: CpuTimes? = null
    }

    @Composable
    private fun Content() {
        val context = LocalHost.current.context
        val live = remember { Live() }
        val scope = rememberCoroutineScope()
        var bench by remember { mutableStateOf<String?>(null) }
        var benching by remember { mutableStateOf(false) }
        var userAgent by remember { mutableStateOf<String?>(null) }

        val sample by rememberPolled(500) {
            val times = SystemInfo.cpuTimes()
            live.prev?.let { p -> times?.let { live.usage.add(it.usageSince(p) * 100f) } }
            live.prev = times
            val mem = SystemInfo.memory(context)
            live.avail.add(mem.avail / 1_048_576f)
            Triple(SystemInfo.cores(), mem, live.usage.toList())
        }

        LabPage("System", "4× ARM Cortex-A7 up to 1.0 GHz, Mali-400 GPU, 1 GB RAM, 32 GB eMMC. Cores switch off when idle (hotplug).") {
            Section("CPU", note = "${SystemInfo.cpuHardware ?: "?"} · ${SystemInfo.coreName(SystemInfo.cpuPart)} · governor ${SystemInfo.governor} · steps ${SystemInfo.frequencies?.split(' ')?.joinToString(" / ") { Format.mhzFromKhz(it.toLongOrNull()) }}") {
                val (cores, _, usage) = sample ?: return@Section
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    cores.forEach { c ->
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Surface2).padding(14.dp),
                        ) {
                            Text("cpu${c.index}", style = MaterialTheme.typography.labelMedium)
                            Text(
                                if (c.online) Format.mhzFromKhz(c.curKhz) else "offline",
                                style = MaterialTheme.typography.titleLarge.copy(color = if (c.online) Good else InkFaint),
                            )
                        }
                    }
                }
                Text("Total load ${usage.lastOrNull()?.let { Format.f1(it) } ?: "–"} %", style = MaterialTheme.typography.bodyMedium)
                Sparkline(usage, Amber, min = 0f, max = 100f, capacity = 120)
                Actions {
                    ActionButton(if (benching) "Running…" else "Benchmark: 1 core, then 4 cores", {
                        benching = true
                        scope.launch {
                            val single = withContext(Dispatchers.Default) { CpuBench.unitsPerSecond(3000) }
                            val multi = multiCore(threads = 4, millis = 5000)
                            bench = "1 core ${Format.f1(single / 1000)}k units/s · 4 cores ${Format.f1(multi / 1000)}k units/s · scaling ×${Format.f2(multi / single)}"
                            benching = false
                        }
                    }, enabled = !benching)
                }
                Note("While the benchmark runs, watch the cores come online and clock up to 1008 MHz.")
                bench?.let { Text(it, style = Mono) }
            }

            Section("Memory") {
                val (_, mem, _) = sample ?: return@Section
                val (cls, large) = SystemInfo.memoryClassMb(context)
                StatGrid(
                    listOf(
                        "Total" to Format.bytes(mem.total),
                        "Available" to Format.bytes(mem.avail),
                        "Low-memory line" to Format.bytes(mem.threshold),
                        "Low now" to mem.low.toString(),
                        "zram swap" to "${Format.bytes(mem.swapTotal ?: 0)} (${Format.bytes(mem.swapFree ?: 0)} free)",
                        "Page cache" to Format.bytes(mem.cached ?: 0),
                        "App heap / large" to "$cls MB / $large MB",
                        "Low-RAM device" to SystemInfo.isLowRam(context).toString(),
                    ),
                )
                Text("Available MB", style = MaterialTheme.typography.labelMedium)
                Sparkline(live.avail.toList(), Sky, capacity = 120, height = 64.dp)
            }

            Section("Storage") {
                StatGrid(SystemInfo.volumes(context).map { "${it.name} (${it.path})" to "${Format.bytes(it.free)} free of ${Format.bytes(it.total)}" }, columns = 2)
            }

            Section("Build") {
                KeyValues(SystemInfo.build() + listOf("Uptime" to Format.duration(SystemInfo.uptimeMs()), "ADB enabled" to SystemInfo.adbEnabled(context).toString()))
            }

            Section("WebView", note = "Chromium 107 is the last WebView for Android 6; dashboards must target it.") {
                KeyValues(listOf("Package version" to (SystemInfo.webViewVersion(context) ?: "not found")) + (userAgent?.let { listOf("User agent" to it) } ?: emptyList()))
                Actions {
                    ActionButton("Load user agent", { scope.launch { userAgent = WebSettings.getDefaultUserAgent(context) } }, primary = false)
                }
            }

            Section("Declared features", note = "What PackageManager claims. Most are a generic tablet list; the other labs check what is real.") {
                Text(SystemInfo.features(context).joinToString("  ·  ") { it.removePrefix("android.hardware.").removePrefix("android.software.") }, style = Mono)
            }

            SelfTestSection(lab.id)
        }
    }

    /** Runs [CpuBench] on [threads] threads at once and returns total units per second. */
    private suspend fun multiCore(threads: Int, millis: Long): Double = coroutineScope {
        (1..threads).map { async(Dispatchers.Default) { CpuBench.unitsPerSecond(millis) } }.awaitAll().sum()
    }

    private suspend fun selfTest(context: Context): List<Check> = CheckList(lab.id).run {
        guard("Android") {
            val patch = android.os.Build.VERSION.SECURITY_PATCH
            val year = patch.take(4).toIntOrNull() ?: 0
            if (year < 2020) warn("Android", "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), patch $patch — unpatched for years")
            else info("Android", "${android.os.Build.VERSION.RELEASE}, patch $patch")
        }
        guard("CPU") {
            val cores = SystemInfo.cores()
            info("CPU", "${cores.size}× ${SystemInfo.coreName(SystemInfo.cpuPart)}, max ${Format.mhzFromKhz(cores.first().maxKhz)}, governor ${SystemInfo.governor}, online now ${cores.count { it.online }}")
        }
        guard("/proc/stat") {
            val a = SystemInfo.cpuTimes()
            if (a != null) pass("/proc/stat", "readable: live CPU load works") else fail("/proc/stat", "not readable")
        }
        guard("Benchmark") {
            val single = withContext(Dispatchers.Default) { CpuBench.unitsPerSecond(1500) }
            val multi = multiCore(4, 2500)
            val scaling = multi / single
            val text = "1 core ${Format.f1(single / 1000)}k/s, 4 cores ${Format.f1(multi / 1000)}k/s (×${Format.f2(scaling)})"
            if (scaling > 2.5) pass("Benchmark", "$text: all 4 cores come online under load") else warn("Benchmark", "$text: fewer than 4 cores worked in parallel")
        }
        guard("RAM") {
            val m = SystemInfo.memory(context)
            val (cls, large) = SystemInfo.memoryClassMb(context)
            info("RAM", "${Format.bytes(m.total)} total, ${Format.bytes(m.avail)} free, zram ${Format.bytes(m.swapTotal ?: 0)}, heap $cls/$large MB, lowRam=${SystemInfo.isLowRam(context)}")
        }
        guard("Storage") {
            val data = SystemInfo.volumes(context).first { it.name == "Data" }
            if (data.free > 1L shl 30) pass("Storage", "${Format.bytes(data.free)} free of ${Format.bytes(data.total)}")
            else warn("Storage", "only ${Format.bytes(data.free)} free")
        }
        guard("WebView") {
            val v = SystemInfo.webViewVersion(context)
            val major = v?.substringBefore('.')?.toIntOrNull() ?: 0
            when {
                v == null -> fail("WebView", "no WebView package")
                major < 110 -> warn("WebView", "Chromium $v: works, but frozen (last version for Android 6)")
                else -> pass("WebView", "Chromium $v")
            }
        }
        items
    }
}
