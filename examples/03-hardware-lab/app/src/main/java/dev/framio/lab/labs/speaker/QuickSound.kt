package dev.framio.lab.labs.speaker

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.delay

/** Plays [Chime] once through a static AudioTrack on the music stream (so the Music volume applies). */
object QuickSound {

    private const val RATE = 44100

    /** Plays and waits for the end. Returns the frames the speaker path consumed (all of them = it played). */
    suspend fun chime(channel: Channel = Channel.Both, gain: Double = dbToGain(-10.0)): Int {
        val samples = Chime.render(RATE, gain, channel)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(samples, 0, samples.size)
            track.play()
            delay(Chime.durationMs() + 150L)
            return track.playbackHeadPosition
        } finally {
            track.release()
        }
    }

    val frames get() = RATE * Chime.durationMs() / 1000
}
