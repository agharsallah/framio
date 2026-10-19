package dev.framio.lab.labs.background

/**
 * Statistics of a once-per-[periodMs] heartbeat: how many beats, the longest gap, and how many beats
 * came late (a gap over twice the period means the process was frozen, the CPU slept, or it was killed).
 * Pure Kotlin, unit-tested.
 */
class Heartbeat(private val periodMs: Long) {

    var count = 0
        private set
    var firstMs = 0L
        private set
    var lastMs = 0L
        private set
    var maxGapMs = 0L
        private set
    var lateBeats = 0
        private set

    /** Records a beat at [nowMs]; returns the gap since the previous one (0 for the first). */
    fun beat(nowMs: Long): Long {
        val gap = if (count == 0) 0L else nowMs - lastMs
        if (count == 0) firstMs = nowMs
        if (gap > maxGapMs) maxGapMs = gap
        if (gap > 2 * periodMs) lateBeats++
        lastMs = nowMs
        count++
        return gap
    }

    /** Beats that should have happened between the first and last, but didn't. */
    val missed: Long
        get() = if (count < 2) 0 else ((lastMs - firstMs) / periodMs + 1 - count).coerceAtLeast(0)

    val runningMs: Long get() = if (count == 0) 0 else lastMs - firstMs
}
