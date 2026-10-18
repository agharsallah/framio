package dev.framio.mawaqit.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Log
import dev.framio.mawaqit.core.Effective.Sound
import dev.framio.mawaqit.data.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Plays the adhan and the short signals on the frame's speaker (music stream, so Frameo's videos pause on the
 * focus request). Adhans are downloaded once into files/audio: the adhan must not depend on Wi-Fi at the moment
 * it is due. The user's own adhans ([Sound.Custom]) are already there (see [CustomAdhans]).
 */
class AdhanPlayer(private val context: Context, private val scope: CoroutineScope) {

    data class Playing(val what: String)

    private val _playing = MutableStateFlow<Playing?>(null)
    val playing: StateFlow<Playing?> = _playing.asStateFlow()

    private val audio = context.getSystemService(AudioManager::class.java)
    private val dir = File(context.filesDir, "audio").apply { mkdirs() }
    private var player: MediaPlayer? = null
    private var job: Job? = null
    private var restoreVolume: Int? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { }

    /** Downloads the sounds we may need, in the background. */
    fun prefetch(sounds: Collection<Sound>) {
        scope.launch(Dispatchers.IO) {
            for (s in sounds + Sound.Cdn(BEEP)) {
                val (url, file) = source(s) ?: continue
                if (file.exists()) continue
                try {
                    Net.download(url, file)
                    Log.i(TAG, "cached ${file.name} (${file.length() / 1024} KB)")
                } catch (e: Exception) {
                    Log.w(TAG, "download $url failed: ${e.message}")
                }
            }
        }
    }

    /** @param volume 0–100 of the music stream, restored afterwards */
    fun play(sound: Sound, volume: Int, label: String) {
        stop()
        job = scope.launch {
            val file = if (sound is Sound.Custom) {
                CustomAdhans.fileOf(dir, sound.id).also { if (it == null) Log.w(TAG, "custom adhan ${sound.id} is gone, chime instead") }
            } else source(sound)?.let { (url, file) ->
                if (!file.exists()) {
                    try {
                        withContext(Dispatchers.IO) { Net.download(url, file) }
                    } catch (e: Exception) {
                        Log.w(TAG, "no adhan file, chime instead: ${e.message}")
                    }
                }
                file.takeIf { it.exists() }
            }
            setVolume(volume)
            Log.i(TAG, "playing ${file?.name ?: "chime"} ($label, volume $volume)")
            _playing.value = Playing(label)
            requestFocus()
            try {
                if (file != null) playFile(file) else Chime.play()
            } finally {
                release()
            }
        }
    }

    /** The iqama / reminder signal: Mawaqit's short "bip". */
    fun beep(volume: Int) {
        if (job?.isActive == true) return // never cut an adhan for a beep
        play(Sound.Cdn(BEEP), volume, "beep")
    }

    fun stop() {
        job?.cancel()
        job = null
        release()
    }

    private suspend fun playFile(file: File) {
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        val mp = MediaPlayer()
        player = mp
        withContext(Dispatchers.IO) {
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(file.path)
            mp.prepare()
        }
        mp.setOnCompletionListener { done.complete(Unit) }
        mp.setOnErrorListener { _, what, extra ->
            Log.w(TAG, "MediaPlayer error $what/$extra")
            done.complete(Unit)
            true
        }
        mp.start()
        done.await()
    }

    private fun release() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        if (_playing.value != null) {
            audio.abandonAudioFocus(focusListener)
            restoreVolume?.let { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0) }
            restoreVolume = null
            _playing.value = null
        }
    }

    @Suppress("DEPRECATION") // AudioFocusRequest is API 26
    private fun requestFocus() {
        audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
    }

    private fun setVolume(percent: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (restoreVolume == null) restoreVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * percent + 50) / 100, 0)
    }

    private fun source(s: Sound): Pair<String, File>? = when (s) {
        is Sound.Cdn -> "$CDN/${s.id}.mp3" to File(dir, "${s.id}.mp3")
        is Sound.Url -> s.url to File(dir, "mosque-" + Integer.toHexString(s.url.hashCode()) + ".mp3")
        Sound.Beep -> "$CDN/$BEEP.mp3" to File(dir, "$BEEP.mp3")
        is Sound.Custom -> null // stored by CustomAdhans, nothing to download
    }

    private companion object {
        const val TAG = "Adhan"
        const val CDN = "https://cdn.mawaqit.net/audio"
        const val BEEP = "bip"
    }
}

/** A soft three-note chime, synthesized: the fallback when no adhan file could be downloaded. */
private object Chime {
    private const val RATE = 44100

    suspend fun play() {
        val notes = doubleArrayOf(659.25, 783.99, 1046.5) // E5 G5 C6
        val noteMs = 420
        val n = RATE * noteMs / 1000
        val pcm = ShortArray(n * notes.size + RATE / 2)
        notes.forEachIndexed { i, f ->
            for (k in 0 until n + RATE / 2) {
                val idx = i * n + k
                if (idx >= pcm.size) break
                val t = k.toDouble() / RATE
                val v = 0.25 * exp(-3.5 * t) * (sin(2 * PI * f * t) + 0.3 * sin(4 * PI * f * t))
                pcm[idx] = (pcm[idx] + v * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
            }
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            delay(pcm.size * 1000L / RATE + 100)
        } finally {
            track.release()
        }
    }
}
