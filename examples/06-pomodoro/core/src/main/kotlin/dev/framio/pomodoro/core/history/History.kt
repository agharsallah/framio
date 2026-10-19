package dev.framio.pomodoro.core.history

import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.SessionId
import dev.framio.pomodoro.core.model.SessionRecord
import dev.framio.pomodoro.core.model.TaskId
import dev.framio.pomodoro.core.notes.Entry
import dev.framio.pomodoro.core.notes.EntryId
import dev.framio.pomodoro.core.notes.Journal
import dev.framio.pomodoro.core.notes.NoteBody
import dev.framio.pomodoro.core.notes.SessionAmend
import dev.framio.pomodoro.core.notes.TagId
import dev.framio.pomodoro.core.notes.tags
import dev.framio.pomodoro.core.stats.Insights
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.time.Duration

/**
 * One session as History shows it: the log record with History's corrections applied, and its notes in timeline
 * order (by focus time; things recalled afterwards last, in the order written).
 *
 * @param date the local date it started, null when the clock wasn't trusted then
 * @param legacyParked parked thoughts an older version counted but didn't keep (the record has only a count)
 */
public data class SessionView(
    val record: SessionRecord,
    val entries: List<Entry>,
    val date: LocalDate?,
    val legacyParked: Int = 0,
) {
    val id: SessionId get() = record.id

    /** Logged distractions (live and recalled). One distraction can have several reasons. */
    val distractions: Int get() = entries.count { it is Entry.Distraction }

    /** Every reason tag on distractions and end reasons, with repeats. */
    val reasonTags: List<TagId> get() = entries.flatMap { it.tags }

    val hasNotes: Boolean get() = legacyParked > 0 || entries.any { it !is Entry.Distraction && it !is Entry.EndReason }

    public fun startLocal(zone: ZoneId): LocalDateTime? =
        record.startWallMs?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
}

/** Sessions of one local day, newest first. [date] null groups the sessions whose start time was never known. */
public data class DayGroup(val date: LocalDate?, val sessions: List<SessionView>) {
    val focused: Duration get() = sessions.fold(Duration.ZERO) { a, s -> a + s.record.focused }
    val completed: Int get() = sessions.count { it.record.outcome.completed }
}

/**
 * History's filters. An empty set means "any". Dates are inclusive local dates; when either bound is set, sessions with
 * an unknown date are left out.
 */
public data class HistoryFilter(
    val tasks: Set<TaskId> = emptySet(),
    val modes: Set<ModeId> = emptySet(),
    val outcomes: Set<Outcome> = emptySet(),
    val tags: Set<TagId> = emptySet(),
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val withNotes: Boolean = false,
) {
    val isEmpty: Boolean get() = this == NONE

    public fun matches(s: SessionView): Boolean {
        val r = s.record
        if (tasks.isNotEmpty() && (r.task == null || r.task !in tasks)) return false
        if (modes.isNotEmpty() && r.mode !in modes) return false
        if (outcomes.isNotEmpty() && r.outcome !in outcomes) return false
        if (tags.isNotEmpty() && s.entries.none { e -> e.tags.any { it in tags } }) return false
        if (from != null || to != null) {
            val d = s.date ?: return false
            if (from != null && d.isBefore(from)) return false
            if (to != null && d.isAfter(to)) return false
        }
        if (withNotes && !s.hasNotes) return false
        return true
    }

    public companion object {
        public val NONE: HistoryFilter = HistoryFilter()
    }
}

/**
 * The session log joined with the notes journal: an immutable snapshot for History, Insights and export. Build it off
 * the main thread (about 5k sessions and 20k entries take tens of milliseconds on a desktop JVM; see the tests).
 *
 * The session record carries the resume note (words and/or ink): it becomes a [Entry.ResumeNote] with the
 * id [EntryId.legacyResume] (so History can edit or delete it like any other), and a `parked` count with no parked
 * entries becomes [SessionView.legacyParked].
 */
