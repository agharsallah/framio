package dev.framio.pomodoro

import dev.framio.pomodoro.core.AMSTERDAM
import dev.framio.pomodoro.core.history.HistoryIndex
import dev.framio.pomodoro.core.history.StatsIndex
import dev.framio.pomodoro.core.model.SessionId
import dev.framio.pomodoro.core.model.SessionRecord
import dev.framio.pomodoro.core.notes.Entry
import dev.framio.pomodoro.core.notes.EntryId
import dev.framio.pomodoro.core.notes.Journal
import dev.framio.pomodoro.core.notes.JournalOp
import dev.framio.pomodoro.core.notes.SessionAmend
import dev.framio.pomodoro.core.notes.Taxonomy
import dev.framio.pomodoro.data.HistoryPort
import dev.framio.pomodoro.data.HistoryView
import dev.framio.pomodoro.data.StatsView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.ZoneId

/**
 * History in memory for ViewModel tests: the real journal, index and day buckets, applied synchronously. [records]
 * plays the session log; [ops] records every write in order.
 */
class FakeHistory(
    records: List<SessionRecord> = emptyList(),
    private val zone: ZoneId = AMSTERDAM,
    private val wall: Long? = 1_792_310_400_000L,
) : HistoryPort {
    val records = records.toMutableList()
    val journal = Journal()
    val ops = mutableListOf<JournalOp>()
    private var seq = 0
    private var loaded = false

    override val tags = MutableStateFlow(Taxonomy())
    override val history = MutableStateFlow<HistoryView?>(null)
    override val stats = MutableStateFlow<StatsView?>(null)
    override val live = MutableStateFlow<Map<SessionId, List<Entry>>>(emptyMap())
    private val liveState: StateFlow<Map<SessionId, List<Entry>>> get() = live

    var needs = 0
    var releases = 0

    override fun need() {
        needs++
        loaded = true
        rebuild()
    }

    override fun needLive() = Unit

    override fun needStats() {
        needs++
        loaded = true
        rebuild()
    }

    override fun release() {
        releases++
    }

    override fun put(entry: Entry) = write(JournalOp.Put(entry))

    override fun delete(entry: Entry) = write(JournalOp.Delete(entry.id, entry.session))

    override fun amend(amend: SessionAmend) = write(JournalOp.Amend(amend))

    override fun drop(id: SessionId) = write(JournalOp.Drop(id))

    override fun restore(id: SessionId) = write(JournalOp.Restore(id))

    override fun updateTags(change: (Taxonomy) -> Taxonomy) {
        tags.value = change(tags.value).normalized()
    }

    override fun newEntryId(): EntryId = EntryId("e${seq++}")

    override fun wallNow(): Long? = wall

    /** Entries of [session] as the live map has them. */
    fun entries(session: SessionId?): List<Entry> = session?.let { liveState.value[it] }.orEmpty()

    fun log(record: SessionRecord) {
        records += record
        if (loaded) rebuild()
    }

    private fun write(op: JournalOp) {
        ops += op
        journal.apply(op)
        live.value = journal.liveEntries.groupBy { it.session }.mapValues { (_, v) -> v.sortedWith(HistoryIndex.TimelineOrder) }
        if (loaded) rebuild()
    }

    private fun rebuild() {
        val idx = HistoryIndex.build(records, journal, zone)
        history.value = HistoryView(idx, (history.value?.version ?: 0) + 1)
        stats.value = StatsView(StatsIndex(zone).also { it.rebuild(idx) }.buckets.toList(), false, (stats.value?.version ?: 0) + 1)
    }
}
