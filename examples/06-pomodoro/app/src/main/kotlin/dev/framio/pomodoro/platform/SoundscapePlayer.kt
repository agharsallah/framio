package dev.framio.pomodoro.platform

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import dev.framio.pomodoro.core.audio.NoiseColor
import dev.framio.pomodoro.core.audio.NoiseSynth
import dev.framio.pomodoro.core.audio.RainSynth
import dev.framio.pomodoro.core.audio.WindSynth
import dev.framio.pomodoro.core.model.Soundscape
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** One of the core synths behind a common face: fill, fade out, silent. */
interface SoundscapeSource {
    fun fill(out: ShortArray, offset: Int, length: Int)
    fun fadeOut(over: Duration)
    val isSilent: Boolean

    companion object {
        fun of(soundscape: Soundscape, sampleRate: Int, fadeIn: Duration, seed: Long = SystemClock.elapsedRealtime()): SoundscapeSource =
            when (soundscape) {
                Soundscape.Rain -> RainSynth(seed, fadeIn = fadeIn, sampleRate = sampleRate).let { s ->
                    source(s::fill, s::fadeOut) { s.isSilent }
                }
                Soundscape.Wind -> WindSynth(seed, fadeIn = fadeIn, sampleRate = sampleRate).let { s ->
                    source(s::fill, s::fadeOut) { s.isSilent }
                }
                Soundscape.Pink, Soundscape.Brown -> NoiseSynth(
                    if (soundscape == Soundscape.Pink) NoiseColor.Pink else NoiseColor.Brown, seed, fadeIn = fadeIn, sampleRate = sampleRate,
                ).let { s -> source(s::fill, s::fadeOut) { s.isSilent } }
            }

        private inline fun source(
            crossinline fill: (ShortArray, Int, Int) -> Unit,
            crossinline fadeOut: (Duration) -> Unit,
            crossinline silent: () -> Boolean,
        ): SoundscapeSource = object : SoundscapeSource {
            override fun fill(out: ShortArray, offset: Int, length: Int) = fill(out, offset, length)
            override fun fadeOut(over: Duration) = fadeOut(over)
            override val isSilent: Boolean get() = silent()
        }
    }
}

/**
 * The optional soundscape (PLAN §3.2 row 11: off by default; noise "may help with ADHD; may hurt otherwise"; no music),
 * streamed from a core synth into a MODE_STREAM AudioTrack by **its own thread** (audio priority), never a coroutine on
 * a shared pool: a late buffer is an audible click.
 *
 * Cheap on purpose: 22.05 kHz mono (half of the cues' rate; the synths have nothing above 5 kHz that matters), a
 * half-second buffer written in ~93 ms chunks, so the thread wakes about 11 times a second. Fades are in the samples
 * (the synth's raised-cosine ramps), not volume steps: [play] fades in over [FADE_IN] after [START_DELAY] (Begin's
 * breath stays silent), [stop] fades out over [FADE_OUT], then the track is released. The in-app [volume] is the
 * track's gain; the system volume is never touched. Like the cues, it plays on USAGE_ALARM (M0 §5: the media stream
 * sits at 2/15 and Frameo resets it).
 */
class SoundscapePlayer(private val sampleRate: Int = SAMPLE_RATE) {
    private val lock = Any()
    private var stream: Stream? = null

    @Volatile private var gain = 0.35f

    private val _playing = MutableStateFlow<Soundscape?>(null)

    /** What is playing (or fading in); null when silent. */
    val playing: StateFlow<Soundscape?> = _playing.asStateFlow()

    /** Plays [soundscape] (null: stops). The same one already playing just keeps going. */
    fun set(soundscape: Soundscape?, volume: Float, delay: Duration = START_DELAY) = synchronized(lock) {
        gain = volume.coerceIn(0f, 1f)
        val current = stream
        if (current != null && !current.stopping && current.soundscape == soundscape) return@synchronized
        current?.finish()
        stream = null
        _playing.value = soundscape
        if (soundscape == null) return@synchronized
        stream = Stream(soundscape, delay, null).also { it.start() }
        Logs.event("soundscape", "$soundscape at ${(gain * 100).toInt()}% (${sampleRate / 1000.0} kHz stream)")
    }

