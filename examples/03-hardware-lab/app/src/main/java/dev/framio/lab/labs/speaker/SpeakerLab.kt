package dev.framio.lab.labs.speaker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.media.audiofx.Equalizer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.framio.lab.core.Check
import dev.framio.lab.core.Events
import dev.framio.lab.core.CheckList
import dev.framio.lab.core.Format
import dev.framio.lab.core.Lab
import dev.framio.lab.core.LabHost
import dev.framio.lab.core.LocalHost
import dev.framio.lab.core.ui.ActionButton
import dev.framio.lab.core.ui.Actions
import dev.framio.lab.core.ui.Amber
import dev.framio.lab.core.ui.Choice
import dev.framio.lab.core.ui.Coral
import dev.framio.lab.core.ui.KeyValues
import dev.framio.lab.core.ui.LabPage
import dev.framio.lab.core.ui.LabeledSlider
import dev.framio.lab.core.ui.Mono
import dev.framio.lab.core.ui.Note
import dev.framio.lab.core.ui.Section
import dev.framio.lab.core.ui.SelfTestSection
import dev.framio.lab.core.ui.Surface0
import dev.framio.lab.core.ui.Teal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** RK312x codec → speaker: tone generator, sweep, spectrum, EQ, volumes and every playback API. */
object SpeakerLab {

    val lab = Lab(
        id = "speaker",
        title = "Speaker",
        summary = "Tone generator, frequency sweep, live spectrum, equalizer, volumes, sound APIs",
        accent = Coral,
        selfTest = ::selfTest,
        content = { Content() },
    )

    @Composable
    private fun Content() {
        val host = LocalHost.current
        val context = host.context
        val player = remember { TonePlayer() }
        DisposableEffect(Unit) { onDispose { player.release() } }

        LabPage("Speaker", "Built-in RK312x codec, 44.1 kHz stereo into one speaker path. Keep levels low: the frame is small.") {
            QuickSection(context)
            OutputSection(context)
            VolumeSection(context)
            ToneSection(player)
            SpectrumSection(host, player)
            EqualizerSection(player)
            ApisSection(context)
            SelfTestSection(lab.id, note = "Plays two short quiet 1 kHz tones and checks them with AudioTrack counters and the Visualizer FFT.")
        }
    }

