package dev.framio.lab.labs.root

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import dev.framio.lab.core.Check
import dev.framio.lab.core.CheckList
import dev.framio.lab.core.Events
import dev.framio.lab.core.Lab
import dev.framio.lab.core.LocalHost
import dev.framio.lab.core.Root
import dev.framio.lab.core.Status
import dev.framio.lab.core.ui.ActionButton
import dev.framio.lab.core.ui.Actions
import dev.framio.lab.core.ui.Bad
import dev.framio.lab.core.ui.LabPage
import dev.framio.lab.core.ui.Mono
import dev.framio.lab.core.ui.Note
import dev.framio.lab.core.ui.Section
import dev.framio.lab.core.ui.SelfTestSection
import dev.framio.lab.core.ui.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * SuperSU root from inside the app. Everything here is read-only or undoes itself:
 * kernel and driver state, open ports, a 3 s backlight blink, dropping the page cache, a full-screen screenshot.
 */
object RootLab {

    val lab = Lab(
        id = "root",
        title = "Root",
        summary = "su from the app: kernel state, open ports, backlight blink, screenshot",
        accent = Bad,
        selfTest = ::selfTest,
        content = { Content() },
    )

    private const val BACKLIGHT = "/sys/class/backlight/rk28_bl/brightness"

    /** Read-only views, one su call each. */
    private val views = listOf(
        "SELinux" to "getenforce",
        "CPU governors" to "cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors",
        "CPU time in state" to "cat /sys/devices/system/cpu/cpu0/cpufreq/stats/time_in_state",
        "GPU (Mali) clock" to "cat /sys/devices/10091000.gpu/dvfs 2>/dev/null || ls /sys/devices/10091000.gpu",
        "DDR frequency" to "cat /sys/class/devfreq/*/cur_freq 2>/dev/null || cat /proc/clocks 2>/dev/null | grep -i ddr || echo n/a",
        "Listening TCP ports" to "netstat -tln",
        "Kernel command line" to "cat /proc/cmdline",
        "Partitions" to "ls /dev/block/platform/1021c000.rksdmmc/by-name",
        "Last kernel messages" to "dmesg | tail -n 25",
        "Top memory users" to "dumpsys meminfo | grep -A 12 'Total PSS by process'",
    )

    @Composable
    private fun Content() {
        val context = LocalHost.current.context
        val scope = rememberCoroutineScope()
        var rootState by remember { mutableStateOf<Boolean?>(null) }
        var output by remember { mutableStateOf<Pair<String, String>?>(null) }
        var busy by remember { mutableStateOf(false) }
        var shot by remember { mutableStateOf<ImageBitmap?>(null) }

        fun su(title: String, cmd: String, timeoutMs: Long = 30_000) {
            busy = true
            scope.launch {
                val r = Root.run(cmd, timeoutMs)
                output = title to (r.out.ifEmpty { r.err.ifEmpty { "(exit ${r.exit})" } })
                rootState = r.exit != -1 && !r.err.contains("denied", ignoreCase = true)
                busy = false
            }
        }

        LabPage("Root", "Uses SuperSU's su. The first call shows SuperSU's Grant/Deny prompt on the frame: tap Grant.") {
            Section("Access", trailing = { ActionButton(if (busy) "Waiting…" else "Check su", { su("su -c id", "id") }, enabled = !busy) }) {
                when (rootState) {
                    true -> Verdict(Status.PASS, "Root granted to this app")
                    false -> Verdict(Status.FAIL, "su denied or missing")
                    null -> Note("Not checked yet.")
                }
            }

            Section("Read-only views") {
                Actions {
                    views.take(5).forEach { (title, cmd) -> ActionButton(title, { su(title, cmd) }, enabled = !busy, primary = false) }
                }
                Actions {
                    views.drop(5).forEach { (title, cmd) -> ActionButton(title, { su(title, cmd) }, enabled = !busy, primary = false) }
                }
                output?.let { (t, o) ->
                    Text(t, style = Mono.copy(color = Bad))
                    Text(o, style = Mono)
                }
            }

            Section("Actions that undo themselves") {
                Actions {
                    ActionButton("Backlight off for 3 s", {
                        Events.log(lab.id, "backlight blink")
                        // one line: this SuperSU ignores multi-line -c scripts
                        su("Backlight blink", "b=\$(cat $BACKLIGHT); echo 0 > $BACKLIGHT; sleep 3; echo \$b > $BACKLIGHT; echo restored to \$b")
                    }, enabled = !busy)
                    ActionButton("Drop page cache", {
                        su("Drop caches", "sync; echo 3 > /proc/sys/vm/drop_caches; grep -E 'MemFree|^Cached' /proc/meminfo")
                    }, enabled = !busy, primary = false)
                    ActionButton("Full-screen screenshot", {
                        busy = true
                        scope.launch {
                            shot = withContext(Dispatchers.IO) { screenshot(context) }
                            output = "screencap" to if (shot != null) "captured 1280×800 with screencap -p" else "screencap failed"
                            busy = false
                        }
                    }, enabled = !busy, primary = false)
                }
                Note("Backlight: writes 0 to $BACKLIGHT and puts the old value back. A real night mode can do the same on a schedule.")
                shot?.let { Image(it, "screenshot", Modifier.width(640.dp).height(400.dp)) }
            }

            SelfTestSection(lab.id, note = "Needs root granted once. Reads SELinux mode and open ports.")
        }
    }

    /** `screencap` as root into our cache dir, then readable by the app. */
    private suspend fun screenshot(context: Context): ImageBitmap? {
        val file = File(context.cacheDir, "screen.png")
        val r = Root.run("screencap -p ${file.path} && chmod 644 ${file.path}")
        if (!r.ok || !file.exists()) return null
        return BitmapFactory.decodeFile(file.path)?.asImageBitmap()
    }

    private suspend fun selfTest(context: Context): List<Check> = CheckList(lab.id).run {
        val id = Root.run("id", 30_000)
        if (!id.out.contains("uid=0")) {
            manual("su", "not granted (${id.err.ifEmpty { "exit ${id.exit}" }}): open Root → Check su and tap Grant in SuperSU")
            return@run items
        }
        pass("su", id.out.substringBefore(' '))
        info("SELinux", Root.run("getenforce").out)
        guard("Open ports") {
            val ports = Root.run("netstat -tln").out.lines()
                .filter { it.contains("LISTEN") }
                .mapNotNull { it.trim().split(Regex("\\s+")).getOrNull(3) }
            val public = ports.filter { it.startsWith("0.0.0.0:") || it.startsWith(":::") }
            if (public.isEmpty()) pass("Open ports", "nothing listens on all interfaces")
            else warn("Open ports", "listening on the LAN: ${public.joinToString()} (45076 = Frameo, 8080 = this lab's web server if started)")
        }
        guard("Backlight sysfs") {
            val b = Root.run("cat $BACKLIGHT").out
            if (b.toIntOrNull() != null) pass("Backlight sysfs", "$BACKLIGHT = $b (root can switch the panel off)") else fail("Backlight sysfs", "unreadable")
        }
        items
    }
}
