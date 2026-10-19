package dev.framio.lab.labs.display

/** Rolling frame-interval statistics. Pure Kotlin, unit-tested. */
class FrameStats(private val capacity: Int = 240) {
    private val intervals = ArrayDeque<Long>()

    fun add(intervalNs: Long) {
        if (intervalNs <= 0) return
        intervals.addLast(intervalNs)
        if (intervals.size > capacity) intervals.removeFirst()
    }

    val count get() = intervals.size

    /** Average frames per second over the window. */
    val fps: Double
        get() = if (intervals.isEmpty()) 0.0 else 1e9 / intervals.average()

    val worstMs: Double
        get() = (intervals.maxOrNull() ?: 0L) / 1e6

    /** Frames that took longer than 1.5 refresh periods (a skipped vsync). */
    fun janks(refreshHz: Double): Int {
        val limit = 1.5e9 / refreshHz
        return intervals.count { it > limit }
    }

    fun clear() = intervals.clear()
}
