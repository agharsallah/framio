package dev.framio.pomodoro.core.engine

import dev.framio.pomodoro.core.model.CueStyle
import dev.framio.pomodoro.core.model.ParkedThought
import dev.framio.pomodoro.core.model.SessionId
import dev.framio.pomodoro.core.model.SessionRecord
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.core.model.Soundscape
import dev.framio.pomodoro.core.time.Deadline
import kotlin.time.Duration

/** What the app must do after an input. Handled in order on the engine's thread; handlers must not block. */
public sealed interface Effect {
    /** The engine will next wake at [at] (it schedules itself; this is for logging and the service). */
    public data class ScheduleWake(val at: Deadline) : Effect

    /** Nothing is scheduled any more. */
    public data object CancelWake : Effect

    public data class PlayCue(val kind: CueKind, val style: CueStyle) : Effect

    /**
     * Brightness for the new phase. [ramp] > 0 fades up slowly (Deep's sunrise at the end of a block).
     * Blackout means backlight off; it must always be restored on stop, Exit and crash.
     */
    public data class SetDisplay(val level: DisplayLevel, val ramp: Duration = Duration.ZERO) : Effect

    /** A session is active: keep the foreground service up. */
    public data object StartService : Effect

    public data object StopService : Effect

    /** Play (or, with null, stop) a soundscape. */
    public data class SetSoundscape(val soundscape: Soundscape?) : Effect

    /** Append one line to the session log (fsync). Duplicates by id are possible after a crash; readers keep the last. */
    public data class AppendLog(val record: SessionRecord) : Effect

    /** Write the engine snapshot (conflated: only the latest matters). */
    public data class Persist(val snapshot: Snapshot) : Effect

    public data class SaveSettings(val settings: Settings) : Effect

    /** A focus block or break ended: show the app if it's in the background. */
    public data object BringToFront : Effect

    /**
     * A thought was parked in [session], [at] logical focus time into it: for the notes journal (DESIGN-history §6),
     * which keeps every parked thought for History. The engine's own lot is unchanged by it.
     */
    public data class ThoughtParked(val session: SessionId, val thought: ParkedThought, val at: Duration) : Effect

    /** Parked thoughts were dealt with (cleared at the wrap-up, the break or the ritual): the journal marks them resolved. */
    public data class ThoughtsCleared(val thoughts: List<ParkedThought>) : Effect
}

/** [Nudge] is a one-note cue the engine doesn't play today (Flow's nudge is visual only, M4); kept for the mode editor. */
public enum class CueKind { FocusEnd, BreakEnd, Nudge, PauseCap }

public enum class DisplayLevel {
    /** Normal brightness. */
    Normal,

    /** Very dim (window brightness). */
    Calm,

    /** Backlight off. */
    Blackout,

    /** Dimmed during breaks, so the frame doesn't become the thing you look at. */
    Dim,
}

/** The result of one input. [rejected] says why an input did nothing; the state is then unchanged. */
public data class Next(
    val state: FocusState,
    val effects: List<Effect> = emptyList(),
    val rejected: String? = null,
)
