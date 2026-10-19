package dev.framio.pomodoro.core.history

import dev.framio.pomodoro.core.AMSTERDAM
import dev.framio.pomodoro.core.model.SessionRecord
import dev.framio.pomodoro.core.notes.Entry
import dev.framio.pomodoro.core.notes.EntryId
import dev.framio.pomodoro.core.notes.Journal
import dev.framio.pomodoro.core.notes.JournalOp
import dev.framio.pomodoro.core.notes.TagId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private var seq = 0

/** A distraction [minute] minutes into [r] (null: recalled afterwards). */
internal fun distraction(r: SessionRecord, minute: Int?, a: TagId, b: TagId? = null, c: TagId? = null): Entry.Distraction =
    Entry.Distraction(EntryId("d${seq++}"), r.id, minute?.minutes, null, listOfNotNull(a, b, c))

internal fun endReason(r: SessionRecord, a: TagId, at: Duration? = r.focused): Entry.EndReason =
    Entry.EndReason(EntryId("x${seq++}"), r.id, at, null, listOf(a))

internal fun journalOf(vararg items: Any): Journal = Journal().also { j ->
    items.forEach {
        when (it) {
            is Entry -> j.apply(JournalOp.Put(it))
            is JournalOp -> j.apply(it)
            else -> error("not an entry or op: $it")
        }
    }
}

internal fun index(records: List<SessionRecord>, journal: Journal = Journal()): HistoryIndex =
    HistoryIndex.build(records, journal, AMSTERDAM)

internal fun stats(index: HistoryIndex): StatsIndex = StatsIndex(AMSTERDAM).also { it.rebuild(index) }
