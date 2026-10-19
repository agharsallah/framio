package dev.framio.pomodoro.core.log

import dev.framio.pomodoro.core.model.SessionRecord
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The session log as JSON Lines: one finished session per line, appended and fsynced by the app. A year of
 * heavy use is about 4k lines. Reading tolerates what a power cut or a newer version leaves behind.
 */
public object SessionLog {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    /** What [decode] read: records in file order (duplicates by id collapsed to the last), and lines it skipped. */
    public data class Decoded(val records: List<SessionRecord>, val skipped: Int)

    /** One line, without the trailing newline. */
    public fun encode(record: SessionRecord): String = json.encodeToString(SessionRecord.serializer(), record)

    /**
     * Every readable line of [text]. Skips blank lines, a torn last line, and lines from a future format it
     * can't read. Unknown fields are ignored. If a session was appended twice (a crash between the log and the
     * snapshot write), the later line wins, at the position of the first.
     */
    public fun decode(text: String): Decoded {
        val byId = LinkedHashMap<String, SessionRecord>()
        var skipped = 0
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val record = try {
                json.decodeFromString(SessionRecord.serializer(), line)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            if (record == null) skipped++ else byId[record.id.value] = record
        }
        return Decoded(byId.values.toList(), skipped)
    }
}
