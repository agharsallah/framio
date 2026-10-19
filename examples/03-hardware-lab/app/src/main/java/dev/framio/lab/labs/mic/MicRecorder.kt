package dev.framio.lab.labs.mic

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder

/** Microphone input through AudioRecord, mono 16-bit. Caller must hold RECORD_AUDIO. */
object MicRecorder {

    enum class Source(val label: String, val value: Int) {
        Mic("MIC", MediaRecorder.AudioSource.MIC),
        Camcorder("CAMCORDER", MediaRecorder.AudioSource.CAMCORDER),
        Recognition("VOICE_RECOGNITION", MediaRecorder.AudioSource.VOICE_RECOGNITION),
        Communication("VOICE_COMMUNICATION", MediaRecorder.AudioSource.VOICE_COMMUNICATION),
    }

    const val RATE = 16_000

    fun supportedRates(): List<Int> = listOf(8000, 11025, 16000, 22050, 32000, 44100, 48000).filter {
        AudioRecord.getMinBufferSize(it, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0
    }

    @SuppressLint("MissingPermission") // callers check RECORD_AUDIO
    fun open(source: Source, rate: Int = RATE): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val rec = AudioRecord(source.value, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5 * 2))
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        return rec
    }

    /** Records [millis] ms and returns the samples. The first [skipMs] are dropped: the codec pops and its offset drifts for ~1.5 s after power-up. */
    fun capture(source: Source, millis: Int, skipMs: Int = 1500): ShortArray? {
        val rec = open(source) ?: return null
        try {
            val total = RATE * (millis + skipMs) / 1000
            val buf = ShortArray(total)
            rec.startRecording()
            var n = 0
            while (n < total) {
                val r = rec.read(buf, n, minOf(1024, total - n))
                if (r <= 0) break
                n += r
            }
            rec.stop()
            val skip = RATE * skipMs / 1000
            return if (n > skip) buf.copyOfRange(skip, n) else ShortArray(0)
        } finally {
            rec.release()
        }
    }

    /** Plays mono samples back through the speaker (static AudioTrack). */
    fun play(samples: ShortArray, rate: Int = RATE): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(samples, 0, samples.size)
        track.play()
        return track
    }
}
