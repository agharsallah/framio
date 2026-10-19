package dev.framio.pomodoro.core.notes

import dev.framio.pomodoro.core.model.SessionId
import dev.framio.pomodoro.core.time.Moment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Duration

/** One note or reason. Unique for good; an edit re-uses it (the later journal line wins). */
@Serializable
@JvmInline
public value class EntryId(public val value: String) {
    public companion object {
        /** The id of the resume note an older log kept inside its session record, so an edit can override it. */
        public fun legacyResume(session: SessionId): EntryId = EntryId("rn:${session.value}")

        /**
         * The id of a parking-lot thought mirrored from the engine (`ParkedThought.at` is unique: one boot, one
         * elapsed millisecond), so parking it and later clearing it map to the same entry without a lookup.
         */
        public fun parked(at: Moment): EntryId = EntryId("pk:${at.bootId}:${at.elapsedMs}")
    }
}

/**
 * Words, handwriting, or both. [ink] is InkCodec text (the parking lot's format). Either may be missing; a body with
 * neither is empty and is never stored.
 */
@Serializable
public data class NoteBody(
    @SerialName("text") val text: String? = null,
    @SerialName("ink") val ink: String? = null,
) {
    val isEmpty: Boolean get() = text.isNullOrBlank() && ink.isNullOrEmpty()

    /** Trimmed, length-capped, and null when nothing is left. */
    public fun cleaned(): NoteBody? {
        val t = text?.trim()?.take(MAX_TEXT)?.takeIf { it.isNotEmpty() }
        val i = ink?.takeIf { it.isNotEmpty() }
        return if (t == null && i == null) null else NoteBody(t, i)
    }

    public companion object {
        public const val MAX_TEXT: Int = 500
        public fun text(s: String): NoteBody = NoteBody(text = s)
        public fun ink(s: String): NoteBody = NoteBody(ink = s)
    }
}

/**
 * Something recorded about one session. Many per session, in any mix.
 *
 * [at] is the logical focus time into the session when it happened (pauses excluded, the same clock as
 * `SessionRecord.focused`), so it places the entry on the session's own timeline. It is null for things recalled
 * afterwards (the wrap-up, an edit in History), which therefore have no hour of day. [wallMs] is when the entry was
 * written, if the clock was trusted.
 */
@Serializable
public sealed interface Entry {
    public val id: EntryId
    public val session: SessionId
    public val at: Duration?
    public val wallMs: Long?

    /** Something pulled you away. One distraction can have several reasons. */
    @Serializable
    @SerialName("distraction")
    public data class Distraction(
        @SerialName("id") override val id: EntryId,
        @SerialName("s") override val session: SessionId,
        @SerialName("at") override val at: Duration? = null,
        @SerialName("w") override val wallMs: Long? = null,
        @SerialName("tags") val tags: List<TagId> = emptyList(),
        @SerialName("note") val note: NoteBody? = null,
    ) : Entry

    /** Why a session ended before its time. Only for sessions ended early; several reasons allowed. */
    @Serializable
    @SerialName("end")
    public data class EndReason(
        @SerialName("id") override val id: EntryId,
        @SerialName("s") override val session: SessionId,
        @SerialName("at") override val at: Duration? = null,
        @SerialName("w") override val wallMs: Long? = null,
        @SerialName("tags") val tags: List<TagId> = emptyList(),
    ) : Entry

    /** A free note, during or after the session. */
    @Serializable
    @SerialName("note")
    public data class Note(
        @SerialName("id") override val id: EntryId,
        @SerialName("s") override val session: SessionId,
        @SerialName("at") override val at: Duration? = null,
        @SerialName("w") override val wallMs: Long? = null,
        @SerialName("body") val body: NoteBody,
    ) : Entry

    /** A stray thought from the parking lot. [resolved] once you dealt with it at a break or later. */
    @Serializable
    @SerialName("parked")
    public data class Parked(
        @SerialName("id") override val id: EntryId,
        @SerialName("s") override val session: SessionId,
        @SerialName("at") override val at: Duration? = null,
        @SerialName("w") override val wallMs: Long? = null,
        @SerialName("body") val body: NoteBody,
        @SerialName("resolved") val resolved: Boolean = false,
    ) : Entry

    /** "Next step when you're back?" (Leroy & Glomb 2018). The latest one is offered when the next session starts. */
    @Serializable
    @SerialName("resume")
    public data class ResumeNote(
        @SerialName("id") override val id: EntryId,
        @SerialName("s") override val session: SessionId,
        @SerialName("at") override val at: Duration? = null,
        @SerialName("w") override val wallMs: Long? = null,
        @SerialName("body") val body: NoteBody,
    ) : Entry
}

/** The reason tags of a distraction or end reason; empty for the other kinds. */
public val Entry.tags: List<TagId>
    get() = when (this) {
        is Entry.Distraction -> tags
        is Entry.EndReason -> tags
        else -> emptyList()
    }

/** The note body, if this kind has one. */
public val Entry.body: NoteBody?
    get() = when (this) {
        is Entry.Distraction -> note
        is Entry.Note -> body
        is Entry.Parked -> body
        is Entry.ResumeNote -> body
        is Entry.EndReason -> null
    }

/** A stable name for the kind ("distraction", "end", "note", "parked", "resume"), as in the journal and exports. */
public val Entry.kind: String
    get() = when (this) {
        is Entry.Distraction -> "distraction"
        is Entry.EndReason -> "end"
        is Entry.Note -> "note"
        is Entry.Parked -> "parked"
        is Entry.ResumeNote -> "resume"
    }
