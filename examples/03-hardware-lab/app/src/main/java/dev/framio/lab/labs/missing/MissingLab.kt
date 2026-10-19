package dev.framio.lab.labs.missing

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.framio.lab.core.Check
import dev.framio.lab.core.Lab
import dev.framio.lab.core.LocalHost
import dev.framio.lab.core.Status
import dev.framio.lab.core.ui.InkDim
import dev.framio.lab.core.ui.LabPage
import dev.framio.lab.core.ui.Neutral
import dev.framio.lab.core.ui.Note
import dev.framio.lab.core.ui.Section
import dev.framio.lab.core.ui.SelfTestSection
import dev.framio.lab.core.ui.StatusChip

/** Everything Android advertises that the frame doesn't have, verified through the real APIs. */
object MissingLab {

    val lab = Lab(
        id = "missing",
        title = "Not on this frame",
        summary = "Bluetooth, camera, NFC, GPS, TTS, vibration…: what Android claims vs what answers",
        accent = Neutral,
        selfTest = ::selfTest,
        content = { Content() },
    )

    @Composable
    private fun Content() {
        val context = LocalHost.current.context
        var probes by remember { mutableStateOf<List<Absent.Probe>?>(null) }
        LaunchedEffect(Unit) { probes = Absent.all(context) }
        LabPage("Not on this frame", "PackageManager.hasSystemFeature says what the firmware declares. Each row below asks the actual API (read-only, nothing is switched on).") {
            Section("Claimed vs real") {
                val list = probes
                if (list == null) {
                    Note("Probing…")
                    return@Section
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Real", Modifier.width(76.dp), style = MaterialTheme.typography.labelSmall)
                    Text("Claimed", Modifier.width(76.dp), style = MaterialTheme.typography.labelSmall)
                    Text("API", style = MaterialTheme.typography.labelSmall)
                }
                list.forEach { p ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusChip(p.status, Modifier.width(76.dp))
                        Text(
                            when (p.claimed) { true -> "yes"; false -> "no"; null -> "–" },
                            Modifier.width(76.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(p.name, Modifier.width(200.dp), style = MaterialTheme.typography.bodyMedium)
                        Text(p.detail, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(color = InkDim))
                    }
                }
            }
            Section("Workarounds") {
                Note(
                    "Bluetooth/BLE → an ESP32 bridge over Wi-Fi (HTTP/MQTT) or USB serial (usb-serial-for-android).\n" +
                        "Location → configure it, or IP geolocation.\n" +
                        "Voice output → pre-rendered audio files, or install an engine APK (e.g. eSpeak NG / RHVoice).\n" +
                        "Push → poll, or keep a WebSocket/MQTT connection open (no FCM without Play services).",
                )
            }
            SelfTestSection(lab.id)
        }
    }

    private suspend fun selfTest(context: Context): List<Check> = Absent.all(context).map { p ->
        val claim = when (p.claimed) {
            true -> "; Android claims it"
            false -> ""
            null -> ""
        }
        Check(lab.id, p.name, if (p.status == Status.PASS) Status.PASS else Status.ABSENT, p.detail + claim)
    }
}
