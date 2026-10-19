package dev.framio.lab.labs.mic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.framio.lab.core.Check
import dev.framio.lab.core.Events
import dev.framio.lab.core.CheckList
import dev.framio.lab.core.Format
import dev.framio.lab.core.Lab
import dev.framio.lab.core.LocalHost
import dev.framio.lab.core.Ring
import dev.framio.lab.core.Status
import dev.framio.lab.core.ui.ActionButton
import dev.framio.lab.core.ui.Actions
import dev.framio.lab.core.ui.Choice
import dev.framio.lab.core.ui.Hairline
import dev.framio.lab.core.ui.KeyValues
import dev.framio.lab.core.ui.LabPage
import dev.framio.lab.core.ui.Mono
import dev.framio.lab.core.ui.Note
import dev.framio.lab.core.ui.Rose
import dev.framio.lab.core.ui.Section
import dev.framio.lab.core.ui.SelfTestSection
import dev.framio.lab.core.ui.Sparkline
import dev.framio.lab.core.ui.Surface0
import dev.framio.lab.core.ui.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Is there a microphone? Live meter, scope, record + playback, per-source signal check. */
object MicLab {

    val lab = Lab(
        id = "mic",
        title = "Microphone",
        summary = "Level meter, oscilloscope, record & play back, is a mic really connected?",
        accent = Rose,
        selfTest = ::selfTest,
        content = { Content() },
    )

