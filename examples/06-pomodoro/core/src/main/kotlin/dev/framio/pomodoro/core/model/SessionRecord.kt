package dev.framio.pomodoro.core.model

import dev.framio.pomodoro.core.time.Moment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration

/** How a focus block ended. Only [EndedEarly] and [Interrupted] don't count towards the daily target. */
@Serializable
public enum class Outcome {
    /** Wrap-up: the step got done. */
    @SerialName("done") Done,

    /** Wrap-up: part of it. */
    @SerialName("partly") Partly,

    /** Wrap-up: not yet. Still a full block of focus. */
    @SerialName("not_yet") NotYet,

    /** Ran to the end; the wrap-up was skipped. */
    @SerialName("unrated") Unrated,

    /** You ended it before the time was up. Never called "failed". */
    @SerialName("ended_early") EndedEarly,

    /** A reboot (or a lost clock) cut it off. */
    @SerialName("interrupted") Interrupted,
    ;

    /** Counts as a session for the daily target and the estimate comparison. */
    public val completed: Boolean get() = this != EndedEarly && this != Interrupted
}

/**
 * One line of the session log (JSON Lines). Durations are logical: in a debug time-scaled run they are
 * what the session pretended to be, not the seconds it really took.
 *
 * @param start when the block started; its wall time may be missing if the clock wasn't trusted yet
 * @param planned the focus length (null in Flow mode, which counts up)
 * @param focused time actually spent in focus (pauses excluded)
 * @param rating 1..5 from the wrap-up, if given
 */
@Serializable
public data class SessionRecord(
    @SerialName("id") val id: SessionId,
    @SerialName("mode") val mode: ModeId,
    @SerialName("modeName") val modeName: String? = null,
    @SerialName("task") val task: TaskId? = null,
    @SerialName("taskTitle") val taskTitle: String? = null,
    @SerialName("intention") val intention: String? = null,
    @SerialName("planned") val planned: Duration? = null,
    @SerialName("focused") val focused: Duration,
    @SerialName("outcome") val outcome: Outcome,
    @SerialName("rating") val rating: Int? = null,
    @SerialName("resumeNote") val resumeNote: String? = null,
    @SerialName("parked") val parkedCount: Int = 0,
    @SerialName("start") val start: Moment,
    /** The resume note's handwriting (InkCodec text), if it was written by hand. */
    @SerialName("resumeInk") val resumeInk: String? = null,
) {
    /** Start as wall-clock milliseconds, or null if it was never known. */
    val startWallMs: Long? get() = start.wallMs
}
