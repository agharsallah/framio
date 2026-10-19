package dev.framio.lab.labs.speaker

import android.media.audiofx.Visualizer
import kotlin.math.hypot

/**
 * Taps an audio session with Visualizer (needs RECORD_AUDIO) and reads its FFT.
 * This proves samples really reach the mixer at the right frequency, without a microphone.
 */
class Spectrum(sessionId: Int) {

    private val visualizer = Visualizer(sessionId).apply {
        enabled = false
        captureSize = Visualizer.getCaptureSizeRange()[1] // largest: best frequency resolution
        enabled = true
    }

    val captureSize get() = visualizer.captureSize

    /** Magnitudes of bins 0..captureSize/2-1. */
    fun magnitudes(): FloatArray {
        val fft = ByteArray(visualizer.captureSize)
        visualizer.getFft(fft)
        return magnitudesOf(fft)
    }

    /** Hz per FFT bin. Visualizer reports its sampling rate in milliHertz. */
    val binHz: Double get() = visualizer.samplingRate / 1000.0 / visualizer.captureSize

    fun waveform(): ByteArray = ByteArray(visualizer.captureSize).also { visualizer.getWaveForm(it) }

    fun release() = visualizer.release()

    companion object {
        /**
         * Visualizer FFT layout: [0] = DC real, [1] = Nyquist real, then (re, im) pairs for bins 1..n/2-1.
         * Pure, unit-tested.
         */
        fun magnitudesOf(fft: ByteArray): FloatArray {
            val n = fft.size / 2
            val out = FloatArray(n)
            out[0] = kotlin.math.abs(fft[0].toFloat())
            for (k in 1 until n) out[k] = hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat())
            return out
        }

        /** Index of the strongest bin, ignoring DC. */
        fun peakBin(mags: FloatArray): Int {
            var best = 1
            for (k in 2 until mags.size) if (mags[k] > mags[best]) best = k
            return best
        }
    }
}
