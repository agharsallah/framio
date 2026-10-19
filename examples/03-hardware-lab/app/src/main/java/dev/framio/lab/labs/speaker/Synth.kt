package dev.framio.lab.labs.speaker

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/** Waveforms, a phase-continuous stereo oscillator, sweeps and WAV encoding. Pure Kotlin, unit-tested. */
enum class Waveform(val label: String) {
    Sine("Sine"), Square("Square"), Triangle("Triangle"), Saw("Saw");

    /** Value in -1..1 at [phase] in 0..1. */
    fun at(phase: Double): Double = when (this) {
        Sine -> sin(2 * PI * phase)
        Square -> if (phase < 0.5) 1.0 else -1.0
        Triangle -> 1 - 4 * abs(phase - 0.5)
        Saw -> 2 * phase - 1
    }
}

/** Where the tone plays. */
enum class Channel(val label: String, val left: Double, val right: Double) {
    Left("Left", 1.0, 0.0), Both("Both", 1.0, 1.0), Right("Right", 0.0, 1.0)
}

class Oscillator(private val sampleRate: Int) {
    private var phase = 0.0

    /**
     * Writes [frames] interleaved stereo 16-bit frames into [out].
     * [frequency] may change between calls; the phase carries over so there are no clicks.
     */
    fun render(out: ShortArray, frames: Int, frequency: Double, gain: Double, waveform: Waveform, channel: Channel) {
        val step = frequency / sampleRate
        for (i in 0 until frames) {
            val v = waveform.at(phase) * gain * Short.MAX_VALUE
            out[2 * i] = (v * channel.left).toInt().toShort()
            out[2 * i + 1] = (v * channel.right).toInt().toShort()
            phase += step
            if (phase >= 1.0) phase -= 1.0
        }
    }
}

object Sweep {
    /** Exponential (log) sweep: equal time per octave, which is how hearing works. */
    fun frequencyAt(seconds: Double, duration: Double, from: Double, to: Double): Double =
        from * (to / from).pow((seconds / duration).coerceIn(0.0, 1.0))
}

/** Decibels relative to full scale → linear gain. -6 dB ≈ 0.5. */
fun dbToGain(db: Double): Double = 10.0.pow(db / 20)

object Wav {
    /** A minimal PCM 16-bit WAV file (44-byte header + samples, little endian). */
    fun pcm16(samples: ShortArray, sampleRate: Int, channels: Int): ByteArray {
        val dataBytes = samples.size * 2
        val b = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            .putInt(sampleRate).putInt(sampleRate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes)
        samples.forEach { b.putShort(it) }
        return b.array()
    }

    /** A short click: 20 ms decaying 2 kHz burst, mono. */
    fun click(sampleRate: Int = 44100): ByteArray {
        val n = sampleRate / 50
        val s = ShortArray(n) { i ->
            val env = (1 - i.toDouble() / n).pow(3)
            (sin(2 * PI * 2000 * i / sampleRate) * env * 0.5 * Short.MAX_VALUE).toInt().toShort()
        }
        return pcm16(s, sampleRate, 1)
    }
}

/** A short rising three-note chime (C5 E5 G5), the quick "does sound work" test. Pure, unit-tested. */
object Chime {
    val notes = doubleArrayOf(523.25, 659.25, 783.99)
    const val NOTE_MS = 180
    const val GAP_MS = 40

    /** Interleaved stereo 16-bit samples. Each note fades out, and the gaps are silent. */
    fun render(sampleRate: Int = 44100, gain: Double = dbToGain(-10.0), channel: Channel = Channel.Both): ShortArray {
        val noteFrames = sampleRate * NOTE_MS / 1000
        val gapFrames = sampleRate * GAP_MS / 1000
        val out = ShortArray((noteFrames + gapFrames) * notes.size * 2)
        notes.forEachIndexed { n, f ->
            val start = n * (noteFrames + gapFrames)
            for (i in 0 until noteFrames) {
                val env = (1 - i.toDouble() / noteFrames).pow(2) // quick decay: sounds like a bell, no click at the end
                val v = sin(2 * PI * f * i / sampleRate) * env * gain * Short.MAX_VALUE
                out[2 * (start + i)] = (v * channel.left).toInt().toShort()
                out[2 * (start + i) + 1] = (v * channel.right).toInt().toShort()
            }
        }
        return out
    }

    fun durationMs() = (NOTE_MS + GAP_MS) * notes.size
}
