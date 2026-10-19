package dev.framio.pomodoro.core.engine

import dev.framio.pomodoro.core.time.Now
import kotlin.time.Duration

/**
 * What the timer face shows, derived from state and time on demand (the engine has no ticker).
 * All results are logical durations: in a debug time-scaled run, "25 min" counts down in 25 s.
 */
public object Timing {
    /** Time left in the current focus block or break; null in Flow, when nothing runs, or if unknown. */
    public fun left(s: FocusState, now: Now, config: EngineConfig = EngineConfig.Default): Duration? = when (val p = s.phase) {
        is Phase.Focus -> p.deadline?.let(now::until)
        is Phase.Paused -> p.remaining
        is Phase.Break -> now.until(p.deadline)
        else -> null
    }?.coerceAtLeast(Duration.ZERO)?.let(config::logical)

    /** Focus time so far in the current block (counts up in Flow); null outside focus. */
    public fun focused(s: FocusState, now: Now, config: EngineConfig = EngineConfig.Default): Duration? = when (val p = s.phase) {
        is Phase.Focus -> now.since(p.startedAt)
        is Phase.Paused -> p.elapsed
        else -> null
    }?.coerceAtLeast(Duration.ZERO)?.let(config::logical)

    /** 0 at the start of a block or break, 1 at its end; 0 when there is no end (Flow). */
    public fun progress(s: FocusState, now: Now, config: EngineConfig = EngineConfig.Default): Float {
        val total = when (val p = s.phase) {
            is Phase.Focus, is Phase.Paused -> s.session?.planned
            is Phase.Break -> p.length
            else -> null
        } ?: return 0f
        val left = left(s, now, config) ?: return 0f
        if (!total.isPositive()) return 1f
        return (1f - (left / total).toFloat()).coerceIn(0f, 1f)
    }
}
