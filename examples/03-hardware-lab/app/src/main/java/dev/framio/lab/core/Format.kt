package dev.framio.lab.core

import java.util.Locale

/** Number formatting shared by all labs. Always Locale.US so logs and the JSON report parse the same way. */
object Format {

    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(Locale.US, b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(Locale.US, b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> "%.1f KB".format(Locale.US, b / (1L shl 10).toDouble())
        else -> "$b B"
    }

    fun hz(f: Double): String = if (f >= 1000) "%.2f kHz".format(Locale.US, f / 1000) else "%.0f Hz".format(Locale.US, f)

    fun mhzFromKhz(khz: Long?): String = khz?.let { "${it / 1000} MHz" } ?: "–"

    fun ms(ms: Double): String = "%.1f ms".format(Locale.US, ms)

    fun duration(ms: Long): String {
        val s = ms / 1000
        val d = s / 86_400
        val h = s % 86_400 / 3600
        val m = s % 3600 / 60
        return if (d > 0) "${d}d ${h}h ${m}m" else if (h > 0) "${h}h ${m}m ${s % 60}s" else "${m}m ${s % 60}s"
    }

    fun f1(v: Double): String = "%.1f".format(Locale.US, v)
    fun f2(v: Double): String = "%.2f".format(Locale.US, v)
    fun f1(v: Float): String = f1(v.toDouble())
    fun f2(v: Float): String = f2(v.toDouble())
}
