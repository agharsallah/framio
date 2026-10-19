package dev.framio.lab.labs.mic

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Level statistics of a block of 16-bit samples. Pure Kotlin, unit-tested.
 *
 * [dc] is the average: a codec with no mic connected sits at a constant offset.
 * [noise] is the RMS around that average, which also counts slow drift (the RK312x ADC offset
 * settles for ~1.5 s after power-up). [hf] is the RMS of sample-to-sample changes divided by √2:
 * equal to [noise] for white noise, near zero for drift. A real microphone always has some [hf].
 */
data class SignalStats(val dc: Double, val noise: Double, val hf: Double, val peak: Int) {

    /** Level relative to full scale; -inf for digital silence. */
    val dbfs: Double get() = if (noise <= 0) Double.NEGATIVE_INFINITY else 20 * log10(noise / Short.MAX_VALUE)

    /** No audio at all: the input changes by only a few LSB from sample to sample. */
    val flat: Boolean get() = hf < FLAT_HF_LSB

    companion object {
        const val FLAT_HF_LSB = 4.0

        fun of(samples: ShortArray, count: Int = samples.size, from: Int = 0): SignalStats {
            val n = count - from
            if (n <= 0) return SignalStats(0.0, 0.0, 0.0, 0)
            var sum = 0.0
            var peak = 0
            for (i in from until count) {
                sum += samples[i]
                peak = max(peak, abs(samples[i].toInt()))
            }
            val mean = sum / n
            var sq = 0.0
            var dsq = 0.0
            for (i in from until count) {
                val d = samples[i] - mean
                sq += d * d
                if (i > from) {
                    val step = (samples[i] - samples[i - 1]).toDouble()
                    dsq += step * step
                }
            }
            val hf = if (n > 1) sqrt(dsq / (n - 1) / 2) else 0.0
            return SignalStats(mean, sqrt(sq / n), hf, peak)
        }
    }
}
