package dev.framio.lab.labs.system

/**
 * CPU time counters from the first line of /proc/stat ("cpu  user nice system idle iowait irq softirq …").
 * Pure parsing and math, so it is unit-tested on the JVM.
 */
data class CpuTimes(val busy: Long, val total: Long) {

    /** Share of time busy between [previous] and this sample, 0..1. */
    fun usageSince(previous: CpuTimes): Float {
        val dTotal = total - previous.total
        if (dTotal <= 0) return 0f
        return ((busy - previous.busy).toFloat() / dTotal).coerceIn(0f, 1f)
    }

    companion object {
        fun parse(procStatFirstLine: String): CpuTimes? {
            val parts = procStatFirstLine.trim().split(Regex("\\s+"))
            if (parts.firstOrNull() != "cpu" || parts.size < 5) return null
            val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (values.size < 4) return null
            val idle = values[3] + (values.getOrNull(4) ?: 0L) // idle + iowait
            val total = values.take(8).sum() // user..steal; guest is already counted in user
            return CpuTimes(busy = total - idle, total = total)
        }
    }
}

/** A fixed amount of integer + float work, used to compare cores and frequencies. */
object CpuBench {

    /** Runs for about [millis] and returns work units per second (higher is faster). */
    fun unitsPerSecond(millis: Long): Double {
        val start = System.nanoTime()
        val end = start + millis * 1_000_000
        var units = 0L
        var sink = 0.0
        while (System.nanoTime() < end) {
            sink += unit(units)
            units++
        }
        if (sink == 42.4242) println(sink) // keeps the JIT from dropping the work
        return units / ((System.nanoTime() - start) / 1e9)
    }

    /** One unit: a short integer hash loop and a few float ops. */
    fun unit(seed: Long): Double {
        var h = seed xor 0x9E3779B97F4A7C15uL.toLong()
        repeat(64) { h = (h xor (h ushr 31)) * 0x7FB5D329728EA185L }
        var f = (h and 0xFFFF).toDouble()
        repeat(16) { f = f * 1.0000001 + 0.5 }
        return f
    }
}
