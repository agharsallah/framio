package dev.framio.pomodoro.platform

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import dev.framio.pomodoro.core.audio.ChimeSynth
import dev.framio.pomodoro.core.audio.Pcm
import dev.framio.pomodoro.core.engine.CueKind
import dev.framio.pomodoro.core.model.CueStyle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The cues, synthesized: no audio files. [ChimeSynth] renders each cue's PCM once (lazily, on the sound thread) and a
 * static AudioTrack plays it; static tracks play to the last frame on this frame (03 LOG).
 *
 * Loudness (M0 §5): media volume sits at 2/15 (≈ −47 dB) and Frameo resets it, alarm is 6/7 (≈ −7 dB). So cues play
 * on **USAGE_ALARM**, never touching any system volume, quietly:
 *  - Soft (Classic, Flow, Gentle): the two-note chime with its peak at [SOFT_PEAK_DB].
 *  - Rising (Deep): three chimes over 8 s from −24 to −12 dBFS (the ramp is in the PCM).
 *  - Nudge and pause-cap: one note at [NOTE_PEAK_DB].
 * Each is then scaled by the in-app volume (Settings.soundVolume, default 0.6 ≈ −4.4 dB) through `setVolume`.
 * A cue that is interrupted fades out over 120 ms instead of clicking. [playing] lets the knock detector (M3) raise
 * its threshold while our own speaker plays.
 */
class SoundEngine(private val scope: CoroutineScope, private val audio: CoroutineDispatcher) {
    private val pcm = HashMap<String, ShortArray>()
    private var track: AudioTrack? = null
    private var gain = 0f
    private var job: Job? = null

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    fun cue(kind: CueKind, style: CueStyle, volume: Float) {
        if (style == CueStyle.Off) return
        val key = when (kind) {
            CueKind.FocusEnd, CueKind.BreakEnd -> if (style == CueStyle.Rising) RISING else SOFT
            CueKind.Nudge, CueKind.PauseCap -> NOTE
        }
        play(key, volume, "cue $kind $style")
    }

    /**
     * Renders every cue's PCM now, on the sound thread, so the first cue plays on time: rendering on first use made
     * the first cue 0.6 s late (LOG M2). Called when a session starts; a no-op once rendered. It waits [after] first:
     * rendering takes about 0.9 s of CPU on the frame (LOG M3), which must not land on Begin's breathe-in.
     */
    fun prepare(after: Duration = PREPARE_AFTER) {
        scope.launch(audio) {
            if (pcm.size == 3) return@launch
            delay(after)
            val start = System.nanoTime()
            var rendered = 0
            for (key in listOf(SOFT, RISING, NOTE)) {
                if (key !in pcm) {
                    pcm[key] = render(key)
                    rendered++
                }
            }
            if (rendered > 0) Logs.event("sound", "prepared $rendered cues in ${(System.nanoTime() - start) / 1_000_000} ms")
        }
    }

    /** "Test" in Settings: the soft chime at [volume], so the user can judge the level by ear. */
    fun test(volume: Float) = play(SOFT, volume, "test")

    fun stop() {
        scope.launch(audio) { stopNow(fade = true) }
    }

    private fun play(key: String, volume: Float, why: String) {
        scope.launch(audio) {
            stopNow(fade = true)
            val samples = pcm.getOrPut(key) { render(key) }
            gain = volume.coerceIn(0f, 1f)
            val t = staticTrack(samples) ?: return@launch
            t.setVolume(gain)
            t.play()
            track = t
            _playing.value = true
            Logs.event("sound", "$why volume ${(gain * 100).toInt()}% (${samples.size * 1000L / Pcm.SAMPLE_RATE} ms)")
            job = scope.launch(audio) {
                delay(samples.size * 1000L / Pcm.SAMPLE_RATE + 150)
                if (track === t) stopNow(fade = false)
            }
        }
    }

    private suspend fun stopNow(fade: Boolean) {
        job?.cancel()
        job = null
        val t = track ?: return
        track = null
        if (fade) {
            for (i in FADE_STEPS - 1 downTo 0) {
                t.setVolume(gain * i / FADE_STEPS)
                delay(FADE_MS / FADE_STEPS)
            }
        }
        try {
            t.stop()
        } catch (_: IllegalStateException) {
        }
        t.release()
        _playing.value = false
    }

    private fun render(key: String): ShortArray = when (key) {
        RISING -> ChimeSynth.rising()
        NOTE -> ChimeSynth.render(NOTE_PEAK_DB, listOf(ChimeSynth.TWO_NOTE.first()))
        else -> ChimeSynth.render(SOFT_PEAK_DB)
    }

    private fun staticTrack(samples: ShortArray): AudioTrack? = try {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(Pcm.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val t = AudioTrack(attributes, format, samples.size * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE)
        // Out of mixer tracks, the constructor doesn't throw: the track is just unusable and play() would (a crash
        // mid-session). Released here, never leaked.
        if (t.state == AudioTrack.STATE_UNINITIALIZED || t.write(samples, 0, samples.size) < 0) {
            Logs.warn("sound", "track unusable (state ${t.state})")
            t.release()
            null
        } else {
            t
        }
    } catch (e: Exception) { // no audio hardware (tests), or the mixer is out of tracks
        Logs.warn("sound", "can't create a track: ${e.message}")
        null
    }

    /** True if a track can be created and filled: for the self-test. */
    fun selfTest(): Boolean = staticTrack(ShortArray(441))?.also { it.release() } != null

    companion object {
        /** Peak of the soft chime in dBFS, before the in-app volume. Chosen to be gentle on the alarm stream (ear-check). */
        const val SOFT_PEAK_DB: Double = -18.0
        const val NOTE_PEAK_DB: Double = -24.0

        /** After Begin's 3 s breath; the shortest block (1 debug "minute" = 1 s × N) still gets it in time in practice. */
        val PREPARE_AFTER: Duration = 4.seconds
        private const val SOFT = "soft"
        private const val RISING = "rising"
        private const val NOTE = "note"
        private const val FADE_MS = 120L
        private const val FADE_STEPS = 6
    }
}
