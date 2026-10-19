package dev.framio.lab.labs.overview

import android.Manifest
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.framio.lab.Labs
import dev.framio.lab.core.Lab
import dev.framio.lab.core.LabHost
import dev.framio.lab.core.LocalHost
import dev.framio.lab.core.Status
import dev.framio.lab.core.ui.ActionButton
import dev.framio.lab.core.ui.Actions
import dev.framio.lab.core.ui.CheckRows
import dev.framio.lab.core.ui.Hairline
import dev.framio.lab.core.ui.InkDim
import dev.framio.lab.core.ui.LabPage
import dev.framio.lab.core.ui.Note
import dev.framio.lab.core.ui.Section
import dev.framio.lab.core.ui.StatusChip
import dev.framio.lab.core.ui.Surface2
import dev.framio.lab.core.ui.Teal
import dev.framio.lab.core.ui.statusColor

/** Start page: a tile per lab, the permissions the labs need, and the full self-test. */
object OverviewLab {

    val lab = Lab(
        id = LabHost.OVERVIEW,
        title = "Overview",
        summary = "All labs, permissions and the full self-test",
        accent = Teal,
        content = { Content() },
    )

    /** Runtime permissions (Android 6 asks at runtime; `adb shell pm grant` works too). */
    private val runtimePermissions = listOf(
        Manifest.permission.RECORD_AUDIO to "Mic lab, audio visualizer",
        Manifest.permission.ACCESS_FINE_LOCATION to "Wi-Fi scan results, Wi-Fi Direct peers",
        Manifest.permission.CAMERA to "Missing-hardware lab: camera probe",
    )

    @Composable
    private fun Content() {
        val host = LocalHost.current
        val runner = host.selfTest
        LabPage("Veidoo F10 · Hardware Lab", "Rockchip RK3126 · Android 6.0.1 · 1280×800. Pick a lab on the left, or run every probe at once.") {
            Section(
                "Full self-test",
                note = "Runs each lab's automated probes (~1 min, plays short quiet tones). Logs CHECK|… under tag FramioLab and writes files/selftest.json.",
                trailing = { ActionButton(if (runner.running) "Running…" else "Run all", { runner.run() }, enabled = !runner.running) },
            ) {
                if (runner.running) {
                    Text("Probing: ${runner.current ?: "…"}", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(color = Teal, trackColor = Hairline)
                }
                val results = runner.results
                if (results.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Status.entries.forEach { s ->
                            val n = results.count { it.status == s }
                            if (n > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusChip(s)
                                Text(" $n", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    CheckRows(results.filter { it.status == Status.FAIL || it.status == Status.WARN }, showLab = true)
                    Note("Only FAIL and WARN are listed here; open a lab to see all of its results.")
                }
            }

            Tiles(host)

            Section("Permissions", note = "Labs work without them but show less. Each is asked for once; the frame shows Android's permission dialog.") {
                host.permissionVersion // re-read after a grant
                runtimePermissions.forEach { (perm, why) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(if (host.granted(perm)) Status.PASS else Status.MANUAL)
                        Text(perm.substringAfterLast('.'), Modifier.padding(start = 12.dp).weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(why, style = MaterialTheme.typography.bodyMedium.copy(color = InkDim))
                    }
                }
                val canWrite = Settings.System.canWrite(host.context)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(if (canWrite) Status.PASS else Status.MANUAL)
                    Text("WRITE_SETTINGS", Modifier.padding(start = 12.dp).weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text("System brightness (adb shell appops set dev.framio.lab WRITE_SETTINGS allow)", style = MaterialTheme.typography.bodyMedium.copy(color = InkDim))
                }
                Actions {
                    ActionButton("Ask for all", { host.request(*runtimePermissions.map { it.first }.toTypedArray()) })
                }
            }
        }
    }

    @Composable
    private fun Tiles(host: LabHost) {
        val labs = Labs.all.drop(1)
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            labs.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { lab -> Tile(lab, host, Modifier.weight(1f)) }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    @Composable
    private fun Tile(lab: Lab, host: LabHost, modifier: Modifier) {
        val checks = host.selfTest.forLab(lab.id)
        val shape = RoundedCornerShape(14.dp)
        Column(
            modifier
                .height(128.dp)
                .clip(shape)
                .background(Surface2)
                .border(1.dp, Hairline, shape)
                .clickable { host.selected = lab.id }
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(lab.accent))
                Text(lab.title, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            Text(lab.summary, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 3)
            if (checks.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Status.entries.forEach { s ->
                        val n = checks.count { it.status == s }
                        if (n > 0) Text("$n ${s.name.lowercase()}", style = MaterialTheme.typography.labelSmall.copy(color = statusColor(s)))
                    }
                }
            }
        }
    }
}
