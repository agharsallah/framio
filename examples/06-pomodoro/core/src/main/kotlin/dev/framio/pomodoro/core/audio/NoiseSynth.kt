package dev.framio.pomodoro.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public enum class NoiseColor { Pink, Brown }

/**
 * Pink or brown noise for the optional soundscape ("may help with ADHD; may hurt otherwise"), streamed into
 * a MODE_STREAM AudioTrack from its own thread. A generator with state: consecutive [fill] calls continue the
 * same signal, so buffer boundaries have no seam. Fades in over [fadeIn]; [fadeOut] ramps it down to silence.
 * Deterministic for a [seed].
 *
 * Pink: Paul Kellet's refined filter of white noise (−3 dB/octave). Brown: leaky integrated white noise
 * (−6 dB/octave). [level] is the typical peak, well under full scale.
 */
public class NoiseSynth(
    public val color: NoiseColor,
    seed: Long = 1,
    private val level: Double = 0.25,
    fadeIn: Duration = 2.seconds,
    private val sampleRate: Int = Pcm.SAMPLE_RATE,
) {
    private val random = Random(seed)
    private val fade = Fade(Pcm.frames(fadeIn.inWholeMilliseconds.toDouble(), sampleRate))
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var b3 = 0.0
    private var b4 = 0.0
    private var b5 = 0.0
    private var b6 = 0.0
    private var brown = 0.0

    /** True once a [fadeOut] has finished: everything after is silence and the track can stop. */
    public val isSilent: Boolean get() = fade.silent

    public fun fadeOut(over: Duration = 1.seconds) {
        fade.out(Pcm.frames(over.inWholeMilliseconds.toDouble(), sampleRate))
    }

    public fun fill(out: ShortArray, offset: Int = 0, length: Int = out.size - offset) {
        for (i in offset until offset + length) out[i] = Pcm.toShort(sample() * level * fade.next())
    }

    public fun next(count: Int): ShortArray = ShortArray(count).also { fill(it) }

    private fun sample(): Double {
        val white = random.nextDouble() * 2 - 1
        return when (color) {
            NoiseColor.Pink -> {
                b0 = 0.99886 * b0 + white * 0.0555179
                b1 = 0.99332 * b1 + white * 0.0750759
                b2 = 0.96900 * b2 + white * 0.1538520
                b3 = 0.86650 * b3 + white * 0.3104856
                b4 = 0.55000 * b4 + white * 0.5329522
                b5 = -0.7616 * b5 - white * 0.0168980
                val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362
                b6 = white * 0.115926
                pink * PINK_GAIN
            }
            NoiseColor.Brown -> {
                brown = (brown + 0.02 * white) / 1.02
                brown * BROWN_GAIN
            }
        }
    }

    private companion object {
        /** Scale each colour so its typical peak is about 1 before [level]. */
        const val PINK_GAIN = 0.11 * 2.2
        const val BROWN_GAIN = 3.5 * 2.0
    }
}

/** A raised-cosine fade-in from the first sample, and an optional fade-out to silence. */
internal class Fade(private val inFrames: Int) {
    private var n = 0L
    private var outFrames = 0
    private var outLeft = -1
    private var outFrom = 1.0

    val silent: Boolean get() = outLeft == 0

    fun out(frames: Int) {
        if (outLeft >= 0) return
        outFrom = current()
        outFrames = frames.coerceAtLeast(1)
        outLeft = outFrames
    }

    fun next(): Double {
        val g = current()
        n++
        if (outLeft > 0) outLeft--
        return g
    }

    private fun current(): Double {
        val fadeIn = if (inFrames <= 0 || n >= inFrames) 1.0 else 0.5 - 0.5 * cos(PI * n / inFrames)
        return when {
            outLeft < 0 -> fadeIn
            outLeft == 0 -> 0.0
            else -> outFrom * (0.5 - 0.5 * cos(PI * outLeft / outFrames))
        }
    }
}
