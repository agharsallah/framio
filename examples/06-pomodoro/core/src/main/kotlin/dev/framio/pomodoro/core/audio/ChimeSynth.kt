package dev.framio.pomodoro.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The end-of-block cue: a soft two-note chime (E5 then A5, a rising fourth), rendered once into a static
 * buffer. Sine plus a little second and third harmonic, a gentle 25 ms raised-cosine attack, an exponential
 * decay and an 80 ms release, so it starts and ends at exactly zero and never clicks. Pure and deterministic.
 */
public object ChimeSynth {
    /** One note: frequency, start and length in ms, relative gain. */
    public data class Note(val hz: Double, val startMs: Int, val durationMs: Int, val gain: Double = 1.0)

    public const val ATTACK_MS: Double = 25.0
    public const val RELEASE_MS: Double = 80.0

    public val TWO_NOTE: List<Note> = listOf(
        Note(659.255, 0, 1_400),
        Note(880.0, 320, 1_700, gain = 0.8),
    )

    /** The chime with its peak at [peakDb] dBFS. */
    public fun render(peakDb: Double = -12.0, notes: List<Note> = TWO_NOTE, sampleRate: Int = Pcm.SAMPLE_RATE): ShortArray {
        val mix = mix(notes, sampleRate)
        val peak = mix.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: 1.0
        val scale = Pcm.dbToGain(peakDb) / peak
        return ShortArray(mix.size) { Pcm.toShort(mix[it] * scale) }
    }

    /**
     * Deep's end cue: the chime [repeats] times, spread over [over], its peak rising evenly in dB from
     * [startDb] to [endDb]. Waking you gently instead of startling you.
     */
    public fun rising(
        startDb: Double = -24.0,
        endDb: Double = -12.0,
        over: Duration = 8.seconds,
        repeats: Int = 3,
        sampleRate: Int = Pcm.SAMPLE_RATE,
    ): ShortArray {
        require(repeats >= 1)
        val one = mix(TWO_NOTE, sampleRate)
        val peak = one.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: 1.0
        val step = (over.inWholeMilliseconds.toDouble() / repeats).let { Pcm.frames(it, sampleRate) }
        val out = DoubleArray(step * (repeats - 1) + one.size)
        for (r in 0 until repeats) {
            val db = if (repeats == 1) startDb else startDb + (endDb - startDb) * r / (repeats - 1)
            val gain = Pcm.dbToGain(db) / peak
            val at = r * step
            for (i in one.indices) out[at + i] += one[i] * gain
        }
        return ShortArray(out.size) { Pcm.toShort(out[it]) }
    }

    /** Where each repeat of [rising] starts, in samples. */
    public fun risingStarts(over: Duration = 8.seconds, repeats: Int = 3, sampleRate: Int = Pcm.SAMPLE_RATE): List<Int> {
        val step = Pcm.frames(over.inWholeMilliseconds.toDouble() / repeats, sampleRate)
        return List(repeats) { it * step }
    }

    private fun mix(notes: List<Note>, sampleRate: Int): DoubleArray {
        val end = notes.maxOfOrNull { it.startMs + it.durationMs } ?: 0
        val frames = Pcm.frames(end.toDouble(), sampleRate)
        val mix = DoubleArray(frames)
        val attack = Pcm.frames(ATTACK_MS, sampleRate).coerceAtLeast(1)
        val release = Pcm.frames(RELEASE_MS, sampleRate).coerceAtLeast(1)
        for (note in notes) {
            val start = Pcm.frames(note.startMs.toDouble(), sampleRate)
            val count = min(Pcm.frames(note.durationMs.toDouble(), sampleRate), frames - start)
            val w = 2 * PI * note.hz / sampleRate
            val tau = count / 3.0
            for (i in 0 until count) {
                val a = if (i < attack) 0.5 - 0.5 * cos(PI * i / attack) else 1.0
                val tail = count - 1 - i
                val r = if (tail < release) 0.5 - 0.5 * cos(PI * tail / release) else 1.0
                val v = sin(w * i) + 0.12 * sin(2 * w * i) + 0.04 * sin(3 * w * i)
                mix[start + i] += v * a * r * exp(-i / tau) * note.gain
            }
        }
        return mix
    }
}
