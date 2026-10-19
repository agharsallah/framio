package dev.framio.pomodoro.core.history

import dev.framio.pomodoro.core.notes.JournalCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/**
 * Where the cached buckets were computed: the byte lengths of `sessions.jsonl` and `history.jsonl` at the time. Both
 * files only grow, except on a journal compaction (which rewrites and shrinks it), so equal lengths mean "nothing new".
 */
@Serializable
public data class Checkpoint(
    @SerialName("sessions") val sessionsBytes: Long,
    @SerialName("journal") val journalBytes: Long,
)

/**
 * Insights' warm start: the day buckets as of [checkpoint], in [zone]. Insights shows these at once, then the history
 * repository reads the files in the background and replaces them if anything changed. Losing the file costs one
 * rebuild, nothing else.
 */
@Serializable
public data class StatsCache(
    @SerialName("v") val version: Int = VERSION,
    @SerialName("zone") val zone: String,
    @SerialName("checkpoint") val checkpoint: Checkpoint,
    @SerialName("days") val days: List<DayBucket>,
) {
    /** Usable as-is for files of [now] in time zone [zone]. A zone change re-buckets every day, so it invalidates. */
    public fun isFresh(now: Checkpoint, zone: String): Boolean = this.zone == zone && checkpoint == now

    public companion object {
        public const val VERSION: Int = 1

        public fun encode(cache: StatsCache): String = JournalCodec.json.encodeToString(serializer(), cache)

        /** Null for anything unreadable or from another cache version: the caller rebuilds. */
        public fun decode(text: String): StatsCache? = try {
            JournalCodec.json.decodeFromString(serializer(), text).takeIf { it.version == VERSION }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
