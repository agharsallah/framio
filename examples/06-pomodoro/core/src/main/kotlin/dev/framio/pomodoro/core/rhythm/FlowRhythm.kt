package dev.framio.pomodoro.core.rhythm

import dev.framio.pomodoro.core.model.Rhythm
import dev.framio.pomodoro.core.model.SmitsTiers
import kotlin.time.Duration

/** Flowtime: the break follows from how long you focused. Pure. */
public object FlowRhythm {
    /**
     * The rest earned by [focused]: the first tier whose upper bound (inclusive) covers it. With the Smits
     * tiers, 25:00 → 5 min, 25:01 → 8, 50:00 → 8, 50:01 → 10.
     */
    public fun breakFor(rhythm: Rhythm.Flow, focused: Duration): Duration {
        val tiers = rhythm.tiers.ifEmpty { SmitsTiers }
        return (tiers.firstOrNull { it.upTo == null || focused <= it.upTo } ?: tiers.last()).rest
    }

    /** True once [focused] has reached the nudge ("a real break helps"); never when the nudge is off. */
    public fun nudgeDue(rhythm: Rhythm.Flow, focused: Duration): Boolean =
        rhythm.nudgeAfter?.let { focused >= it } ?: false
}
