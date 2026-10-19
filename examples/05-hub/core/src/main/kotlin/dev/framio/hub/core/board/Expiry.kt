package dev.framio.hub.core.board

import dev.framio.hub.core.model.Keep
import dev.framio.hub.core.model.Note
import dev.framio.hub.core.time.LocalTimes
import java.time.LocalTime
import java.time.ZoneId

/** When notes go away by themselves. */
object Expiry {
    /** "Today" lasts until 04:00 the next morning, so a note written at 23:00 survives the evening. */
    val END_OF_DAY: LocalTime = LocalTime.of(4, 0)
    const val THREE_DAYS_MS = 3 * 24 * 3_600_000L

    fun expiresAt(keep: Keep, createdWallMs: Long, zone: ZoneId): Long? = when (keep) {
        Keep.Today -> LocalTimes.nextOccurrence(END_OF_DAY, createdWallMs, zone)
        Keep.ThreeDays -> createdWallMs + THREE_DAYS_MS
        Keep.Pinned -> null
    }

    fun isExpired(note: Note, wallMs: Long): Boolean = note.expiresWallMs?.let { wallMs >= it } ?: false
}
