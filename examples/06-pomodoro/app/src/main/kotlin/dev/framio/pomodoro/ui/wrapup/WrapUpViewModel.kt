package dev.framio.pomodoro.ui.wrapup

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.framio.pomodoro.core.engine.FocusState
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.engine.Phase
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.SessionId
import dev.framio.pomodoro.core.notes.Entry
import dev.framio.pomodoro.core.notes.NoteBody
import dev.framio.pomodoro.core.notes.ReasonTag
import dev.framio.pomodoro.core.notes.TagId
import dev.framio.pomodoro.core.notes.TagScope
import dev.framio.pomodoro.core.notes.Taxonomy
import dev.framio.pomodoro.data.HistoryPort
import dev.framio.pomodoro.data.NoHistory
import dev.framio.pomodoro.di.EnginePort
import dev.framio.pomodoro.ui.deep.suggestionsFor
import dev.framio.pomodoro.ui.stateForUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

@Immutable
data class WrapUpUi(
    val accent: Int = 0,
    val breakMinutes: Int = 0,
    val intention: String? = null,
    val outcome: Outcome? = null,
    val rating: Int? = null,
    val note: String = "",
    /** This block's parked thoughts (ink); each can be cleared, the rest are kept. */
    val parked: List<String> = emptyList(),
    /** "Pulled away?" chips: offered tags; [counts] = distractions logged for this block per tag. */
    val reasons: List<ReasonTag> = emptyList(),
    val counts: Map<TagId, Int> = emptyMap(),
    val moreReasons: Boolean = false,
    /** The resume note's handwriting, and its suggestion chips. */
    val noteInk: String? = null,
    val suggestions: List<String> = emptyList(),
    /** Notes written here (ink), and which input is open over the screen. */
    val notes: Int = 0,
    val input: WrapInput? = null,
)

/** What is open over the wrap-up: the pad for the resume note, the pad for a note, or the keyboard. */
enum class WrapInput { ResumePad, NotePad, Keyboard }

/**
 * Deep's wrap-up (PLAN §4.1): one screen, every part optional: Done / Partly / Not yet, focus 1–5, "Next step?", the
 * parked thoughts to review. Leaving it any way (Start break, Skip break, Home) logs what was answered.
 */
class WrapUpViewModel(private val port: EnginePort, private val history: HistoryPort = NoHistory) : ViewModel() {
    private data class Local(
        val session: SessionId? = null,
        val outcome: Outcome? = null,
        val rating: Int? = null,
        val note: String = "",
        val ink: String? = null,
        /** Distractions recalled here, per tag: a second tap takes one back. */
        val recalled: Map<TagId, Entry.Distraction> = emptyMap(),
        val more: Boolean = false,
        val input: WrapInput? = null,
    )

    private val local = MutableStateFlow(Local())

    val ui = combine(port.state, local, history.tags, history.live) { s, l, tags, live -> map(s, current(s, l), tags, live) }
        .stateForUi(viewModelScope, port.state.value.let { map(it, current(it, Local()), history.tags.value, history.live.value) })

    init {
        history.needLive() // distractions logged during focus show here as filled chips
    }

    /**
     * A reason chip (DESIGN-history §5.4): adds a distraction remembered now (no time in the block); tapping the same
     * chip again takes back the one added here. Those logged during focus stay (History edits them).
     */
    fun toggleReason(tag: TagId) {
        val s = port.state.value
        val session = s.session?.id ?: return
        val l = current(s, local.value)
        val mine = l.recalled[tag]
        if (mine != null) {
            history.delete(mine)
            local.value = l.copy(recalled = l.recalled - tag)
        } else {
            val e = Entry.Distraction(history.newEntryId(), session, null, history.wallNow(), listOf(tag))
            history.put(e)
            local.value = l.copy(recalled = l.recalled + (tag to e))
        }
    }

    fun moreReasons() = edit { it.copy(more = true) }

