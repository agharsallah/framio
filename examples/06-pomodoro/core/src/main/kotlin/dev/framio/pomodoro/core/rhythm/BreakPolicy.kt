package dev.framio.pomodoro.core.rhythm

import dev.framio.pomodoro.core.model.Rhythm
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration

@Serializable
public enum class BreakKind {
    @SerialName("short") Short,
    @SerialName("long") Long,
}

/** The break that follows a focus block. */
public data class BreakPlan(val kind: BreakKind, val length: Duration)

/** What follows a focus block, for either rhythm. Pure. */
public object BreakPolicy {
    /**
     * @param cycle focus blocks finished in this run, including the one that just ended (1 for the first)
     * @param focused how long that block's focus really lasted (Flow's break depends on it)
     */
    public fun after(rhythm: Rhythm, cycle: Int, focused: Duration): BreakPlan = when (rhythm) {
        is Rhythm.Fixed -> FixedRhythm.breakAfter(rhythm, cycle)
        is Rhythm.Flow -> BreakPlan(BreakKind.Short, FlowRhythm.breakFor(rhythm, focused))
    }

    /** When a break ends: start the next block by itself, or wait on "Ready?". */
    public fun autoFocus(rhythm: Rhythm): Boolean = rhythm is Rhythm.Fixed && rhythm.autoFocus
}
