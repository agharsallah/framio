package dev.framio.pomodoro.ui

import kotlin.math.ceil
import kotlin.time.Duration

/** Countdown text: "24:59" (seconds rounded up, so a block starts at "25:00" and ends at "0:00"), or "38 min". */
object Format {
    fun clock(left: Duration, seconds: Boolean): String {
        if (!seconds) return "${minutesLeft(left)} min"
        val total = ceil(left.inWholeMilliseconds.coerceAtLeast(0) / 1000.0).toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "$h:${pad(m)}:${pad(s)}" else "$m:${pad(s)}"
    }

    /** Whole minutes left, rounded up: "12 minutes left" while 11:01 shows. */
    fun minutesLeft(left: Duration): Int = ceil(left.inWholeMilliseconds.coerceAtLeast(0) / 60_000.0).toInt()

    private fun pad(n: Long) = if (n < 10) "0$n" else "$n"
}
