package dev.framio.pomodoro.ui.history

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.framio.pomodoro.R
import dev.framio.pomodoro.core.history.DayGroup
import dev.framio.pomodoro.core.history.HistoryFilter
import dev.framio.pomodoro.core.history.HistoryIndex
import dev.framio.pomodoro.core.history.SessionView
import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.SessionId
import dev.framio.pomodoro.core.model.TaskId
import dev.framio.pomodoro.core.notes.Entry
import dev.framio.pomodoro.core.notes.EntryId
import dev.framio.pomodoro.core.notes.NoteBody
import dev.framio.pomodoro.core.notes.SessionAmend
import dev.framio.pomodoro.core.notes.TagId
import dev.framio.pomodoro.core.notes.Taxonomy
import dev.framio.pomodoro.data.HistoryPort
import dev.framio.pomodoro.data.HistoryView
import dev.framio.pomodoro.ui.stateForUi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** History's date presets (DESIGN-history §7): rows of presets, no calendar to fight. */
enum class DateRange(val days: Int?, @param:StringRes val label: Int) {
    All(null, R.string.range_all),
    Today(1, R.string.range_today),
    Week(7, R.string.range_week),
    Month(28, R.string.range_month),
    Quarter(84, R.string.range_quarter),
}

/** Which chooser is open over History. */
sealed interface HistorySheet {
    data object Range : HistorySheet
    data object Tasks : HistorySheet
    data object Modes : HistorySheet
    data object Outcomes : HistorySheet
    data object Reasons : HistorySheet

    /** Change the reasons of one distraction or end reason. */
    data class EditReasons(val entry: EntryId) : HistorySheet

    /** Add a distraction to the open session (recalled: no time). */
    data object AddDistraction : HistorySheet

    /** Handwrite a note on the open session. */
    data object AddNote : HistorySheet
}

@Immutable
data class HistoryUi(
    val loading: Boolean = true,
    val groups: List<DayGroup> = emptyList(),
    /** Sessions in the whole history, and those the filters let through. */
    val total: Int = 0,
    val shown: Int = 0,
    val filter: HistoryFilter = HistoryFilter.NONE,
    val range: DateRange = DateRange.All,
    val tasks: List<Pair<TaskId, String>> = emptyList(),
    val modes: List<Pair<ModeId, String>> = emptyList(),
    val tags: Taxonomy = Taxonomy(),
    val detail: SessionView? = null,
    val sheet: HistorySheet? = null,
    @param:StringRes val undo: Int? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    val filtered: Boolean get() = !filter.isEmpty || range != DateRange.All

    /** The entry a sheet edits, if any. */
    val editing: Entry? get() = (sheet as? HistorySheet.EditReasons)?.let { s -> detail?.entries?.firstOrNull { it.id == s.entry } }
}

/**
 * History (DESIGN-history §7): the sessions by day, one row of filters, a session's detail with its notes timeline.
 * Every change is written at once; delete shows "Deleted · Undo" for [UNDO_FOR] and never asks first.
 */
