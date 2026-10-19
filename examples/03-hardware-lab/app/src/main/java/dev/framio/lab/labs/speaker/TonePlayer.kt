package dev.framio.lab.labs.speaker

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Streams a generated tone through AudioTrack on its own thread. Parameters can change while it plays.
 * [sessionId] is the audio session that Visualizer and Equalizer attach to.
 */
class TonePlayer(val sampleRate: Int = 44100) {

    @Volatile var frequency = 440.0
    @Volatile var gain = dbToGain(-18.0)
    @Volatile var waveform = Waveform.Sine
    @Volatile var channel = Channel.Both

    /** When set, frequency follows a 20 Hz → 16 kHz log sweep of this many seconds, then playback stops. */
    @Volatile var sweepSeconds: Double? = null

    /** Frames handed to the track so far. */
    @Volatile var framesWritten = 0L
        private set

    private val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)

    val track: AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
        )
        .setBufferSizeInBytes(minBuffer * 2)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    val sessionId get() = track.audioSessionId

    @Volatile private var thread: Thread? = null

    val playing get() = thread != null

    /** Called on the audio thread when a sweep reaches its end. */
    var onFinished: (() -> Unit)? = null

    fun start() {
        if (thread != null) return
        framesWritten = 0
        track.play()
        thread = Thread({ loop() }, "TonePlayer").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        val t = thread ?: return
        thread = null
        t.join(500)
        track.pause()
        track.flush()
    }

    fun release() {
        stop()
        track.release()
    }

    /** The frequency being played right now (moves during a sweep). */
    @Volatile var currentFrequency = frequency
        private set

    private fun loop() {
        val osc = Oscillator(sampleRate)
        val block = 512
        val buf = ShortArray(block * 2)
        var t = 0.0
        while (thread === Thread.currentThread()) {
            val sweep = sweepSeconds
            val f = if (sweep != null) Sweep.frequencyAt(t, sweep, 20.0, 16_000.0) else frequency
            currentFrequency = f
            osc.render(buf, block, f, gain, waveform, channel)
            val n = track.write(buf, 0, buf.size)
            if (n < 0) break
            framesWritten += block
            t += block.toDouble() / sampleRate
            if (sweep != null && t >= sweep) {
                sweepSeconds = null
                thread = null
                track.pause()
                track.flush()
                onFinished?.invoke()
            }
        }
    }
}