public class HistoryIndex private constructor(
    /** Newest first; sessions with an unknown start last, in log order. */
    public val sessions: List<SessionView>,
    /** Entries whose session isn't in the log (yet): a session still running, or one that was never logged. */
    public val orphans: List<Entry>,
    public val zone: ZoneId,
) {
    private val byId: Map<SessionId, SessionView> = sessions.associateBy { it.id }
    private val byDate: Map<LocalDate?, List<SessionView>> by lazy { sessions.groupBy { it.date } }

    /** The sessions that started on local [date] (null: unknown date), newest first. */
    public fun on(date: LocalDate?): List<SessionView> = byDate[date].orEmpty()

    /** Every local date with at least one session, newest first. */
    public val dates: Set<LocalDate> get() = byDate.keys.filterNotNullTo(LinkedHashSet())

    public fun session(id: SessionId): SessionView? = byId[id]

    public val size: Int get() = sessions.size

    /** Matching sessions grouped by local day, newest day first; unknown dates last. */
    public fun query(filter: HistoryFilter = HistoryFilter.NONE): List<DayGroup> {
        val out = ArrayList<DayGroup>()
        var day: LocalDate? = null
        var bucket = ArrayList<SessionView>()
        var started = false
        for (s in sessions) {
            if (!filter.matches(s)) continue
            if (!started || s.date != day) {
                if (started && bucket.isNotEmpty()) out += DayGroup(day, bucket)
                bucket = ArrayList()
                day = s.date
                started = true
            }
            bucket += s
        }
        if (bucket.isNotEmpty()) out += DayGroup(day, bucket)
        return out
    }

    /** Tasks that appear in the log, most focused first, with their latest title: the History task filter's choices. */
    public fun taskChoices(): List<Pair<TaskId, String>> = sessions.asSequence()
        .filter { it.record.task != null }
        .groupBy { it.record.task }
        .map { (id, list) -> Triple(id, list.first().record.taskTitle ?: list.first().record.intention ?: "", list.sumOf { it.record.focused.inWholeSeconds }) }
        .sortedByDescending { it.third }
        .mapNotNull { (id, title) -> id?.let { it to title } }

    /** The newest resume note: the one offered when the next session starts. */
    public fun latestResume(): Entry.ResumeNote? = sessions.firstNotNullOfOrNull { s ->
        s.entries.lastOrNull { it is Entry.ResumeNote } as Entry.ResumeNote?
    }

    /** Parked thoughts not yet dealt with, newest first: the break's and Home's review list. */
    public fun openParked(): List<Entry.Parked> =
        sessions.flatMap { s -> s.entries.filterIsInstance<Entry.Parked>().filter { !it.resolved }.asReversed() }

    public companion object {
        /** Joins [records] (as `SessionLog.decode` returns them) with [journal]. */
        public fun build(records: List<SessionRecord>, journal: Journal, zone: ZoneId): HistoryIndex {
            val bySession = HashMap<SessionId, MutableList<Entry>>()
            for (e in journal.liveEntries) bySession.getOrPut(e.session) { ArrayList(4) } += e
            val known = HashSet<SessionId>(records.size * 2)
            val views = ArrayList<SessionView>(records.size)
            for (raw in records) {
                known += raw.id
                if (journal.isDropped(raw.id)) continue
                val record = journal.amendOf(raw.id)?.let { raw.amendedBy(it) } ?: raw
                val own = bySession[raw.id].orEmpty()
                val entries = ArrayList<Entry>(own.size + 1)
                entries += own
                val legacyId = EntryId.legacyResume(raw.id)
                val body = NoteBody(raw.resumeNote, raw.resumeInk).cleaned()
                if (body != null && journal.entry(legacyId) == null && !journal.isDeleted(legacyId)) {
                    entries += Entry.ResumeNote(legacyId, raw.id, raw.focused, null, body)
                }
                entries.sortWith(TimelineOrder)
                val legacyParked = if (entries.none { it is Entry.Parked }) raw.parkedCount else 0
                views += SessionView(record, entries, Insights.dateOf(record, zone), legacyParked)
            }
            val dated = views.filter { it.record.startWallMs != null }.sortedByDescending { it.record.startWallMs }
            val undated = views.filter { it.record.startWallMs == null }
            val orphans = bySession.filterKeys { it !in known }.values.flatten()
            return HistoryIndex(dated + undated, orphans, zone)
        }

        /** Timeline order: placed entries by focus time, then recalled ones (no time) in the order they were written. */
        public val TimelineOrder: Comparator<Entry> = compareBy<Entry, Long?>(nullsLast()) { it.at?.inWholeMilliseconds }
            .thenBy(nullsLast()) { it.wallMs }
    }
}

/** This record with a History correction applied. Rating 0 clears it; out-of-range ratings are ignored. */
public fun SessionRecord.amendedBy(a: SessionAmend): SessionRecord = copy(
    outcome = a.outcome ?: outcome,
    rating = when (val r = a.rating) {
        null -> rating
        0 -> null
        in 1..5 -> r
        else -> rating
    },
    task = a.task ?: task,
    taskTitle = a.taskTitle ?: taskTitle,
)
