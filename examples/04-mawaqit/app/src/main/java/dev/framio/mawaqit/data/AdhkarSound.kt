package dev.framio.mawaqit.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The adhkar's two sounds, synthesized: a short, soft tick on each count (the frame has no vibrator, so this is the
 * "feel" of a tasbih bead) and a gentle two-note chime when a reminder appears. Both are quiet on purpose and play at
 * the music stream's current volume, never changing it.
 */
class AdhkarSound {
    private var tick: AudioTrack? = null

    fun tick() {
        try {
            val t = tick ?: track(pcm(doubleArrayOf(1320.0), 36, 0.07, 140.0)).also { tick = it }
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.play()
        } catch (e: Exception) {
            Log.w(TAG, "tick: ${e.message}")
            release()
        }
    }

    /** Fire and forget: released once played. */
    fun chime() {
        try {
            val pcm = pcm(doubleArrayOf(659.25, 987.77), 700, 0.12, 3.2) // E5, B5
            val t = track(pcm)
            t.setNotificationMarkerPosition(pcm.size - 1)
            t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack) = track.release()
                override fun onPeriodicNotification(track: AudioTrack) = Unit
            })
            t.play()
        } catch (e: Exception) {
            Log.w(TAG, "chime: ${e.message}")
        }
    }

    fun release() {
        tick?.release()
        tick = null
    }

    /** One decaying sine per note, notes one after the other, each [noteMs] long. */
    private fun pcm(notes: DoubleArray, noteMs: Int, amplitude: Double, decay: Double): ShortArray {
        val n = RATE * noteMs / 1000
        val out = ShortArray(n * notes.size)
        notes.forEachIndexed { i, f ->
            for (k in 0 until n) {
                val t = k.toDouble() / RATE
                val attack = (k / (RATE * 0.004)).coerceAtMost(1.0) // 4 ms: no click at the start
                val v = amplitude * attack * exp(-decay * t) * (sin(2 * PI * f * t) + 0.25 * sin(4 * PI * f * t))
                out[i * n + k] = (v * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
            }
        }
        return out
    }

    private fun track(pcm: ShortArray): AudioTrack {
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(pcm, 0, pcm.size)
        return t
    }

    private companion object {
        const val TAG = "AdhkarSound"
        const val RATE = 22050
    }
}
