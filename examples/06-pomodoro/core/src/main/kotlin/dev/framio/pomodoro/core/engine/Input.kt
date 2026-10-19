package dev.framio.pomodoro.core.engine

import dev.framio.pomodoro.core.model.Display
import dev.framio.pomodoro.core.model.Mode
import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.core.model.TaskId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Everything that can happen to the engine: taps, adb commands, the scheduler, and the start-up load. */
public sealed interface Input {
    /** Start-up: the stored snapshot (null if none or unreadable) and settings. Resolves deadlines that passed while dead. */
    public data class Restore(val snapshot: Snapshot?, val settings: Settings) : Input

    /** The wall clock just became trusted: fill in wall times that were unknown until now. */
    public data object TimeTrusted : Input

    /** Sent by the scheduler when the next wake is due. Does nothing if nothing is due (yet). */
    public data object DeadlineReached : Input

    // Home and ritual

    /** In Idle or Ritual. */
    public data class SelectMode(val id: ModeId) : Input

    /** From Idle or Ready. [intention] (Home's chosen task) is preselected on the ritual screen. */
    public data class OpenRitual(val intention: String? = null) : Input

    /** Whether [mode]'s ritual screen is skipped from now on. Turning it on needs one run through it first. */
    public data class SetRitualSkip(val mode: ModeId, val skip: Boolean) : Input

    /** Back from the ritual to Idle (or Ready, mid-run). */
    public data object CancelRitual : Input

    /**
     * Starts a focus block, from Idle, Ritual or Ready. A [taskId] picks a recent task; otherwise a non-blank
     * [intention] finds or creates the task of that title. [focusOverride] replaces a fixed rhythm's focus
     * length once (adb `--ei min 1`). [checked] comes from the ritual screen: the ticked checklist ids, which
     * are remembered (with the if-then line) for the next time; null when Begin didn't come from the ritual.
     * [display] switches a Calm or Blackout mode's screen for this run (the ritual's Calm/Blackout toggle); a
     * Full mode stays Full.
     */
    public data class Begin(
        val intention: String? = null,
        val ifThen: String? = null,
        val taskId: TaskId? = null,
        val focusOverride: Duration? = null,
        val checked: Set<String>? = null,
        val display: Display? = null,
    ) : Input

    // During focus

    public data object Pause : Input

    public data object Resume : Input

    /**
     * Ends the block early (Focus or Paused); logged as ended early, never as failed. The resume note is words,
     * handwriting ([resumeInk], InkCodec text), both, or neither.
     */
    public data class EndEarly(val resumeNote: String? = null, val resumeInk: String? = null) : Input

    /** Flow: stop at a natural point; the break follows from the tiers. */
    public data object Complete : Input

    /** Fixed rhythms: a few more minutes. */
    public data class Extend(val by: Duration = 5.minutes) : Input

    /** A stray thought, as ink from the parking-lot pad (InkCodec). In Focus or Paused. */
    public data class Park(val ink: String) : Input

    /**
     * Clears parked thought [index] (null: all of them): the current block's while it has one (Focus to
     * Break), otherwise the ones kept from earlier blocks. Thoughts not cleared are kept when the block ends.
     */
    public data class ClearParked(val index: Int? = null) : Input

    // After focus

    /** The wrap-up; every part optional (a null [outcome] logs the block as unrated). [rating] is 1..5. */
    public data class WrapUp(
        val outcome: Outcome? = null,
        val rating: Int? = null,
        val resumeNote: String? = null,
        val resumeInk: String? = null,
    ) : Input

    /** Ends the break (or skips wrap-up and break) now. */
    public data object SkipBreak : Input

    /** A break a few minutes longer ("+5 min" on the break screen). */
    public data class ExtendBreak(val by: Duration = 5.minutes) : Input

    /** "Ready?" → the next block with the same mode and intention. */
    public data object StartNext : Input

    /** Leaves the run from Ritual, Wrap-up, Break or Ready. Logs an unanswered wrap-up as unrated. */
    public data object Home : Input

    public data object DismissNotice : Input

    // Tasks

    public data class AddTask(val title: String, val estimateSessions: Int? = null) : Input

    /** 1..99 sessions, or null to clear. */
    public data class SetEstimate(val taskId: TaskId, val sessions: Int?) : Input

    public data class RemoveTask(val taskId: TaskId) : Input

    // Settings and modes

    /** Replaces the settings (normalized: built-in presets can't change). A running session keeps its mode. */
    public data class ChangeSettings(val settings: Settings) : Input

    /**
     * Adds or replaces one of your own modes. A changed display forgets the ritual's remembered Calm/Blackout choice
     * for it, so the mode's new default shows.
     */
    public data class SaveMode(val mode: Mode) : Input

    public data class DeleteMode(val id: ModeId) : Input

    /** Moves a mode [by] places in the carousel order (negative = towards the front); clamped at the ends. */
    public data class MoveMode(val id: ModeId, val by: Int) : Input
}