    @Composable
    private fun Content() {
        val host = LocalHost.current
        val context = host.context
        host.permissionVersion
        val allowed = host.granted(Manifest.permission.RECORD_AUDIO)

        LabPage("Microphone", "The codec has a capture path and Android lists a built-in mic. Clap or talk near the frame and watch the meter.") {
            if (!allowed) {
                Section("Permission") {
                    Note("AudioRecord needs RECORD_AUDIO.")
                    Actions { ActionButton("Allow", { host.request(Manifest.permission.RECORD_AUDIO) }) }
                }
            } else {
                MeterSection()
                RecordSection()
                SourcesSection()
            }
            Section("Capture features") {
                KeyValues(
                    listOf(
                        "Supported rates" to MicRecorder.supportedRates().joinToString { "${it / 1000.0} kHz" },
                        "AutomaticGainControl" to AutomaticGainControl.isAvailable().toString(),
                        "NoiseSuppressor" to NoiseSuppressor.isAvailable().toString(),
                        "AcousticEchoCanceler" to AcousticEchoCanceler.isAvailable().toString(),
                        "Feature microphone" to context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE).toString(),
                    ),
                )
            }
            SelfTestSection(lab.id, note = "Records 1.5 s from MIC (after the 1.5 s power-up settling) and checks whether the signal moves at all.")
        }
    }

    private class Meter {
        val levels = Ring(150)
        var stats: SignalStats? = null
        var scope = ShortArray(0)
    }

    @Composable
    private fun MeterSection() {
        var running by remember { mutableStateOf(false) }
        var source by remember { mutableStateOf(MicRecorder.Source.Mic) }
        val meter = remember { Meter() }
        var version by remember { mutableStateOf(0) }
        val scope = rememberCoroutineScope()

        DisposableEffect(running, source) {
            if (!running) return@DisposableEffect onDispose { }
            val job = scope.launch(Dispatchers.IO) {
                val rec = MicRecorder.open(source) ?: return@launch
                val buf = ShortArray(MicRecorder.RATE / 20) // 50 ms blocks
                rec.startRecording()
                try {
                    while (isActive) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n <= 0) break
                        val s = SignalStats.of(buf, n)
                        meter.stats = s
                        if (!s.flat) Events.throttled(lab.id, "signal", 500) { "${source.label} signal ${Format.f1(s.dbfs)} dBFS, change ${Format.f1(s.hf)} LSB, peak ${s.peak}" }
                        meter.levels.add(s.dbfs.coerceAtLeast(-96.0).toFloat())
                        meter.scope = buf.copyOf(n)
                        withContext(Dispatchers.Main) { version++ }
                    }
                } finally {
                    rec.stop()
                    rec.release()
                }
            }
            onDispose { job.cancel() }
        }

        Section(
            "Live input",
            note = "AudioRecord 16 kHz mono, 50 ms blocks. Level = RMS after removing the DC offset.",
            trailing = { ActionButton(if (running) "Stop" else "Listen", { running = !running }) },
        ) {
            Choice(MicRecorder.Source.entries, source, { it.label }) { source = it }
            version.let {
                val s = meter.stats
                if (s != null) {
                    Verdict(
                        if (s.flat) Status.ABSENT else Status.PASS,
                        if (s.flat) "Flat: offset ${s.dc.toInt()}, change ${Format.f1(s.hf)} LSB — no signal from a microphone"
                        else "Signal: ${Format.f1(s.dbfs)} dBFS, change ${Format.f1(s.hf)} LSB, peak ${s.peak}",
                    )
                }
                Sparkline(meter.levels.toList(), Rose, min = -96f, max = 0f, capacity = 150, height = 80.dp)
                Canvas(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(10.dp)).background(Surface0)) {
                    val w = meter.scope
                    drawLine(Hairline, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
                    if (w.size < 2) return@Canvas
                    val mean = w.average().toFloat()
                    val path = Path()
                    w.forEachIndexed { i, v ->
                        // zoomed ×16 around the offset so a few LSB of noise is visible
                        val y = size.height / 2 - (v - mean) * 16 / Short.MAX_VALUE * size.height / 2
                        val x = i * size.width / (w.size - 1)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, Rose, style = Stroke(1.5f))
                }
            }
        }
    }

    @Composable
    private fun RecordSection() {
        var state by remember { mutableStateOf("") }
        var clip by remember { mutableStateOf<ShortArray?>(null) }
        val scope = rememberCoroutineScope()
        Section("Record and play back", note = "5 s from MIC, then played through the speaker at 16 kHz.") {
            Actions {
                ActionButton("Record 5 s", {
                    state = "Recording… make some noise"
                    scope.launch {
                        val c = withContext(Dispatchers.IO) { MicRecorder.capture(MicRecorder.Source.Mic, 5000) }
                        clip = c
                        state = c?.let { val s = SignalStats.of(it); "Recorded ${it.size} samples · ${Format.f1(s.dbfs)} dBFS · peak ${s.peak}" } ?: "Could not open the mic"
                    }
                })
                ActionButton("Play back", {
                    clip?.let { c ->
                        // boost ×8 so a quiet recording is audible; clipping is fine for a test
                        val mean = c.average()
                        val loud = ShortArray(c.size) { ((c[it] - mean) * 8).toInt().coerceIn(-32768, 32767).toShort() }
                        val t = MicRecorder.play(loud)
                        scope.launch { delay(5500); t.release() }
                    }
                }, enabled = clip != null, primary = false)
            }
            if (state.isNotEmpty()) Text(state, style = Mono)
        }
    }

    @Composable
    private fun SourcesSection() {
        var results by remember { mutableStateOf<List<Pair<MicRecorder.Source, SignalStats?>>?>(null) }
        var running by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        Section(
            "Every audio source",
            note = "Each AudioSource picks a different HAL input path. 1 s each.",
            trailing = {
                ActionButton(if (running) "Recording…" else "Check all", {
                    running = true
                    scope.launch {
                        results = withContext(Dispatchers.IO) {
                            MicRecorder.Source.entries.map { it to MicRecorder.capture(it, 1000)?.let(SignalStats::of) }
                        }
                        running = false
                    }
                }, enabled = !running, primary = false)
            },
        ) {
            results?.forEach { (src, s) ->
                Verdict(
                    when {
                        s == null -> Status.FAIL
                        s.flat -> Status.ABSENT
                        else -> Status.PASS
                    },
                    "${src.label}: " + (s?.let { "offset ${it.dc.toInt()}, change ${Format.f1(it.hf)} LSB, peak ${it.peak}" } ?: "could not open"),
                )
            }
        }
    }

    private fun selfTest(context: Context): List<Check> = CheckList(lab.id).run {
        info("Rates", MicRecorder.supportedRates().joinToString { "${it / 1000.0}k" })
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            manual("Microphone", "grant RECORD_AUDIO (Overview → Ask for all) to test it")
            return@run items
        }
        guard("Microphone") {
            val samples = MicRecorder.capture(MicRecorder.Source.Mic, 1500)
            if (samples == null) {
                fail("Microphone", "AudioRecord could not be opened")
            } else {
                val s = SignalStats.of(samples)
                if (s.flat) absent("Microphone", "AudioRecord works but the input is flat: offset ${s.dc.toInt()}, sample-to-sample change ${Format.f2(s.hf)} LSB, peak ${s.peak} → no mic wired to the codec")
                else pass("Microphone", "signal ${Format.f1(s.dbfs)} dBFS, change ${Format.f1(s.hf)} LSB, peak ${s.peak}")
            }
        }
        info("Capture effects", "AGC=${AutomaticGainControl.isAvailable()}, NS=${NoiseSuppressor.isAvailable()}, AEC=${AcousticEchoCanceler.isAvailable()}")
        manual("Clap test", "Microphone → Listen, then clap next to the frame: the level should jump")
        items
    }
}
