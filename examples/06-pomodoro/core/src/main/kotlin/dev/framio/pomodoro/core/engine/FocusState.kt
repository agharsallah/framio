package dev.framio.pomodoro.core.engine

import dev.framio.pomodoro.core.model.Display
import dev.framio.pomodoro.core.model.LocalDateAsString
import dev.framio.pomodoro.core.model.Mode
import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.ParkedThought
import dev.framio.pomodoro.core.model.SessionId
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.core.model.Task
import dev.framio.pomodoro.core.model.TaskId
import dev.framio.pomodoro.core.time.Moment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.time.Duration

/** What you said you'd work on. */
@Serializable
public data class Intent(
    @SerialName("intention") val intention: String? = null,
    @SerialName("ifThen") val ifThen: String? = null,
    @SerialName("task") val task: TaskId? = null,
)

/**
 * A run of focus blocks with one mode, from the first Begin until Home. [mode] is a copy taken at Begin, so
 * editing or deleting the mode never disturbs a running session. [cycle] counts finished blocks (long breaks).
 */
@Serializable
public data class Run(
    @SerialName("mode") val mode: Mode,
    @SerialName("intent") val intent: Intent,
    @SerialName("cycle") val cycle: Int = 0,
)

/**
 * The current focus block, from Begin until it is logged (after the wrap-up, or when the break moves on).
 * [planned] and [focused] are logical durations (before any debug time scale).
 */
@Serializable
public data class ActiveSession(
    @SerialName("id") val id: SessionId,
    @SerialName("start") val start: Moment,
    @SerialName("planned") val planned: Duration?,
    @SerialName("focused") val focused: Duration? = null,
    @SerialName("parked") val parked: List<ParkedThought> = emptyList(),
    @SerialName("nudged") val nudged: Boolean = false,
    @SerialName("logged") val logged: Boolean = false,
)

/**
 * What a mode's ritual remembers: the boxes ticked, the if-then line and the screen (Calm or Blackout) of its last run,
 * how many runs it has had,
 * and whether you chose to skip its screen ([skip], allowed after the first run).
 */
@Serializable
public data class RitualMemory(
    @SerialName("mode") val mode: ModeId,
    @SerialName("ticks") val ticks: List<String> = emptyList(),
    @SerialName("ifThen") val ifThen: String? = null,
    @SerialName("runs") val runs: Int = 0,
    @SerialName("skip") val skip: Boolean = false,
    /** The Calm/Blackout choice of the last run; null = the mode's own. */
    @SerialName("display") val display: Display? = null,
)

/** Something the next screen should mention once. */
@Serializable
public enum class Notice {
    /** A reboot cut the last session off; it was logged as interrupted. */
    @SerialName("interrupted") Interrupted,
}

/**
 * What survives a process kill: everything in [FocusState] except the settings (stored on their own).
 * [savedAt] is when it was written, which bounds an interrupted session's length.
 */
@Serializable
public data class Snapshot(
    @SerialName("phase") val phase: Phase = Phase.Idle,
    @SerialName("run") val run: Run? = null,
    @SerialName("session") val session: ActiveSession? = null,
    @Serializable(with = LocalDateAsString::class)
    @SerialName("today") val today: LocalDate? = null,
    @SerialName("completedToday") val completedToday: Int = 0,
    @SerialName("lastResumeNote") val lastResumeNote: String? = null,
    @SerialName("tasks") val tasks: List<Task> = emptyList(),
    @SerialName("notice") val notice: Notice? = null,
    @SerialName("savedAt") val savedAt: Moment? = null,
    @SerialName("rituals") val rituals: List<RitualMemory> = emptyList(),
    @SerialName("parkedLot") val parkedLot: List<ParkedThought> = emptyList(),
    @SerialName("lastResumeInk") val lastResumeInk: String? = null,
)

/**
 * Everything the engine owns. [loaded] is false until [Input.Restore]; the UI waits for it.
 *
 * @param today the local date [completedToday] counts for; null while the clock isn't trusted
 * @param lastResumeNote "Next step when you're back?", shown when the next session starts
 * @param tasks recent tasks, most recently used first
 * @param rituals per mode: the last ritual's ticks and if-then line, and whether its screen is skipped
 * @param parkedLot parked thoughts kept from finished blocks (the current block's are on [session])
 */
public data class FocusState(
    val loaded: Boolean = false,
    val settings: Settings = Settings(),
    val phase: Phase = Phase.Idle,
    val run: Run? = null,
    val session: ActiveSession? = null,
    val today: LocalDate? = null,
    val completedToday: Int = 0,
    val lastResumeNote: String? = null,
    val tasks: List<Task> = emptyList(),
    val notice: Notice? = null,
    val rituals: List<RitualMemory> = emptyList(),
    val parkedLot: List<ParkedThought> = emptyList(),
    /** The last resume note's handwriting (InkCodec text), with or instead of [lastResumeNote]. */
    val lastResumeInk: String? = null,
) {
    /** The mode of the running session, else the selected one. */
    val mode: Mode get() = run?.mode ?: settings.selected

    public fun task(id: TaskId?): Task? = id?.let { t -> tasks.firstOrNull { it.id == t } }

    public fun ritual(mode: ModeId): RitualMemory? = rituals.firstOrNull { it.mode == mode }

    /** Whether Begin on Home skips [mode]'s ritual screen: it has one, it may be skipped, and you chose to. */
    public fun skipsRitual(mode: Mode): Boolean =
        mode.ritual.hasScreen && mode.ritual.skippable && ritual(mode.id)?.let { it.skip && it.runs > 0 } == true

    public fun snapshot(savedAt: Moment? = null): Snapshot =
        Snapshot(phase, run, session, today, completedToday, lastResumeNote, tasks, notice, savedAt, rituals, parkedLot, lastResumeInk)

    public companion object {
        public fun of(snapshot: Snapshot, settings: Settings): FocusState = FocusState(
            loaded = true,
            settings = settings,
            phase = snapshot.phase,
            run = snapshot.run,
            session = snapshot.session,
            today = snapshot.today,
            completedToday = snapshot.completedToday,
            lastResumeNote = snapshot.lastResumeNote,
            tasks = snapshot.tasks,
            notice = snapshot.notice,
            rituals = snapshot.rituals,
            parkedLot = snapshot.parkedLot,
            lastResumeInk = snapshot.lastResumeInk,
        )
    }
}
