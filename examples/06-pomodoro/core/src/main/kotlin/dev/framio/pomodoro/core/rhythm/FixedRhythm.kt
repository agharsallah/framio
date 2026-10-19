package dev.framio.pomodoro.core.rhythm

import dev.framio.pomodoro.core.model.Rhythm

/** Fixed blocks with a long break every N. Pure. */
public object FixedRhythm {
    /** True when the break after block [cycle] (1-based) is a long one. */
    public fun isLong(rhythm: Rhythm.Fixed, cycle: Int): Boolean =
        rhythm.longEvery > 0 && cycle > 0 && cycle % rhythm.longEvery == 0

    public fun breakAfter(rhythm: Rhythm.Fixed, cycle: Int): BreakPlan =
        if (isLong(rhythm, cycle)) BreakPlan(BreakKind.Long, rhythm.long) else BreakPlan(BreakKind.Short, rhythm.short)
}
