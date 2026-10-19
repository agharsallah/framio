package dev.framio.pomodoro.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Something you work on. The Home chip row orders tasks by how recently and how often you used them.
 *
 * @param estimateSessions your guess of how many focus blocks it takes; compared with the actual count
 * @param lastUsedWallMs wall time of the last use, null if the clock wasn't trusted yet
 */
@Serializable
public data class Task(
    @SerialName("id") val id: TaskId,
    @SerialName("title") val title: String,
    @SerialName("estimate") val estimateSessions: Int? = null,
    @SerialName("lastUsed") val lastUsedWallMs: Long? = null,
    @SerialName("uses") val useCount: Int = 0,
) {
    public companion object {
        /** Older, rarely used tasks drop off the end. */
        public const val MAX_TASKS: Int = 40
        public const val MAX_TITLE: Int = 80
    }
}