    /** Settings' preview: [soundscape] for [length], then it fades out by itself. */
    fun preview(soundscape: Soundscape, volume: Float, length: Duration = PREVIEW) = synchronized(lock) {
        gain = volume.coerceIn(0f, 1f)
        stream?.finish()
        _playing.value = soundscape
        stream = Stream(soundscape, Duration.ZERO, length).also { it.start() }
        Logs.event("soundscape", "preview $soundscape at ${(gain * 100).toInt()}%")
    }

    /** The in-app soundscape volume changed: applied to the playing track at its next chunk. */
    fun volume(volume: Float) {
        gain = volume.coerceIn(0f, 1f)
    }

    fun stop() = set(null, gain)

    private inner class Stream(val soundscape: Soundscape, private val delay: Duration, private val length: Duration?) :
        Thread("soundscape") {
        @Volatile var stopping = false
            private set

        init {
            isDaemon = true
        }

        /** Fades out and ends: the loop notices at its next chunk, the start delay within [POLL_MS]. */
        fun finish() {
            stopping = true
        }

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val until = SystemClock.elapsedRealtime() + delay.inWholeMilliseconds
            while (!stopping && SystemClock.elapsedRealtime() < until) SystemClock.sleep(POLL_MS)
            if (stopping) return done()
            val track = track() ?: return done()
            val source = SoundscapeSource.of(soundscape, sampleRate, FADE_IN)
            val chunk = ShortArray(CHUNK)
            val started = SystemClock.elapsedRealtime()
            var applied = -1f
            var fading = false
            try {
                track.play()
                while (true) {
                    val g = gain
                    if (g != applied) {
                        track.setVolume(g)
                        applied = g
                    }
                    val over = length != null && SystemClock.elapsedRealtime() - started > length.inWholeMilliseconds
                    if ((stopping || over) && !fading) {
                        source.fadeOut(FADE_OUT)
                        fading = true
                    }
                    source.fill(chunk, 0, chunk.size)
                    if (track.write(chunk, 0, chunk.size) < 0) break
                    if (source.isSilent) break
                }
                // The tail of the fade still sits in the buffer: let it play out before stopping
                SystemClock.sleep(BUFFER_MS)
            } catch (e: IllegalStateException) {
                Logs.warn("soundscape", "track failed: ${e.message}")
            } finally {
                try {
                    track.stop()
                } catch (_: IllegalStateException) {
                }
                track.release()
                done()
            }
        }

        private fun done() {
            synchronized(lock) {
                if (stream === this) { // ended by itself (a preview, a failed track); a replaced stream changes nothing
                    stream = null
                    _playing.value = null
                }
            }
            Logs.event("soundscape", "$soundscape stopped")
        }

        private fun track(): AudioTrack? = try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bytes = maxOf(min, (sampleRate * BUFFER_MS / 1000).toInt() * 2)
            AudioTrack(attributes, format, bytes, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        } catch (e: Exception) { // no audio hardware (tests), or the mixer is out of tracks
            Logs.warn("soundscape", "can't create a track: ${e.message}")
            null
        }
    }

    companion object {
        const val SAMPLE_RATE: Int = 22_050

        /** ≈ 93 ms at 22.05 kHz. */
        private const val CHUNK = 2_048
        private const val BUFFER_MS = 500L
        private const val POLL_MS = 100L
        val FADE_IN: Duration = 4.seconds
        val FADE_OUT: Duration = 1500.milliseconds

        /** After Begin's 3 s "breathe in": the room goes quiet first, then the sound arrives. */
        val START_DELAY: Duration = 3.seconds
        val PREVIEW: Duration = 6.seconds
    }
}