    /** One tap: a three-note chime on the music stream, then left and right separately. */
    @Composable
    private fun QuickSection(context: Context) {
        val scope = rememberCoroutineScope()
        val am = remember { context.getSystemService(AudioManager::class.java) }
        var result by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        fun play(channel: Channel) {
            busy = true
            scope.launch {
                val frames = QuickSound.chime(channel)
                val ok = frames >= QuickSound.frames * 9 / 10
                result = if (ok) "${channel.label}: chime played ($frames frames). Did you hear it?" else "${channel.label}: only $frames of ${QuickSound.frames} frames played"
                Events.log(lab.id, "quick chime ${channel.label}: $frames/${QuickSound.frames} frames")
                busy = false
            }
        }
        Section("Quick sound test", note = "A short C–E–G chime on the music stream. Music volume: ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}.") {
            Actions {
                ActionButton(if (busy) "Playing…" else "Play test sound", { play(Channel.Both) }, enabled = !busy)
                ActionButton("Left only", { play(Channel.Left) }, enabled = !busy, primary = false)
                ActionButton("Right only", { play(Channel.Right) }, enabled = !busy, primary = false)
                ActionButton("Volume +", { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0); result = "Music volume ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}" }, primary = false)
                ActionButton("Volume −", { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0); result = "Music volume ${am.getStreamVolume(AudioManager.STREAM_MUSIC)}" }, primary = false)
            }
            if (result.isNotEmpty()) Text(result, style = Mono)
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) Note("Music volume is 0: nothing will be heard.")
        }
    }

    @Composable
    private fun OutputSection(context: Context) {
        val (rate, frames) = SoundApis.mixerProperties(context)
        Section("Output path") {
            KeyValues(
                listOf(
                    "Output devices" to SoundApis.outputDevices(context).joinToString(),
                    "Mixer" to "$rate Hz, $frames frames per buffer (${frames?.toDoubleOrNull()?.let { f -> rate?.toDoubleOrNull()?.let { Format.ms(f / it * 1000) } }})",
                    "Native track rate" to "${AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)} Hz",
                    "Low-latency feature" to context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY).toString(),
                ),
            )
        }
    }

    private val streams = listOf(
        "Music" to AudioManager.STREAM_MUSIC,
        "Alarm" to AudioManager.STREAM_ALARM,
        "Notification" to AudioManager.STREAM_NOTIFICATION,
        "System" to AudioManager.STREAM_SYSTEM,
        "Ring" to AudioManager.STREAM_RING,
    )

    @Composable
    private fun VolumeSection(context: Context) {
        val am = remember { context.getSystemService(AudioManager::class.java) }
        var version by remember { mutableIntStateOf(0) }
        Section("Volumes", note = "AudioManager.setStreamVolume. Each stream has its own level; Music is what apps and the WebView use.") {
            version.let {
                streams.forEach { (name, stream) ->
                    val max = am.getStreamMaxVolume(stream)
                    val cur = am.getStreamVolume(stream)
                    LabeledSlider(name, "$cur / $max", cur.toFloat(), { v ->
                        am.setStreamVolume(stream, v.roundToInt(), 0)
                        version++
                    }, range = 0f..max.toFloat(), steps = (max - 1).coerceAtLeast(0))
                }
            }
        }
    }

    @Composable
    private fun ToneSection(player: TonePlayer) {
        var playing by remember { mutableStateOf(false) }
        var sweeping by remember { mutableStateOf(false) }
        var waveform by remember { mutableStateOf(Waveform.Sine) }
        var channel by remember { mutableStateOf(Channel.Both) }
        // log slider: 0..1 → 20 Hz..16 kHz
        var pos by remember { mutableFloatStateOf(posOf(440.0)) }
        var db by remember { mutableFloatStateOf(-18f) }
        var shownHz by remember { mutableStateOf(440.0) }

        LaunchedEffect(sweeping) {
            while (sweeping) {
                shownHz = player.currentFrequency
                delay(100)
            }
        }
        player.onFinished = { sweeping = false; playing = false }

        Section(
            "Tone generator",
            note = "AudioTrack streaming 16-bit stereo from our own oscillator.",
            trailing = {
                ActionButton(if (playing) "Stop" else "Play", {
                    if (playing) player.stop() else player.start()
                    playing = !playing
                    sweeping = false
                    player.sweepSeconds = null
                })
            },
        ) {
            Choice(Waveform.entries, waveform, { it.label }) { waveform = it; player.waveform = it }
            LabeledSlider("Frequency", Format.hz(freqOf(pos)), pos, {
                pos = it
                player.frequency = freqOf(it)
                shownHz = player.frequency
            })
            LabeledSlider("Level", "${db.roundToInt()} dBFS", db, {
                db = it
                player.gain = dbToGain(it.toDouble())
            }, range = -48f..-3f)
            Choice(Channel.entries, channel, { it.label }) { channel = it; player.channel = it }
            Note("Left/Right: if both sound the same, the frame has one speaker fed with a mono mix.")
            Actions {
                ActionButton(if (sweeping) "Sweeping…" else "Sweep 20 Hz → 16 kHz (12 s)", {
                    player.stop()
                    player.sweepSeconds = 12.0
                    player.start()
                    playing = true
                    sweeping = true
                }, enabled = !sweeping, primary = false)
                listOf(100.0, 440.0, 1000.0, 4000.0, 10_000.0).forEach { f ->
                    ActionButton(Format.hz(f), { pos = posOf(f); player.frequency = f; shownHz = f }, primary = false)
                }
            }
            Text(if (sweeping) "Now: ${Format.hz(shownHz)} — note where it becomes audible and where it fades" else "Frequency ${Format.hz(shownHz)}", style = Mono)
        }
    }

    private fun freqOf(pos: Float) = 20.0 * (16_000.0 / 20.0).pow(pos.toDouble())
    private fun posOf(f: Double) = (ln(f / 20.0) / ln(16_000.0 / 20.0)).toFloat()

    @Composable
    private fun SpectrumSection(host: LabHost, player: TonePlayer) {
        host.permissionVersion
        val allowed = host.granted(Manifest.permission.RECORD_AUDIO)
        var mags by remember { mutableStateOf(FloatArray(0)) }
        var peak by remember { mutableStateOf("") }
        val spectrum = remember(allowed) { if (allowed) runCatching { Spectrum(player.sessionId) }.getOrNull() else null }
        DisposableEffect(spectrum) { onDispose { spectrum?.release() } }
        LaunchedEffect(spectrum) {
            val s = spectrum ?: return@LaunchedEffect
            while (true) {
                if (player.playing) {
                    val m = s.magnitudes()
                    mags = m
                    val k = Spectrum.peakBin(m)
                    peak = "peak ${Format.hz(k * s.binHz)} (bin $k, ${Format.f1(s.binHz)} Hz per bin)"
                }
                delay(66)
            }
        }
        Section("Spectrum", note = "android.media.audiofx.Visualizer on the tone's audio session: the FFT of what the mixer receives.") {
            if (!allowed) {
                Note("Visualizer needs RECORD_AUDIO.")
                Actions { ActionButton("Allow", { host.request(Manifest.permission.RECORD_AUDIO) }) }
            } else if (spectrum == null) {
                Note("Visualizer could not attach to the session.")
            } else {
                Canvas(Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(10.dp)).background(Surface0)) {
                    if (mags.isEmpty()) return@Canvas
                    val bins = mags.size / 2 // 0..~11 kHz
                    val w = size.width / bins
                    val max = (mags.maxOrNull() ?: 1f).coerceAtLeast(1f)
                    for (k in 0 until bins) {
                        val h = mags[k] / max * size.height
                        drawRect(Teal, Offset(k * w, size.height - h), Size(w.coerceAtLeast(1f), h))
                    }
                }
                Text(if (player.playing) peak else "Play a tone to see it.", style = Mono)
            }
        }
    }

    @Composable
    private fun EqualizerSection(player: TonePlayer) {
        val eq = remember { runCatching { Equalizer(0, player.sessionId).apply { enabled = true } }.getOrNull() }
        DisposableEffect(eq) { onDispose { eq?.release() } }
        var version by remember { mutableIntStateOf(0) }
        Section("Equalizer", note = "android.media.audiofx.Equalizer on the tone's session. Play a sweep and pull a band down to hear it.") {
            if (eq == null) {
                Note("Equalizer not available for this session.")
                return@Section
            }
            val (lo, hi) = eq.bandLevelRange.let { it[0] to it[1] }
            version.let {
                for (b in 0 until eq.numberOfBands) {
                    val band = b.toShort()
                    val level = eq.getBandLevel(band)
                    LabeledSlider(
                        Format.hz(eq.getCenterFreq(band) / 1000.0),
                        "${level / 100} dB",
                        level.toFloat(),
                        { eq.setBandLevel(band, it.roundToInt().toShort()); version++ },
                        range = lo.toFloat()..hi.toFloat(),
                    )
                }
                Choice((0 until eq.numberOfPresets).toList(), -1, { eq.getPresetName(it.toShort()) }) {
                    eq.usePreset(it.toShort()); version++
                }
            }
        }
    }

    @Composable
    private fun ApisSection(context: Context) {
        val scope = rememberCoroutineScope()
        var result by remember { mutableStateOf("") }
        Section("Other playback APIs", note = "Sounds bundled in /system/media/audio: ${SoundApis.ringtoneCounts(context).entries.joinToString { "${it.value} ${it.key}" }}") {
            Actions {
                ActionButton("ToneGenerator beep", { SoundApis.dtmf(ToneGenerator.TONE_PROP_BEEP); result = "ToneGenerator TONE_PROP_BEEP" }, primary = false)
                ActionButton("DTMF 1-2-3", {
                    scope.launch {
                        listOf(ToneGenerator.TONE_DTMF_1, ToneGenerator.TONE_DTMF_2, ToneGenerator.TONE_DTMF_3).forEach { SoundApis.dtmf(it, 150); delay(250) }
                    }
                    result = "ToneGenerator DTMF"
                }, primary = false)
                ActionButton("SoundPool click", {
                    scope.launch { result = SoundApis.soundPoolClick(context)?.let { "SoundPool loaded click.wav in $it ms" } ?: "SoundPool failed" }
                }, primary = false)
                ActionButton("MediaPlayer notification", {
                    result = SoundApis.playNotification(context)?.let { "MediaPlayer: default notification, $it ms" } ?: "MediaPlayer failed"
                }, primary = false)
                ActionButton("Ringtone alarm", {
                    result = SoundApis.playAlarmBriefly(context)?.let { "Ringtone: $it (1.5 s)" } ?: "no alarm sound"
                }, primary = false)
            }
            if (result.isNotEmpty()) Text(result, style = Mono)
            Note("Effects on this build: ${SoundApis.effects().joinToString()}")
        }
    }

    private suspend fun selfTest(context: Context): List<Check> = CheckList(lab.id).run {
        guard("Output device") {
            val outs = SoundApis.outputDevices(context)
            if (outs.any { it.startsWith("speaker") }) pass("Output device", outs.joinToString()) else fail("Output device", "no built-in speaker in ${outs.joinToString()}")
        }
        guard("Mixer") {
            val (rate, frames) = SoundApis.mixerProperties(context)
            info("Mixer", "$rate Hz, $frames frames/buffer, low-latency=${context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)}")
        }
        guard("Music volume") {
            val am = context.getSystemService(AudioManager::class.java)
            val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (v == 0) warn("Music volume", "muted (0/$max)") else info("Music volume", "$v/$max")
        }

        guard("Chime") {
            val frames = QuickSound.chime()
            if (frames >= QuickSound.frames * 9 / 10) pass("Chime", "C–E–G chime fully played ($frames frames, ${Chime.durationMs()} ms)")
            else fail("Chime", "only $frames of ${QuickSound.frames} frames played")
        }

        val player = TonePlayer()
        try {
            player.frequency = 1000.0
            player.gain = dbToGain(-30.0)
            val canTap = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val spectrum = if (canTap) runCatching { Spectrum(player.sessionId) }.getOrNull() else null
            player.start()
            delay(900)
            val head = player.track.playbackHeadPosition
            var peakHz = 0.0
            if (spectrum != null) {
                val m = spectrum.magnitudes()
                peakHz = Spectrum.peakBin(m) * spectrum.binHz
            }
            player.stop()
            spectrum?.release()

            if (head > 0.5 * player.sampleRate) pass("AudioTrack", "played ${head} frames (${Format.ms(head * 1000.0 / player.sampleRate)}) of a 1 kHz tone at -30 dBFS")
            else fail("AudioTrack", "playback head only at $head frames after 0.9 s")

            when {
                spectrum == null -> manual("Visualizer FFT", if (canTap) "Visualizer could not attach" else "grant RECORD_AUDIO to verify the tone by FFT")
                peakHz in 900.0..1100.0 -> pass("Visualizer FFT", "peak at ${Format.hz(peakHz)} for a 1 kHz tone: samples reach the mixer intact")
                else -> fail("Visualizer FFT", "peak at ${Format.hz(peakHz)}, expected 1 kHz")
            }
        } finally {
            player.release()
        }

        guard("Equalizer") {
            val p = TonePlayer()
            val eq = Equalizer(0, p.sessionId)
            pass("Equalizer", "${eq.numberOfBands} bands, ${eq.numberOfPresets} presets, range ${eq.bandLevelRange[0] / 100}..${eq.bandLevelRange[1] / 100} dB")
            eq.release()
            p.release()
        }
        guard("ToneGenerator") {
            SoundApis.dtmf(ToneGenerator.TONE_PROP_BEEP, 120)
            pass("ToneGenerator", "beep played")
        }
        guard("SoundPool") {
            val ms = SoundApis.soundPoolClick(context)
            if (ms != null) pass("SoundPool", "click.wav loaded in $ms ms and played") else fail("SoundPool", "load failed")
        }
        guard("System sounds") {
            info("System sounds", SoundApis.ringtoneCounts(context).entries.joinToString { "${it.value} ${it.key}" })
        }
        manual("Hear it", "Speaker → Play: confirm the tone is audible; run the sweep to find the speaker's range")
        items
    }
}
