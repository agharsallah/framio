package dev.framio.hub.core.timers

import dev.framio.hub.core.engine.Now
import dev.framio.hub.core.model.Deadline
import dev.framio.hub.core.model.Run
import dev.framio.hub.core.model.Timer

/**
 * Milliseconds until this deadline (negative once it has passed), or null when it can't be known:
 * same boot → elapsed time, which never jumps; after a reboot → wall clock, if both sides trust it.
 */
fun Deadline.remainingMs(now: Now): Long? = when {
    bootId == now.bootId -> elapsedMs - now.elapsedMs
    wallMs != null && now.wallMs != null -> wallMs - now.wallMs
    else -> null
}

fun Now.deadlineIn(ms: Long): Deadline = Deadline(bootId, elapsedMs + ms, wallMs?.plus(ms))

/**
 * The same instant, expressed in the current boot when possible and with its wall time filled in once the
 * clock is trusted, so it also survives the next reboot.
 */
fun Deadline.rebased(now: Now): Deadline {
    val remaining = remainingMs(now) ?: return this
    val wall = wallMs ?: now.wallMs?.plus(remaining)
    return Deadline(now.bootId, now.elapsedMs + remaining, wall)
}

/** True when this deadline can never be resolved: it was set before a reboot and before the clock was trusted. */
fun Deadline.isLost(now: Now) = bootId != now.bootId && wallMs == null

val Timer.isActive get() = run is Run.Running || run is Run.Paused || run is Run.Ringing

/** Remaining time for display: running counts down, paused holds, ringing and finished show 0. Null if unknown. */
fun Timer.remainingMs(now: Now): Long? = when (val r = run) {
    is Run.Running -> r.until.remainingMs(now)?.coerceAtLeast(0)
    is Run.Paused -> r.remainingMs
    is Run.Ringing, is Run.Done -> 0
    Run.Interrupted -> null
}

/** 0 at the start, 1 when done. */
fun Timer.progress(now: Now): Float {
    val remaining = remainingMs(now) ?: return 0f
    if (totalMs <= 0) return 1f
    return (1f - remaining.toFloat() / totalMs).coerceIn(0f, 1f)
}

object Durations {
    private val PARTS = Regex("""^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$""")

    /** "3m", "90s", "1h30m", "1h 5m 10s"; a bare number is minutes. Null when it isn't a positive duration. */
    fun parse(text: String): Long? {
        val t = text.lowercase().replace(" ", "")
        if (t.isEmpty()) return null
        val total = t.toLongOrNull()?.let { it * 60_000 } ?: PARTS.matchEntire(t)?.groupValues?.let { g ->
            (g[1].toLongOrNull() ?: 0) * 3_600_000 + (g[2].toLongOrNull() ?: 0) * 60_000 + (g[3].toLongOrNull() ?: 0) * 1_000
        }
        return total?.takeIf { it > 0 }
    }

    /** "4:32", "12:00", "1:02:03". Rounds up, so a timer shows 0:01 until it really ends. */
    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 999) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "$h:${pad(m)}:${pad(sec)}" else "$m:${pad(sec)}"
    }

    private fun pad(v: Long) = if (v < 10) "0$v" else v.toString()
}
