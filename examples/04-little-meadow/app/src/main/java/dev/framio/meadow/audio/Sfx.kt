package dev.framio.meadow.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import dev.framio.meadow.core.TAG
import kotlin.math.abs
import kotlin.math.pow

/**
 * Short sounds, all soft and rounded (tools/sound/make_sounds.py renders them). SoundPool keeps
 * them decoded in memory, so a tap answers in a few milliseconds.
 */
enum class Sound(val file: String) {
    Tap("tap"),          // a soft wooden tock: any button
    Pop("pop"),          // a bubble
    Good("good"),        // three rising marimba notes: right answer
    Sparkle("sparkle"),  // a little bell arpeggio: sticker, celebration
    Place("place"),      // a soft plop: something dropped where it belongs
    Hmm("hmm"),          // two low, gentle notes: "not quite", never a buzzer
    Whoosh("whoosh"),    // airy swish: page turn, things flying in
    Pick("pick"),        // a tiny lift: picking something up
    Drum("drum"),        // soft tom
    Shaker("shaker"),
    Clap("clap"),
    Ding("ding"),        // a single soft bell: counting, attention
    Splash("splash"),    // water: washing hands
    Brush("brush"),      // a short scrub: brushing teeth
    Yawn("yawn"),        // slide down: sleepy
}

enum class Instrument(val file: String, val bases: IntArray) {
    Marimba("marimba", intArrayOf(60, 66, 72, 78, 84)),
    Bell("bell", intArrayOf(60, 66, 72, 78, 84)),
    Kalimba("kalimba", intArrayOf(60, 66, 72, 78, 84)),
    MusicBox("musicbox", intArrayOf(66, 72, 78, 84, 90)),
}

class Sfx(context: Context) {

    private val pool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<String, Int>()
    private val lastPlayed = HashMap<String, Long>()
    @Volatile var volume = 0.8f

    init {
        // SoundPool decodes on its own thread, but opening 55 files still takes a while: not on the main thread
        Thread({
            val am = context.assets
            val names = Sound.entries.map { it.file } + Instrument.entries.flatMap { i -> i.bases.map { "${i.file}_$it" } }
            for (n in names) {
                try {
                    am.openFd("sfx/$n.ogg").use { fd -> pool.load(fd, 1).let { id -> synchronized(ids) { ids[n] = id } } }
                } catch (e: Exception) {
                    Log.w(TAG, "sfx $n missing", e)
                }
            }
        }, "sfx-load").start()
    }

    /** [rate] 0.5..2 shifts the pitch; the same sound twice within 40 ms plays once (a palm on the screen). */
    fun play(s: Sound, volume: Float = 1f, rate: Float = 1f) = playFile(s.file, volume, rate)

    /** A note by MIDI number (60 = middle C) on [instrument], pitched from the nearest recorded base. */
    fun note(instrument: Instrument, midi: Int, volume: Float = 1f) {
        val base = instrument.bases.minBy { abs(it - midi) }
        val rate = 2.0.pow((midi - base) / 12.0).toFloat().coerceIn(0.5f, 2f)
        playFile("${instrument.file}_$base", volume, rate, debounceMs = 0)
    }

    private fun playFile(name: String, volume: Float, rate: Float, debounceMs: Long = 40) {
        val id = synchronized(ids) { ids[name] } ?: return
        val now = SystemClock.uptimeMillis()
        if (debounceMs > 0 && now - (lastPlayed[name] ?: 0) < debounceMs) return
        lastPlayed[name] = now
        val v = (volume * this.volume).coerceIn(0f, 1f)
        pool.play(id, v, v, 1, 0, rate)
    }
}
