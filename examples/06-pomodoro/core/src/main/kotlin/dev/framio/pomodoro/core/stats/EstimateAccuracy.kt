package dev.framio.pomodoro.core.stats

import dev.framio.pomodoro.core.model.TaskId

/** One task's guess against what it took (planning fallacy, PLAN §3 principle 9). Built by `history.InsightsReport`. */
public data class TaskEstimate(val task: TaskId, val title: String, val estimate: Int, val actual: Int) {
    /** actual / estimate: above 1 means it took longer than you thought. */
    val ratio: Float get() = actual.toFloat() / estimate
}
