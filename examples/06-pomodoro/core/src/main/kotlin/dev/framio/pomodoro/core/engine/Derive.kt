package dev.framio.pomodoro.core.engine

import dev.framio.pomodoro.core.model.Display
import dev.framio.pomodoro.core.model.Rhythm
import dev.framio.pomodoro.core.model.Soundscape
import dev.framio.pomodoro.core.time.Deadline
import dev.framio.pomodoro.core.time.Now
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The effects that follow from a state alone: what to wake for, whether the service runs, how bright the
 * screen is, which soundscape plays, and what to write. [reduce] adds the ones whose value changed; after a
 * Restore (no "before") it adds all of them, so the app syncs to whatever was restored.
 */
public object Derive {
    /** Deep's sunrise: the backlight rises over 10 s at the end of a Calm or Blackout block. */
    public val SUNRISE: Duration = 10.seconds

    /** When the engine must next look at the time: a deadline, a pause cap, or Flow's nudge. Null = nothing. */
    public fun wake(s: FocusState, config: EngineConfig = EngineConfig.Default): Deadline? = when (val p = s.phase) {
        is Phase.Focus -> p.deadline ?: run {
            val rhythm = s.mode.rhythm
            val nudge = (rhythm as? Rhythm.Flow)?.nudgeAfter
            if (nudge != null && s.session?.nudged == false) p.startedAt + config.real(nudge) else null
        }
        is Phase.Paused -> p.capAt?.takeIf { !p.overCap }
        is Phase.Break -> p.deadline
        else -> null
    }

    /** The foreground service runs from Begin until Home. */
    public fun serviceActive(s: FocusState): Boolean = when (s.phase) {
        is Phase.Focus, is Phase.Paused, is Phase.WrapUp, is Phase.Break, Phase.Ready -> true
        Phase.Idle, is Phase.Ritual -> false
    }

    public fun display(s: FocusState): DisplayLevel = when (s.phase) {
        is Phase.Focus -> when (s.mode.environment.display) {
            Display.Full -> DisplayLevel.Normal
            Display.Calm -> DisplayLevel.Calm
            Display.Blackout -> DisplayLevel.Blackout
        }
        is Phase.Break -> DisplayLevel.Dim
        else -> DisplayLevel.Normal
    }

    public fun soundscape(s: FocusState): Soundscape? = if (s.phase is Phase.Focus) s.mode.environment.soundscape else null

    internal fun effects(before: FocusState?, after: FocusState, now: Now, config: EngineConfig): List<Effect> {
        val out = ArrayList<Effect>(4)
        val wake = wake(after, config)
        if (before == null || wake != wake(before, config)) out += wake?.let(Effect::ScheduleWake) ?: Effect.CancelWake
        val active = serviceActive(after)
        if (before == null || active != serviceActive(before)) out += if (active) Effect.StartService else Effect.StopService
        val level = display(after)
        val was = before?.let(::display)
        if (was != level) {
            // Sunrise only when a dark block reaches its end; pausing or ending it yourself lights up at once
            val dark = was == DisplayLevel.Calm || was == DisplayLevel.Blackout
            val ended = after.phase is Phase.WrapUp || after.phase is Phase.Break
            val sunrise = dark && ended && before.mode.environment.sunrise
            out += Effect.SetDisplay(level, if (sunrise) SUNRISE else Duration.ZERO)
        }
        val sound = soundscape(after)
        if (before == null || sound != soundscape(before)) out += Effect.SetSoundscape(sound)
        if (before != null && after.snapshot() != before.snapshot()) out += Effect.Persist(after.snapshot(savedAt = now.moment))
        if (before != null && after.settings != before.settings) out += Effect.SaveSettings(after.settings)
        return out
    }
}
