package dev.framio.pomodoro.data

import androidx.compose.runtime.Immutable
import dev.framio.pomodoro.core.history.Checkpoint
import dev.framio.pomodoro.core.history.DayBucket
import dev.framio.pomodoro.core.history.Export
import dev.framio.pomodoro.core.history.HistoryIndex
import dev.framio.pomodoro.core.history.HistorySeed
import dev.framio.pomodoro.core.history.StatsCache
import dev.framio.pomodoro.core.history.StatsIndex
import dev.framio.pomodoro.core.log.SessionLog
import dev.framio.pomodoro.core.model.ParkedThought
import dev.framio.pomodoro.core.model.SessionId
import dev.framio.pomodoro.core.model.SessionRecord
import dev.framio.pomodoro.core.notes.Entry
import dev.framio.pomodoro.core.notes.EntryId
import dev.framio.pomodoro.core.notes.Journal
import dev.framio.pomodoro.core.notes.JournalCodec
import dev.framio.pomodoro.core.notes.JournalOp
import dev.framio.pomodoro.core.notes.NoteBody
import dev.framio.pomodoro.core.notes.SessionAmend
import dev.framio.pomodoro.core.notes.Taxonomy
import dev.framio.pomodoro.core.stats.Insights
import dev.framio.pomodoro.platform.Logs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.time.Duration

/** History as the screens get it: an immutable joined index. [version] grows with every change. */
@Immutable
data class HistoryView(val index: HistoryIndex, val version: Int)

/** Insights' day buckets; [fromCache] when they came from the cache file without reading the log. */
@Immutable
data class StatsView(val buckets: List<DayBucket>, val fromCache: Boolean, val version: Int)

/**
 * What screens see of the session history and its notes (DESIGN-history §6). ViewModels depend on this, so their tests
 * run against an in-memory fake. Every write is applied in order on one writer; nothing here blocks the caller.
 */
interface HistoryPort {
    val tags: StateFlow<Taxonomy>

    /** Null until [need] has loaded the history once. */
    val history: StateFlow<HistoryView?>

    /** Null until [needStats]; from the cache file when it is fresh. */
    val stats: StateFlow<StatsView?>

    /** Entries per session: this process's writes at once, everything once loaded. For the panel and the wrap-up. */
    val live: StateFlow<Map<SessionId, List<Entry>>>

    /** Loads the history (if it isn't yet) and keeps [history] current from now on. */
    fun need()

    /** Loads the journal for [live] (the wrap-up's reasons logged during focus), without watching the index. */
    fun needLive()

    /** The screen that called [need] or [needStats] is gone: stop rebuilding on every change. */
    fun release()

    /** Makes [stats] current, from the cache when it is fresh, else by loading. */
    fun needStats()

    /** Adds an entry, or replaces the one with its id (an edit, or the undo of a delete). */
    fun put(entry: Entry)

    fun delete(entry: Entry)

    fun amend(amend: SessionAmend)

    fun drop(id: SessionId)

    fun restore(id: SessionId)

    fun updateTags(change: (Taxonomy) -> Taxonomy)

    fun newEntryId(): EntryId

    /** Trusted wall time now, for an entry's "written at"; null while the clock isn't trusted. */
    fun wallNow(): Long?
}

/** The tag taxonomy (`files/datastore/tags.json`). */
class TagStore(file: File, scope: CoroutineScope, io: CoroutineDispatcher) :
    JsonStore<Taxonomy>(file, scope, io, Taxonomy(), Taxonomy::encode, Taxonomy::decode)

/**
 * The single writer of `history.jsonl` and owner of the joined history (DESIGN-history §6, §10). Loading is lazy:
 * logging a distraction during focus only appends a line; the log and the journal are read the first time History,
 * Insights (without a fresh cache), the wrap-up or an export needs them. After that every change is applied in
 * memory, and the index and day buckets are rebuilt only while someone watches them (else on the next [need]).
 * Insights' day buckets are cached in `stats-cache.json` (fresh while both files keep their lengths), so Insights
 * opens without reading the log.
 */
