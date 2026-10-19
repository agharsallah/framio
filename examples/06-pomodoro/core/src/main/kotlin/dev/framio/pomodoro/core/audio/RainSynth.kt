package dev.framio.pomodoro.core.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Procedural rain: a bed of band-limited noise (one-pole low-pass and high-pass) plus sparse droplets, short
 * decaying sine blips at random pitches and times. A streaming generator like [NoiseSynth]: consecutive
 * [fill] calls continue seamlessly, it fades in over [fadeIn], and it is deterministic for a [seed].
 */
public class RainSynth(
    seed: Long = 1,
    private val level: Double = 0.25,
    private val dropsPerSecond: Double = 7.0,
    fadeIn: Duration = 2.seconds,
    private val sampleRate: Int = Pcm.SAMPLE_RATE,
) {
    private val random = Random(seed)
    private val fade = Fade(Pcm.frames(fadeIn.inWholeMilliseconds.toDouble(), sampleRate))
    private val lowA = 1 - exp(-2 * PI * 1_800.0 / sampleRate)
    private val highA = exp(-2 * PI * 200.0 / sampleRate)
    private var low = 0.0
    private var highIn = 0.0
    private var high = 0.0

    // Droplet voices
    private val phase = DoubleArray(VOICES)
    private val step = DoubleArray(VOICES)
    private val amp = DoubleArray(VOICES)
    private val decay = DoubleArray(VOICES)
    private val age = IntArray(VOICES) { -1 }
    private val attack = Pcm.frames(1.0, sampleRate).coerceAtLeast(1)

    public val isSilent: Boolean get() = fade.silent

    public fun fadeOut(over: Duration = 1.seconds) {
        fade.out(Pcm.frames(over.inWholeMilliseconds.toDouble(), sampleRate))
    }

    public fun fill(out: ShortArray, offset: Int = 0, length: Int = out.size - offset) {
        val dropChance = dropsPerSecond / sampleRate
        for (i in offset until offset + length) {
            val white = random.nextDouble() * 2 - 1
            low += lowA * (white - low)
            high = highA * (high + low - highIn)
            highIn = low
            if (random.nextDouble() < dropChance) startDrop()
            out[i] = Pcm.toShort((high * BED + drops() * DROPS) * level * fade.next())
        }
    }

    public fun next(count: Int): ShortArray = ShortArray(count).also { fill(it) }

    private fun startDrop() {
        val v = (0 until VOICES).firstOrNull { age[it] < 0 } ?: return
        step[v] = 2 * PI * (1_500.0 + random.nextDouble() * 3_000.0) / sampleRate
        amp[v] = 0.2 + random.nextDouble() * 0.4
        decay[v] = exp(-1.0 / (sampleRate * (0.008 + random.nextDouble() * 0.017)))
        phase[v] = 0.0
        age[v] = 0
    }

    private fun drops(): Double {
        var sum = 0.0
        for (v in 0 until VOICES) {
            if (age[v] < 0) continue
            val a = if (age[v] < attack) age[v].toDouble() / attack else 1.0
            sum += sin(phase[v]) * amp[v] * a
            phase[v] += step[v]
            amp[v] *= if (age[v] < attack) 1.0 else decay[v]
            age[v]++
            if (amp[v] < 1e-4) age[v] = -1
        }
        return sum
    }

    private companion object {
        const val VOICES = 8
        const val BED = 2.0
        const val DROPS = 2.0
    }
}
