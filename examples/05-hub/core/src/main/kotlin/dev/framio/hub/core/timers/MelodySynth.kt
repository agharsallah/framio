package dev.framio.hub.core.timers

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** One note: a MIDI number, when it starts and how long it rings, in ms. */
data class Tone(val midi: Int, val startMs: Int, val durationMs: Int, val gain: Double = 1.0) {
    val hz: Double get() = 440.0 * 2.0.pow((midi - 69) / 12.0)
}

/**
 * Every timer's melody, from the C major pentatonic scale, so two timers ringing together still sound
 * pleasant. Each has its own contour (rise, fall, bounce…), so people learn which timer is which.
 */
object Melodies {
    private const val C5 = 72
    private const val D5 = 74
    private const val E5 = 76
    private const val G5 = 79
    private const val A5 = 81
    private const val C6 = 84
    private const val D6 = 86
    private const val E6 = 88

    private fun line(step: Int, vararg midi: Int) = midi.mapIndexed { i, m -> Tone(m, i * step, step * 2) }

    val all: List<List<Tone>> = listOf(
        line(160, C5, E5, G5, C6), // rise
        line(160, C6, A5, G5, E5), // fall
        line(140, G5, C6, G5, C6), // bounce
        line(180, D5, G5, A5, G5), // hill
        line(120, E5, G5, A5, C6, D6), // steps
        listOf(Tone(A5, 0, 260), Tone(A5, 300, 260), Tone(E6, 600, 520)), // echo
        line(200, C5, G5, E5, G5), // waltz
        line(130, E5, D5, E5, G5, A5), // wave
    )

    val count: Int get() = all.size

    fun get(index: Int): List<Tone> = all[index.mod(all.size)]

    // Short cues for everything else, quieter than alarms
    val noteArrived = listOf(Tone(G5, 0, 260, 0.7), Tone(C6, 140, 420, 0.7))
    val stepDone = listOf(Tone(E6, 0, 160, 0.6))
    val routineDone = line(110, C5, E5, G5, C6, E6)
    val hourly = listOf(Tone(G5, 0, 500, 0.6), Tone(C5, 450, 900, 0.6))
}

/**
 * Renders tones as 16-bit mono PCM: a sine with a little second harmonic and a bell-like envelope (5 ms
 * attack, exponential decay, 20 ms release), so nothing clicks. Pure and deterministic, so it's unit-tested.
 * Inspired by the Lab's Chime, which proved a static AudioTrack plays to the last frame on this frame.
 */
object MelodySynth {
    const val SAMPLE_RATE = 22_050
    private const val ATTACK_MS = 5.0
    private const val RELEASE_MS = 20.0

    /** [lengthMs] pads with silence (an alarm loops every 4 s); 0 = just the tones. Peak = [gain]. */
    fun render(tones: List<Tone>, gain: Double = 0.5, lengthMs: Int = 0, sampleRate: Int = SAMPLE_RATE): ShortArray {
        val end = tones.maxOfOrNull { it.startMs + it.durationMs } ?: 0
        val frames = sampleRate * maxOf(end, lengthMs) / 1000
        val mix = DoubleArray(frames)
        for (tone in tones) {
            val start = sampleRate * tone.startMs / 1000
            val count = min(sampleRate * tone.durationMs / 1000, frames - start)
            val w = 2 * PI * tone.hz / sampleRate
            val durS = tone.durationMs / 1000.0
            for (i in 0 until count) {
                val t = i.toDouble() / sampleRate
                val ms = t * 1000
                val attack = min(1.0, ms / ATTACK_MS)
                val release = min(1.0, (tone.durationMs - ms) / RELEASE_MS).coerceAtLeast(0.0)
                val decay = exp(-3.0 * t / durS)
                val v = sin(w * i) + 0.18 * sin(2 * w * i)
                mix[start + i] += v * attack * release * decay * tone.gain
            }
        }
        val peak = mix.maxOfOrNull { kotlin.math.abs(it) }?.takeIf { it > 0 } ?: 1.0
        val scale = gain * Short.MAX_VALUE / peak
        return ShortArray(frames) { (mix[it] * scale).toInt().toShort() }
    }

    /** Duration of [samples] in ms. */
    fun durationMs(samples: ShortArray, sampleRate: Int = SAMPLE_RATE) = samples.size * 1000L / sampleRate

    /**
     * Strength of [hz] in [samples] (Goertzel algorithm), 0..~1 for a full-scale sine. Used by the tests to
     * check that each note plays the right pitch.
     */
    fun magnitude(samples: ShortArray, from: Int, count: Int, hz: Double, sampleRate: Int = SAMPLE_RATE): Double {
        val w = 2 * PI * hz / sampleRate
        val coeff = 2 * kotlin.math.cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (i in from until min(from + count, samples.size)) {
            val s0 = samples[i] / 32768.0 + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        return kotlin.math.sqrt(power.coerceAtLeast(0.0)) / (count / 2.0)
    }
}