class HistoryRepository(
    private val sessions: SessionLogFile,
    private val journalFile: LineFile,
    private val cacheFile: File,
    private val tagStore: JsonStore<Taxonomy>,
    private val scope: CoroutineScope,
    io: CoroutineDispatcher,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val wall: () -> Long? = { System.currentTimeMillis() },
    private val cacheDelay: Duration = Duration.ZERO,
) : HistoryPort {
    private val writer = io.limitedParallelism(1)
    private val _tags = MutableStateFlow(Taxonomy())
    private val _history = MutableStateFlow<HistoryView?>(null)
    private val _stats = MutableStateFlow<StatsView?>(null)
    private val _live = MutableStateFlow<Map<SessionId, List<Entry>>>(emptyMap())
    private val tagsLoad = lazy { scope.launch(io) { _tags.value = tagStore.load() } }

    /** Loaded on first use (a capture sheet, History, Settings): Home's start-up doesn't read the tags file. */
    override val tags: StateFlow<Taxonomy>
        get() {
            tagsLoad.value
            return _tags
        }
    override val history: StateFlow<HistoryView?> = _history.asStateFlow()
    override val stats: StateFlow<StatsView?> = _stats.asStateFlow()
    override val live: StateFlow<Map<SessionId, List<Entry>>> = _live.asStateFlow()

    // Writer-only state
    private var loaded = false
    private val records = LinkedHashMap<String, SessionRecord>()
    private var journal = Journal()
    private var index: HistoryIndex? = null
    private var buckets: StatsIndex? = null
    private var indexCurrent = false
    private var statsCurrent = false
    private val dirty = HashSet<LocalDate>()
    private var full = true
    private var builtZone: ZoneId? = null
    private var version = 0
    private var checkpoint = Checkpoint(0, 0)
    private var cacheJob: Job? = null
    private var watching = false
    private var watchingStats = false

    override fun need() = onWriter {
        watching = true
        loadIfNeeded()
        if (!indexCurrent || builtZone != zone()) rebuild()
    }

    override fun needStats() = onWriter {
        watchingStats = true
        if (!loaded) {
            if (statsCurrent && builtZone == zone()) return@onWriter
            if (tryCache()) return@onWriter
            loadIfNeeded()
        }
        if (!statsCurrent || !indexCurrent || builtZone != zone()) rebuild()
    }

    override fun needLive() = onWriter { loadIfNeeded() }

    override fun release() = onWriter {
        watching = false
        watchingStats = false
    }

    override fun put(entry: Entry) = onWriter { write(JournalOp.Put(entry)) }

    override fun delete(entry: Entry) = onWriter { write(JournalOp.Delete(entry.id, entry.session)) }

    override fun amend(amend: SessionAmend) = onWriter { write(JournalOp.Amend(amend)) }

    override fun drop(id: SessionId) = onWriter { write(JournalOp.Drop(id)) }

    override fun restore(id: SessionId) = onWriter { write(JournalOp.Restore(id)) }

    override fun updateTags(change: (Taxonomy) -> Taxonomy) {
        val next = change(tags.value).normalized()
        _tags.value = next
        tagStore.offer(next)
    }

    override fun newEntryId(): EntryId = EntryId("e-${UUID.randomUUID()}")

    override fun wallNow(): Long? = wall()

    /** A session was appended to the log (called after the append, by the log writer). */
    fun onLogged(record: SessionRecord) = onWriter {
        if (loaded) {
            records[record.id.value] = record
            Insights.dateOf(record, zone())?.let(dirty::add)
        } else {
            full = true
        }
        changed()
    }

    /** The engine parked a thought (P1): kept in the journal for History, under a derived id. */
    fun parked(session: SessionId, thought: ParkedThought, at: Duration) = onWriter {
        write(JournalOp.Put(Entry.Parked(EntryId.parked(thought.at), session, at, thought.at.wallMs, NoteBody.ink(thought.ink))))
    }

    /** The engine cleared parked thoughts (dealt with): marked resolved in the journal. */
    fun resolved(thoughts: List<ParkedThought>) = onWriter {
        loadIfNeeded()
        for (t in thoughts) {
            val e = journal.entry(EntryId.parked(t.at)) as? Entry.Parked ?: continue
            if (!e.resolved) write(JournalOp.Put(e.copy(resolved = true)))
        }
    }

    /** Waits until every write queued so far is applied (tests, and before an export). */
    suspend fun flush() = withContext(writer) {}

    /** Writes the export (JSON + two CSVs) into [dir]; returns the files. */
    suspend fun export(dir: File, nowMs: Long): List<File> = withContext(writer) {
        loadIfNeeded()
        if (!indexCurrent || builtZone != zone()) rebuild()
        val idx = index ?: return@withContext emptyList()
        dir.mkdirs()
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(Instant.ofEpochMilli(nowMs).atZone(zone()))
        tagsLoad.value.join() // the tags file is read on first use; an export right after start-up waits for it
        val tags = _tags.value
        val files = listOf(
            File(dir, "pomodoro-$stamp.json") to Export.json(idx, tags, nowMs),
            File(dir, "pomodoro-$stamp-sessions.csv") to Export.sessionsCsv(idx, tags),
            File(dir, "pomodoro-$stamp-entries.csv") to Export.entriesCsv(idx, tags),
        )
        files.forEach { (f, text) -> f.writeText(text) }
        Logs.event("export", "${idx.size} sessions, ${idx.sessions.sumOf { it.entries.size }} entries → ${dir.path}")
        files.map { it.first }
    }

    /**
     * Debug: appends [days] days of made-up history ([HistorySeed], ids `seed-…`), or with 0 removes every seeded line
     * again. Not for release builds: the session log has its own writer, and this one writes it too.
     */
    suspend fun seed(days: Int): Int = withContext(writer) {
        val t0 = System.nanoTime()
        val count: Int
        if (days > 0) {
            val today = Instant.ofEpochMilli(wall() ?: System.currentTimeMillis()).atZone(zone()).toLocalDate()
            val s = HistorySeed.generate(days, today, zone(), seed = days)
            sessions.appendLines(s.records.map(SessionLog::encode))
            journalFile.append(s.ops.map(JournalCodec::encode))
            count = s.records.size
        } else {
            val mark = "\"${HistorySeed.PREFIX}"
            val kept = sessions.lines().filterNot { mark in it }
            count = sessions.lines().size - kept.size
            sessions.rewrite(kept)
            journalFile.rewrite(journalFile.readText().lineSequence().filter { it.isNotBlank() && mark !in it }.toList())
        }
        cacheFile.delete()
        reset()
        if (watching || watchingStats) {
            loadIfNeeded()
            rebuild()
        }
        Logs.event("seed", "${if (days > 0) "added" else "removed"} $count sessions in ${(System.nanoTime() - t0) / 1_000_000} ms")
        count
    }

    private fun reset() {
        loaded = false
        records.clear()
        journal = Journal()
        index = null
        buckets = null
        indexCurrent = false
        statsCurrent = false
        full = true
        dirty.clear()
        _live.value = emptyMap()
    }

    private fun onWriter(block: () -> Unit) {
        scope.launch(writer) {
            try {
                block()
            } catch (e: Exception) {
                Logs.warn("history", "failed: ${e.message}", e)
            }
        }
    }

    private fun write(op: JournalOp) {
        journalFile.append(JournalCodec.encode(op))
        if (loaded) {
            journal.apply(op)
            records[op.session.value]?.let { Insights.dateOf(it, zone()) }?.let(dirty::add)
        } else {
            full = true
        }
        updateLive(op)
        changed()
    }

    private fun changed() {
        indexCurrent = false
        statsCurrent = false
        if (watching || watchingStats) {
            loadIfNeeded()
            rebuild()
        }
    }

    private fun updateLive(op: JournalOp) {
        val map = _live.value
        val list = map[op.session].orEmpty()
        val next = when (op) {
            is JournalOp.Put -> (list.filterNot { it.id == op.entry.id } + op.entry).sortedWith(HistoryIndex.TimelineOrder)
            is JournalOp.Delete -> list.filterNot { it.id == op.id }
            else -> return
        }
        _live.value = map + (op.session to next)
    }

    private fun loadIfNeeded() {
        if (loaded) return
        val t0 = System.nanoTime()
        val decoded = sessions.read()
        records.clear()
        decoded.records.forEach { records[it.id.value] = it }
        journal = JournalCodec.read(journalFile.readText())
        if (journal.shouldCompact) {
            journalFile.rewrite(journal.compacted())
            Logs.event("history", "journal compacted: ${journal.lines} → ${journal.compacted().size} lines")
            journal = JournalCodec.read(journalFile.readText())
        }
        loaded = true
        full = true
        _live.value = journal.liveEntries.groupBy { it.session }.mapValues { (_, v) -> v.sortedWith(HistoryIndex.TimelineOrder) }
        Logs.event(
            "history",
            "loaded ${records.size} sessions (${decoded.skipped} unreadable), ${journal.liveEntries.size} entries in ${(System.nanoTime() - t0) / 1_000_000} ms",
        )
    }

    private fun rebuild() {
        val t0 = System.nanoTime()
        val z = zone()
        val idx = HistoryIndex.build(records.values.toList(), journal, z)
        val stats = buckets
        if (stats == null || full || builtZone != z) {
            buckets = StatsIndex(z).also { it.rebuild(idx) }
        } else {
            stats.refresh(idx, dirty)
        }
        dirty.clear()
        full = false
        builtZone = z
        index = idx
        indexCurrent = true
        statsCurrent = true
        checkpoint = Checkpoint(sessions.length, journalFile.length)
        version++
        _history.value = HistoryView(idx, version)
        _stats.value = StatsView(buckets?.buckets?.toList().orEmpty(), fromCache = false, version = version)
        Logs.event("history", "index ${idx.size} sessions in ${(System.nanoTime() - t0) / 1_000_000} ms")
        scheduleCache()
    }

    private fun tryCache(): Boolean {
        val t0 = System.nanoTime()
        val text = try {
            if (cacheFile.exists()) cacheFile.readText() else return false
        } catch (_: Exception) {
            return false
        }
        val cache = StatsCache.decode(text) ?: return false
        val z = zone()
        if (!cache.isFresh(Checkpoint(sessions.length, journalFile.length), z.id)) return false
        buckets = StatsIndex(z).also { it.load(cache.days) }
        builtZone = z
        statsCurrent = true
        full = true // the index isn't built: the next load rebuilds everything
        version++
        _stats.value = StatsView(cache.days, fromCache = true, version = version)
        Logs.event("history", "stats from cache: ${cache.days.size} days in ${(System.nanoTime() - t0) / 1_000_000} ms")
        return true
    }

    private fun scheduleCache() {
        cacheJob?.cancel()
        cacheJob = scope.launch(writer) {
            if (cacheDelay.isPositive()) delay(cacheDelay)
            val stats = buckets ?: return@launch
            val z = builtZone ?: return@launch
            if (!statsCurrent) return@launch
            try {
                val tmp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
                // Days older than ~6 months folded into months: a third of the size, the same reports (DESIGN-history §10)
                val today = Instant.ofEpochMilli(wall() ?: System.currentTimeMillis()).atZone(z).toLocalDate()
                val days = DayBucket.foldMonths(stats.buckets, today.minusDays(DayBucket.KEEP_DAYS).withDayOfMonth(1))
                tmp.writeText(StatsCache.encode(StatsCache(zone = z.id, checkpoint = checkpoint, days = days)))
                if (!tmp.renameTo(cacheFile)) {
                    cacheFile.delete()
                    tmp.renameTo(cacheFile)
                }
            } catch (e: Exception) {
                Logs.warn("history", "cache write failed: ${e.message}", e)
            }
        }
    }
}

/** No history at all (previews, and screens built without one): reads are empty, writes do nothing. */
object NoHistory : HistoryPort {
    override val tags: StateFlow<Taxonomy> = MutableStateFlow(Taxonomy()).asStateFlow()
    override val history: StateFlow<HistoryView?> = MutableStateFlow<HistoryView?>(null).asStateFlow()
    override val stats: StateFlow<StatsView?> = MutableStateFlow<StatsView?>(null).asStateFlow()
    override val live: StateFlow<Map<SessionId, List<Entry>>> = MutableStateFlow(emptyMap<SessionId, List<Entry>>()).asStateFlow()
    override fun need() = Unit
    override fun needLive() = Unit
    override fun needStats() = Unit
    override fun release() = Unit
    override fun put(entry: Entry) = Unit
    override fun delete(entry: Entry) = Unit
    override fun amend(amend: SessionAmend) = Unit
    override fun drop(id: SessionId) = Unit
    override fun restore(id: SessionId) = Unit
    override fun updateTags(change: (Taxonomy) -> Taxonomy) = Unit
    override fun newEntryId(): EntryId = EntryId("e-none")
    override fun wallNow(): Long? = null
}
