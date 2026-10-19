package dev.framio.pomodoro.core.audio

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** 16-bit mono PCM helpers shared by the synths. */
public object Pcm {
    public const val SAMPLE_RATE: Int = 44_100
    public const val FULL_SCALE: Int = 32_767

    /** −24 dB → 0.063. */
    public fun dbToGain(db: Double): Double = 10.0.pow(db / 20.0)

    /** A sample in −1..1 to 16 bits; anything beyond is clamped (the synths stay well inside). */
    public fun toShort(x: Double): Short = (x.coerceIn(-1.0, 1.0) * FULL_SCALE).toInt().toShort()

    public fun peak(samples: ShortArray, from: Int = 0, to: Int = samples.size): Int {
        var p = 0
        for (i in from until to) p = maxOf(p, abs(samples[i].toInt()))
        return p
    }

    public fun rms(samples: ShortArray, from: Int = 0, to: Int = samples.size): Double {
        if (to <= from) return 0.0
        var sum = 0.0
        for (i in from until to) {
            val v = samples[i] / FULL_SCALE.toDouble()
            sum += v * v
        }
        return sqrt(sum / (to - from))
    }

    /** Samples for [ms] milliseconds. */
    public fun frames(ms: Double, sampleRate: Int = SAMPLE_RATE): Int = (ms * sampleRate / 1000.0).toInt()
}