class HistoryViewModel(
    private val history: HistoryPort,
    private val today: () -> LocalDate,
    compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private data class Local(
        val filter: HistoryFilter = HistoryFilter.NONE,
        val range: DateRange = DateRange.All,
        val selected: SessionId? = null,
        val sheet: HistorySheet? = null,
        @param:StringRes val undo: Int? = null,
    )

    private val local = MutableStateFlow(Local())
    private var undoAction: (() -> Unit)? = null
    private var undoJob: Job? = null

    val ui = combine(history.history, local, history.tags) { h, l, t -> map(h, l, t) }
        .flowOn(compute)
        .stateForUi(viewModelScope, HistoryUi(tags = history.tags.value))

    init {
        history.need()
    }

    override fun onCleared() = history.release()

    // Filters

    fun setRange(r: DateRange) = local.update { it.copy(range = r, sheet = null) }

    fun toggleTask(id: TaskId) = filter { it.copy(tasks = it.tasks.toggle(id)) }

    fun toggleMode(id: ModeId) = filter { it.copy(modes = it.modes.toggle(id)) }

    fun toggleOutcome(o: Outcome) = filter { it.copy(outcomes = it.outcomes.toggle(o)) }

    fun toggleTag(id: TagId) = filter { it.copy(tags = it.tags.toggle(id)) }

    fun toggleNotes() = filter { it.copy(withNotes = !it.withNotes) }

    fun clearFilters() = local.update { it.copy(filter = HistoryFilter.NONE, range = DateRange.All) }

    fun openSheet(sheet: HistorySheet) = local.update { it.copy(sheet = sheet) }

    fun closeSheet() = local.update { it.copy(sheet = null) }

    // Detail

    fun select(id: SessionId) = local.update { it.copy(selected = id, sheet = null) }

    fun closeDetail() = local.update { it.copy(selected = null, sheet = null) }

    /** Tapping the current outcome again does nothing (an outcome is never "none"). */
    fun setOutcome(o: Outcome) {
        val id = local.value.selected ?: return
        history.amend(SessionAmend(id, outcome = o))
    }

    /** 1..5; tapping the current rating clears it. */
    fun setRating(r: Int) {
        val s = selected() ?: return
        history.amend(SessionAmend(s.id, rating = if (s.record.rating == r) 0 else r))
    }

    /** Adds or removes [tag] on the distraction or end reason being edited; written at once. */
    fun toggleEntryTag(tag: TagId) {
        val e = ui.value.editing ?: return
        val next = when (e) {
            is Entry.Distraction -> e.copy(tags = e.tags.toggle(tag))
            is Entry.EndReason -> e.copy(tags = e.tags.toggle(tag))
            else -> return
        }
        history.put(next)
    }

    /** A distraction remembered afterwards (no time in the block). One tap, then the sheet closes. */
    fun addDistraction(tag: TagId) {
        val id = local.value.selected ?: return
        history.put(Entry.Distraction(history.newEntryId(), id, null, history.wallNow(), listOf(tag)))
        closeSheet()
    }

    fun saveNote(ink: String?) {
        val id = local.value.selected
        if (id != null && ink != null) history.put(Entry.Note(history.newEntryId(), id, null, history.wallNow(), NoteBody.ink(ink)))
        closeSheet()
    }

    fun toggleResolved(p: Entry.Parked) = history.put(p.copy(resolved = !p.resolved))

    fun deleteEntry(e: Entry) {
        history.delete(e)
        offerUndo(R.string.deleted_entry) { history.put(e) }
    }

    fun deleteSession() {
        val id = local.value.selected ?: return
        history.drop(id)
        local.update { it.copy(selected = null, sheet = null) }
        offerUndo(R.string.deleted_session) {
            history.restore(id)
            local.update { it.copy(selected = id) }
        }
    }

    fun undo() {
        undoJob?.cancel()
        undoAction?.invoke()
        undoAction = null
        local.update { it.copy(undo = null) }
    }

    private fun offerUndo(@StringRes message: Int, action: () -> Unit) {
        undoJob?.cancel()
        undoAction = action
        local.update { it.copy(undo = message) }
        undoJob = viewModelScope.launch {
            delay(UNDO_FOR)
            undoAction = null
            local.update { it.copy(undo = null) }
        }
    }

    private fun selected(): SessionView? = ui.value.detail

    private fun filter(f: (HistoryFilter) -> HistoryFilter) = local.update { it.copy(filter = f(it.filter)) }

    private fun map(h: HistoryView?, l: Local, tags: Taxonomy): HistoryUi {
        val idx: HistoryIndex = h?.index ?: return HistoryUi(loading = true, tags = tags, filter = l.filter, range = l.range, sheet = l.sheet, undo = l.undo)
        val end = today()
        val f = l.range.days?.let { l.filter.copy(from = end.minusDays(it - 1L), to = end) } ?: l.filter.copy(from = null, to = null)
        val groups = idx.query(f)
        return HistoryUi(
            loading = false,
            groups = groups,
            total = idx.size,
            shown = groups.sumOf { it.sessions.size },
            filter = l.filter,
            range = l.range,
            tasks = idx.taskChoices(),
            modes = idx.sessions.asSequence().map { it.record.mode to (it.record.modeName ?: it.record.mode.value) }.distinctBy { it.first }.toList(),
            tags = tags,
            detail = l.selected?.let(idx::session),
            sheet = l.sheet,
            undo = l.undo,
            zone = idx.zone,
        )
    }

    companion object {
        val UNDO_FOR: Duration = 6.seconds

        fun factory(history: HistoryPort, today: () -> LocalDate): ViewModelProvider.Factory =
            viewModelFactory { initializer { HistoryViewModel(history, today) } }
    }
}

private fun <T> Set<T>.toggle(x: T): Set<T> = if (x in this) this - x else this + x

private fun <T> List<T>.toggle(x: T): List<T> = if (x in this) this - x else this + x
