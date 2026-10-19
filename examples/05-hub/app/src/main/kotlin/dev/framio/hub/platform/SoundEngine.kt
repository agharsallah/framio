package dev.framio.hub.platform

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import dev.framio.hub.core.engine.Cue
import dev.framio.hub.core.timers.Melodies
import dev.framio.hub.core.timers.MelodySynth
import dev.framio.hub.core.timers.Tone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Plays the synthesized melodies. No audio files: [MelodySynth] renders PCM once per melody, and a static
 * AudioTrack plays it (the Lab proved static tracks play to the last frame on this frame).
 *
 *  - Alarms use USAGE_ALARM, so they follow the alarm volume; the melody repeats every 4 s and gets louder,
 *    from 40 % to 100 % over 30 s.
 *  - Cues (a note arrived, a routine finished) use USAGE_MEDIA at −18 dB.
 * The Hub never changes system volumes. [playing] lets the knock detector ignore our own speaker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SoundEngine(private val scope: CoroutineScope) {
    private val audio = Dispatchers.Default.limitedParallelism(1)
    private val pcm = HashMap<Any, ShortArray>()
    private var alarm: AudioTrack? = null
    private var alarmMelody = -1
    private var ramp: Job? = null
    private var cueTrack: AudioTrack? = null
    private var cueEnds = 0L

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    fun ring(melody: Int) {
        scope.launch(audio) {
            if (alarm != null && alarmMelody == melody) return@launch
            stopAlarm()
            val samples = pcm.getOrPut("alarm$melody") { MelodySynth.render(Melodies.get(melody), gain = 0.9, lengthMs = LOOP_MS) }
            val track = staticTrack(samples, AudioAttributes.USAGE_ALARM) ?: return@launch
            track.setLoopPoints(0, samples.size, -1)
            track.setVolume(START_VOLUME)
            track.play()
            alarm = track
            alarmMelody = melody
            _playing.value = true
            Logs.event("sound", "ring melody $melody")
            ramp = scope.launch(audio) {
                val steps = RAMP_MS / RAMP_STEP_MS
                for (i in 1..steps) {
                    delay(RAMP_STEP_MS)
                    alarm?.setVolume(START_VOLUME + (1f - START_VOLUME) * i / steps)
                }
            }
        }
    }

    fun silence() {
        scope.launch(audio) {
            if (alarm != null) Logs.event("sound", "silence")
            stopAlarm()
        }
    }

    fun cue(cue: Cue) = play(
        cue,
        when (cue) {
            Cue.NoteArrived -> Melodies.noteArrived
            Cue.RoutineDone -> Melodies.routineDone
            Cue.StepDone -> Melodies.stepDone
        },
    )

    fun hourly() = play("hourly", Melodies.hourly)

    /** A timer's melody once, quietly: when choosing timers, so people learn which is which. */
    fun preview(melody: Int) = play("preview$melody", Melodies.get(melody))

    private fun play(key: Any, tones: List<Tone>) {
        scope.launch(audio) {
            if (alarm != null) return@launch // never over an alarm
            cueTrack?.release()
            val samples = pcm.getOrPut(key) { MelodySynth.render(tones, gain = CUE_GAIN) }
            val track = staticTrack(samples, AudioAttributes.USAGE_MEDIA) ?: return@launch
            track.play()
            cueTrack = track
            _playing.value = true
            val length = MelodySynth.durationMs(samples)
            cueEnds = System.nanoTime() + length * 1_000_000
            delay(length + 150)
            if (cueTrack === track) {
                track.release()
                cueTrack = null
                _playing.value = alarm != null
            }
        }
    }

    private fun stopAlarm() {
        ramp?.cancel()
        ramp = null
        alarm?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        alarm = null
        alarmMelody = -1
        _playing.value = cueTrack != null && System.nanoTime() < cueEnds
    }

    private fun staticTrack(samples: ShortArray, usage: Int): AudioTrack? = try {
        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(MelodySynth.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        AudioTrack(attributes, format, samples.size * 2, AudioTrack.MODE_STATIC, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE).also {
            it.write(samples, 0, samples.size)
        }
    } catch (e: Exception) { // no audio hardware (tests), or the mixer is out of tracks
        Logs.warn("sound", "can't create a track: ${e.message}")
        null
    }

    companion object {
        const val LOOP_MS = 4_000
        private const val START_VOLUME = 0.4f
        private const val RAMP_MS = 30_000L
        private const val RAMP_STEP_MS = 500L

        /** −18 dB. */
        private const val CUE_GAIN = 0.126
    }
}
