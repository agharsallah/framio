package dev.framio.pomodoro.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Procedural wind: white noise through a band-pass whose centre and loudness drift together in slow gusts (a gust is
 * louder and a little higher, like air through trees), over a soft brown rumble. A streaming generator like
 * [NoiseSynth]: consecutive [fill] calls continue seamlessly, it fades in over [fadeIn], [fadeOut] ramps it to
 * silence, and it is deterministic for a [seed].
 *
 * Cheap on the frame's CPU: the filter is a Chamberlin state-variable filter (three multiply-adds a sample) and its
 * coefficient is recomputed only every [CONTROL] samples. Gusts are cosine moves between random targets, 1.5–5 s apart.
 */
public class WindSynth(
    seed: Long = 1,
    private val level: Double = 0.25,
    fadeIn: Duration = 2.seconds,
    private val sampleRate: Int = Pcm.SAMPLE_RATE,
) {
    private val random = Random(seed)
    private val fade = Fade(Pcm.frames(fadeIn.inWholeMilliseconds.toDouble(), sampleRate))

    // State-variable band-pass
    private var low = 0.0
    private var band = 0.0
    private var f = 0.0

    // Rumble: leaky integrated white noise
    private var brown = 0.0

    // A gentle low-pass after the band-pass: wind has no hiss
    private val smoothA = 1 - kotlin.math.exp(-2 * PI * SMOOTH_HZ / sampleRate)
    private var smooth = 0.0

    // Gust envelope: from [gustFrom] to [gustTo] over [gustLength] samples
    private var gustFrom = 0.5
    private var gustTo = 0.5
    private var gustLength = 1
    private var gustAt = 0
    private var gust = 0.5
    private var control = 0

    init {
        nextGust()
        gustFrom = gustTo
        retune()
    }

    public val isSilent: Boolean get() = fade.silent

    public fun fadeOut(over: Duration = 1.seconds) {
        fade.out(Pcm.frames(over.inWholeMilliseconds.toDouble(), sampleRate))
    }

    public fun fill(out: ShortArray, offset: Int = 0, length: Int = out.size - offset) {
        for (i in offset until offset + length) {
            if (control == 0) retune()
            control = (control + 1) % CONTROL
            val white = random.nextDouble() * 2 - 1
            val high = white - low - DAMPING * band
            band += f * high
            low += f * band
            brown = (brown + 0.02 * white) / 1.02
            smooth += smoothA * (band - smooth)
            val air = smooth * BAND_GAIN * (0.08 + 0.92 * gust * gust)
            val rumble = brown * RUMBLE_GAIN * (0.5 + 0.5 * gust)
            out[i] = Pcm.toShort((air + rumble) * level * fade.next())
        }
    }

    public fun next(count: Int): ShortArray = ShortArray(count).also { fill(it) }

    /** Advances the gust by one control period and recomputes the band-pass centre from it. */
    private fun retune() {
        gustAt += CONTROL
        if (gustAt >= gustLength) nextGust()
        val t = gustAt.toDouble() / gustLength
        gust = gustFrom + (gustTo - gustFrom) * (0.5 - 0.5 * cos(PI * t))
        val centre = LOW_HZ + (HIGH_HZ - LOW_HZ) * gust
        f = 2 * sin(PI * centre / sampleRate)
    }

    private fun nextGust() {
        gustFrom = gust
        gustTo = 0.1 + random.nextDouble() * 0.9
        gustLength = (sampleRate * (1.5 + random.nextDouble() * 3.5)).toInt()
        gustAt = 0
    }

    private companion object {
        /** Samples between filter updates (≈ 0.7 ms at 44.1 kHz): far faster than any gust moves. */
        const val CONTROL = 32
        const val LOW_HZ = 220.0
        const val HIGH_HZ = 900.0

        /** 1/Q of the band-pass: wide enough to sound like air, not a whistle. */
        const val DAMPING = 1.1
        const val SMOOTH_HZ = 1_100.0
        const val BAND_GAIN = 4.0
        const val RUMBLE_GAIN = 3.0
    }
}