    /** A suggestion chip for the next step: chosen, or tapped again to clear. */
    fun suggest(text: String) = edit { it.copy(note = if (it.note == text) "" else text) }

    fun openInput(input: WrapInput) = edit { it.copy(input = input) }

    fun closeInput() = edit { it.copy(input = null) }

    /** The pad's ink: the resume note's, or a new note on this session. */
    fun saveInk(ink: String?) {
        val l = current(port.state.value, local.value)
        when (l.input) {
            WrapInput.ResumePad -> local.value = l.copy(ink = ink ?: l.ink, input = null)
            WrapInput.NotePad -> {
                val session = port.state.value.session?.id
                if (ink != null && session != null) history.put(Entry.Note(history.newEntryId(), session, null, history.wallNow(), NoteBody.ink(ink)))
                local.value = l.copy(input = null)
            }
            else -> local.value = l.copy(input = null)
        }
    }

    fun clearNote() = edit { it.copy(note = "") }

    fun clearInk() = edit { it.copy(ink = null) }

    /** Tapping the chosen outcome again clears it. */
    fun outcome(o: Outcome) = edit { it.copy(outcome = if (it.outcome == o) null else o) }

    fun rating(r: Int) = edit { it.copy(rating = if (it.rating == r || r !in 1..5) null else r) }

    fun note(text: String) = edit { it.copy(note = text.take(MAX_NOTE)) }

    fun removeParked(index: Int) = port.send(Input.ClearParked(index))

    /** Answers (or, with nothing chosen, skips) the wrap-up; the break starts. */
    fun startBreak() = port.send(answer())

    fun skipBreak() {
        port.send(answer())
        port.send(Input.SkipBreak)
    }

    fun home() {
        port.send(answer())
        port.send(Input.Home)
    }

    /** The Classic break card: answers at once. */
    fun answer(outcome: Outcome?) = port.send(Input.WrapUp(outcome = outcome))

    private fun answer(): Input.WrapUp {
        val l = current(port.state.value, local.value)
        return Input.WrapUp(l.outcome, l.rating, l.note.trim().takeIf { it.isNotEmpty() }, l.ink)
    }

    private fun edit(f: (Local) -> Local) {
        local.value = f(current(port.state.value, local.value))
    }

    /** A new block's wrap-up starts empty. */
    private fun current(s: FocusState, l: Local): Local {
        val id = (s.phase as? Phase.WrapUp)?.session ?: return l
        return if (l.session == id) l else Local(id)
    }

    private fun map(s: FocusState, l: Local, tags: Taxonomy, live: Map<SessionId, List<Entry>>) = WrapUpUi(
        accent = s.settings.accent ?: s.mode.accent,
        breakMinutes = (s.phase as? Phase.WrapUp)?.breakFor?.inWholeMinutes?.toInt() ?: 0,
        intention = s.run?.intent?.intention,
        outcome = l.outcome,
        rating = l.rating,
        note = l.note,
        parked = s.session?.parked.orEmpty().map { it.ink },
        reasons = tags.offered(TagScope.Any),
        counts = countsOf(s.session?.id?.let { live[it] }.orEmpty()),
        moreReasons = l.more,
        noteInk = l.ink,
        suggestions = suggestionsFor(s.run?.intent?.intention),
        notes = s.session?.id?.let { live[it] }.orEmpty().count { it is Entry.Note },
        input = l.input,
    )

    companion object {
        const val MAX_NOTE = 160

        fun factory(port: EnginePort, history: HistoryPort = NoHistory): ViewModelProvider.Factory =
            viewModelFactory { initializer { WrapUpViewModel(port, history) } }
    }
}

/** Distractions per tag (a distraction with two reasons counts once for each). */
fun countsOf(entries: List<Entry>): Map<TagId, Int> {
    val m = LinkedHashMap<TagId, Int>()
    entries.filterIsInstance<Entry.Distraction>().forEach { d -> d.tags.distinct().forEach { m[it] = (m[it] ?: 0) + 1 } }
    return m
}
