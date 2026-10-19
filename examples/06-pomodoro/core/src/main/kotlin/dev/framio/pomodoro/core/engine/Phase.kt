package dev.framio.pomodoro.core.engine

import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.SessionId
import dev.framio.pomodoro.core.rhythm.BreakKind
import dev.framio.pomodoro.core.time.Deadline
import dev.framio.pomodoro.core.time.Moment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * Where the focus loop is (PLAN §6.1). Moments and deadlines are real time; the logical lengths (what the
 * mode says, before a debug time scale) live on [ActiveSession] and in [Break.length]. Peek is UI-only.
 */
@Serializable
public sealed interface Phase {
    /** Home: pick a mode and a task. */
    @Serializable
    @SerialName("idle")
    public data object Idle : Phase

    /**
     * The pre-start ritual of [modeId]: intention, if-then, checklist, last resume note. [intention] is what Home
     * had chosen when the ritual opened (preselected there).
     */
    @Serializable
    @SerialName("ritual")
    public data class Ritual(
        @SerialName("mode") val modeId: ModeId,
        @SerialName("intention") val intention: String? = null,
    ) : Phase

    /**
     * Focusing. [deadline] is null in Flow mode (counts up). [startedAt] is the effective start: moved
     * forward by every pause, so `now - startedAt` is always the time spent focusing.
     */
    @Serializable
    @SerialName("focus")
    public data class Focus(
        @SerialName("session") val session: SessionId,
        @SerialName("deadline") val deadline: Deadline?,
        @SerialName("startedAt") val startedAt: Moment,
    ) : Phase

    /**
     * A paused focus block. [remaining] is the real time that was left (null in Flow), [elapsed] the focus
     * time so far. [capAt] is when the pause cap runs out (Deep: 10 min); [overCap] once it has, and the app
     * offers to end the session with a resume note.
     */
    @Serializable
    @SerialName("paused")
    public data class Paused(
        @SerialName("session") val session: SessionId,
        @SerialName("remaining") val remaining: Duration?,
        @SerialName("elapsed") val elapsed: Duration,
        @SerialName("since") val since: Moment,
        @SerialName("capAt") val capAt: Deadline? = null,
        @SerialName("overCap") val overCap: Boolean = false,
    ) : Phase

    /** Focus ended and the break waits for the wrap-up (rhythms without auto-break, such as Deep). */
    @Serializable
    @SerialName("wrapup")
    public data class WrapUp(
        @SerialName("session") val session: SessionId,
        @SerialName("next") val next: BreakKind,
        /** Logical length of the break that follows. */
        @SerialName("breakFor") val breakFor: Duration,
    ) : Phase

    /**
     * A break until [deadline]. [length] is logical (for the progress arc). [pendingWrapUp]: the block's
     * wrap-up hasn't been answered yet and shows as a card on the break screen.
     */
    @Serializable
    @SerialName("break")
    public data class Break(
        @SerialName("kind") val kind: BreakKind,
        @SerialName("deadline") val deadline: Deadline,
        @SerialName("length") val length: Duration,
        @SerialName("prompt") val prompt: BreakPrompt,
        @SerialName("pendingWrapUp") val pendingWrapUp: Boolean = false,
    ) : Phase

    /** "Ready?": the break is over; a tap starts the next block. */
    @Serializable
    @SerialName("ready")
    public data object Ready : Phase
}

/** The one thing the break card suggests (PLAN §3 principle 6). Long breaks also allow checking messages. */
@Serializable
public enum class BreakPrompt {
    @SerialName("move") Move,
    @SerialName("window") Window,
    @SerialName("water") Water,
    @SerialName("eyes") Eyes,
    @SerialName("messages") Messages,
    ;

    public companion object {
        private val SHORT = listOf(Move, Window, Water, Eyes)

        /** Short breaks rotate through the four prompts; a long break says messages can be checked now. */
        public fun forBreak(kind: BreakKind, cycle: Int): BreakPrompt =
            if (kind == BreakKind.Long) Messages else SHORT[(cycle - 1).mod(SHORT.size)]
    }
}
