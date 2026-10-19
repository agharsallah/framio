package dev.framio.pomodoro.core.notes

import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.SessionId
import dev.framio.pomodoro.core.model.TaskId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A correction to a logged session, made in History. Null fields are unchanged; [rating] 0 clears the rating. Later
 * amends merge over earlier ones. The session log itself is never rewritten.
 */
@Serializable
public data class SessionAmend(
    @SerialName("s") val session: SessionId,
    @SerialName("outcome") val outcome: Outcome? = null,
    @SerialName("rating") val rating: Int? = null,
    @SerialName("task") val task: TaskId? = null,
    @SerialName("taskTitle") val taskTitle: String? = null,
) {
    public fun mergedWith(later: SessionAmend): SessionAmend = SessionAmend(
        session,
        later.outcome ?: outcome,
        later.rating ?: rating,
        later.task ?: task,
        later.taskTitle ?: taskTitle,
    )
}

/**
 * One line of the notes journal (`history.jsonl`, next to `sessions.jsonl`). Append-only like the session log: an edit
 * is a new [Put] with the same id, a delete is a [Delete] tombstone, and undo writes the inverse op. Nothing ever asks
 * "are you sure?".
 */
public sealed interface JournalOp {
    /** The session this op touches. */
    public val session: SessionId

    public data class Put(val entry: Entry) : JournalOp {
        override val session: SessionId get() = entry.session
    }

    public data class Delete(val id: EntryId, override val session: SessionId) : JournalOp

    public data class Amend(val amend: SessionAmend) : JournalOp {
        override val session: SessionId get() = amend.session
    }

    /** Hides a whole session (and its entries) from History, Insights and exports. [Restore] undoes it. */
    public data class Drop(override val session: SessionId) : JournalOp

    public data class Restore(override val session: SessionId) : JournalOp
}

/**
 * Encodes and decodes journal lines. Each line carries `"v"`; a line from a newer version (or an op this version
 * doesn't know) is kept verbatim, so compaction never destroys data a newer app wrote.
 */
public object JournalCodec {
    public const val VERSION: Int = 1

    internal val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        coerceInputValues = true
    }

    /** What one line turned out to be. */
    public sealed interface Line {
        public data class Op(val op: JournalOp) : Line

        /** Valid JSON from a newer version or with an unknown op: keep it, don't interpret it. */
        public data class Unknown(val raw: String) : Line

        /** Not a journal line (a torn write after a power cut, or garbage). */
        public data object Garbage : Line
    }

    public fun encode(op: JournalOp): String {
        val obj = buildJsonObject {
            put("v", JsonPrimitive(VERSION))
            when (op) {
                is JournalOp.Put -> {
                    put("op", JsonPrimitive("put"))
                    put("e", json.encodeToJsonElement(Entry.serializer(), op.entry))
                }
                is JournalOp.Delete -> {
                    put("op", JsonPrimitive("del"))
                    put("id", JsonPrimitive(op.id.value))
                    put("s", JsonPrimitive(op.session.value))
                }
                is JournalOp.Amend -> {
                    put("op", JsonPrimitive("amend"))
                    json.encodeToJsonElement(SessionAmend.serializer(), op.amend).jsonObject.forEach { (k, v) -> put(k, v) }
                }
                is JournalOp.Drop -> {
                    put("op", JsonPrimitive("drop"))
                    put("s", JsonPrimitive(op.session.value))
                }
                is JournalOp.Restore -> {
                    put("op", JsonPrimitive("restore"))
                    put("s", JsonPrimitive(op.session.value))
                }
            }
        }
        return obj.toString()
    }

    public fun decode(line: String): Line {
        val obj = try {
            json.parseToJsonElement(line) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return Line.Garbage
        val v = (obj["v"] as? JsonPrimitive)?.intOrNull ?: return Line.Garbage
        if (v > VERSION) return Line.Unknown(line)
        val op = (obj["op"] as? JsonPrimitive)?.contentOrNull ?: return Line.Garbage
        return try {
            when (op) {
                "put" -> Line.Op(JournalOp.Put(json.decodeFromJsonElement(Entry.serializer(), obj.getValue("e"))))
                "del" -> Line.Op(JournalOp.Delete(EntryId(obj.string("id")), SessionId(obj.string("s"))))
                "amend" -> Line.Op(JournalOp.Amend(json.decodeFromJsonElement(SessionAmend.serializer(), obj)))
                "drop" -> Line.Op(JournalOp.Drop(SessionId(obj.string("s"))))
                "restore" -> Line.Op(JournalOp.Restore(SessionId(obj.string("s"))))
                else -> Line.Unknown(line)
            }
        } catch (_: SerializationException) {
            // A known op whose entry type is from the future reads as unknown, not garbage: keep it.
            if (op == "put") Line.Unknown(line) else Line.Garbage
        } catch (_: IllegalArgumentException) {
            Line.Garbage
        } catch (_: NoSuchElementException) {
            Line.Garbage
        }
    }

    private fun JsonObject.string(key: String): String =
        getValue(key).jsonPrimitive.contentOrNull ?: throw IllegalArgumentException("no $key")

    /** Reads a whole journal file. Blank lines are ignored; garbage lines (a torn last line) are counted. */
    public fun read(text: String): Journal {
        val journal = Journal()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            when (val l = decode(line)) {
                is Line.Op -> journal.apply(l.op)
                is Line.Unknown -> journal.keepUnknown(l.raw)
                Line.Garbage -> journal.skipped++
            }
        }
        return journal
    }
}

