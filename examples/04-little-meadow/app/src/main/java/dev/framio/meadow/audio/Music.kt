package dev.framio.meadow.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import dev.framio.meadow.core.TAG

/** Background music tracks in assets/music (tools/sound/make_sounds.py). Each loops seamlessly. */
enum class Track(val file: String, val level: Float) {
    Meadow("meadow", 0.22f),   // home and worlds: kalimba and soft pad, 72 bpm
    Studio("studio", 0.18f),   // making things: slower, sparser
    Night("night", 0.3f),      // the night light: a music-box lullaby
    Calm("calm", 0.2f),        // breathing: long pad chords only
}

/**
 * One looping track, very quiet, ducked further while a friend speaks. Fades in and out over about
 * a second so nothing ever starts abruptly.
 */
class Music(private val context: Context) {

    private val thread = HandlerThread("music").apply { start() }
    private val handler = Handler(thread.looper)
    private var player: MediaPlayer? = null
    private var current: Track? = null
    private var level = 0f
    private var target = 0f
    @Volatile private var enabled = true
    @Volatile private var ducked = false
    /** 0..1 on top of each track's level (0 = muted); fades to the new level. */
    @Volatile var master = 1f
        set(value) {
            field = value
            handler.post { retarget() }
        }

    fun play(track: Track?) = handler.post {
        if (track == current) return@post
        current = track
        fadeTo(0f) {
            release()
            if (track != null && enabled) start(track)
        }
    }

    fun setEnabled(on: Boolean) = handler.post {
        if (on == enabled) return@post
        enabled = on
        val t = current
        if (!on) fadeTo(0f) { release() } else if (t != null && player == null) start(t)
    }

    fun duck(on: Boolean) {
        ducked = on
        handler.post { retarget() }
    }

    fun pause() = handler.post { player?.let { if (it.isPlaying) it.pause() } }

    fun resume() = handler.post { player?.let { if (!it.isPlaying && enabled) it.start() } }

    // ---- music thread

    private fun start(track: Track) {
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            context.assets.openFd("music/${track.file}.ogg").use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            mp.isLooping = true
            mp.prepare()
            level = 0f
            mp.setVolume(0f, 0f)
            mp.start()
            player = mp
            retarget()
        } catch (e: Exception) {
            Log.w(TAG, "music ${track.file} failed", e)
        }
    }

    private fun retarget() {
        val t = current ?: return
        fadeTo(t.level * master * if (ducked) 0.45f else 1f)
    }

    private var fadeToken = 0

    private fun fadeTo(to: Float, then: (() -> Unit)? = null) {
        target = to
        val token = ++fadeToken
        val step = object : Runnable {
            override fun run() {
                if (token != fadeToken) return
                val d = target - level
                level = if (kotlin.math.abs(d) < 0.01f) target else level + d * 0.18f
                player?.setVolume(level, level)
                if (level != target) handler.postDelayed(this, 40) else then?.invoke()
            }
        }
        if (player == null) {
            level = to
            then?.invoke()
        } else handler.post(step)
    }

    private fun release() {
        player?.release()
        player = null
    }
}