/**
 * The notes journal folded into its current state. Single writer: the app's history repository owns one instance
 * and applies each op after appending it to the file. Not thread-safe; read it from the writer or copy out.
 */
public class Journal {
    private val entries = LinkedHashMap<EntryId, Entry>()
    private val tombstones = HashMap<EntryId, SessionId>()
    private val amends = HashMap<SessionId, SessionAmend>()
    private val dropped = HashSet<SessionId>()
    private val unknown = ArrayList<String>()

    /** Lines applied or kept. */
    public var lines: Int = 0
        private set

    /** Lines whose effect a later line replaced: what compaction would save. */
    public var superseded: Int = 0
        private set

    /** Lines that couldn't be read at all. */
    public var skipped: Int = 0
        internal set

    public val liveEntries: Collection<Entry> get() = entries.values

    public fun entry(id: EntryId): Entry? = entries[id]

    public fun isDeleted(id: EntryId): Boolean = id in tombstones

    public fun amendOf(session: SessionId): SessionAmend? = amends[session]

    public fun isDropped(session: SessionId): Boolean = session in dropped

    public val unknownLines: List<String> get() = unknown

    /** Applies [op]; returns the session it changed. */
    public fun apply(op: JournalOp): SessionId {
        lines++
        when (op) {
            is JournalOp.Put -> {
                val id = op.entry.id
                if (entries.put(id, op.entry) != null) superseded++
                if (tombstones.remove(id) != null) superseded++
            }
            is JournalOp.Delete -> {
                if (entries.remove(op.id) != null) superseded++
                if (tombstones.put(op.id, op.session) != null) superseded++
            }
            is JournalOp.Amend -> {
                val old = amends[op.session]
                if (old != null) superseded++
                amends[op.session] = old?.mergedWith(op.amend) ?: op.amend
            }
            is JournalOp.Drop -> if (!dropped.add(op.session)) superseded++
            is JournalOp.Restore -> {
                if (dropped.remove(op.session)) superseded++
                superseded++ // a restore line is never needed after compaction
            }
        }
        return op.session
    }

    internal fun keepUnknown(raw: String) {
        lines++
        unknown += raw
    }

    /** Worth rewriting once a quarter of the file (and at least [MIN_SUPERSEDED] lines) is dead weight. */
    public val shouldCompact: Boolean get() = superseded >= MIN_SUPERSEDED && superseded * 4 >= lines

    /**
     * The smallest journal with the same meaning: unknown lines first (kept verbatim), then live entries, merged
     * amends, drops, and tombstones. Tombstones stay (a few bytes each): an entry synthesized from the session log
     * (an old resume note) would otherwise come back. Write it to a temp file and rename it over the journal.
     */
    public fun compacted(): List<String> = buildList {
        addAll(unknown)
        entries.values.forEach { add(JournalCodec.encode(JournalOp.Put(it))) }
        amends.values.forEach { add(JournalCodec.encode(JournalOp.Amend(it))) }
        dropped.forEach { add(JournalCodec.encode(JournalOp.Drop(it))) }
        tombstones.forEach { (id, s) -> add(JournalCodec.encode(JournalOp.Delete(id, s))) }
    }

    public companion object {
        public const val MIN_SUPERSEDED: Int = 500
    }
}
