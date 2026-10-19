package dev.framio.pomodoro.ui.focus

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.framio.pomodoro.core.engine.FocusState
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.engine.Phase
import dev.framio.pomodoro.core.model.Face
import dev.framio.pomodoro.core.model.SessionId
import dev.framio.pomodoro.core.notes.Entry
import dev.framio.pomodoro.core.notes.ReasonTag
import dev.framio.pomodoro.core.notes.TagId
import dev.framio.pomodoro.core.notes.TagScope
import dev.framio.pomodoro.core.notes.Taxonomy
import dev.framio.pomodoro.data.HistoryPort
import dev.framio.pomodoro.data.NoHistory
import dev.framio.pomodoro.ui.deep.suggestionsFor
import dev.framio.pomodoro.ui.notes.EndDraft
import dev.framio.pomodoro.ui.notes.EndFlow
import dev.framio.pomodoro.core.time.Now
import dev.framio.pomodoro.di.EnginePort
import dev.framio.pomodoro.ui.FaceTiming
import dev.framio.pomodoro.ui.stateForUi
import dev.framio.pomodoro.ui.timing
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Immutable
data class FocusUi(
    val modeName: String = "",
    val accent: Int = 0,
    val intention: String? = null,
    val face: Face = Face.Ring,
    val paused: Boolean = false,
    /** Deep's pause cap ran out: the app offers to end. */
    val overCap: Boolean = false,
    val showSeconds: Boolean = true,
    /** Fixed rhythms can take +5 min; Flow has no end to extend. */
    val canExtend: Boolean = true,
    val controls: Boolean = false,
    val timing: FaceTiming = FaceTiming.None,
    /** The end sheet's answers while it is open; null when closed. */
    val end: EndDraft? = null,
    /** "Pulled away?": the reason chips are open (one tap logs and closes). */
    val reasonSheet: Boolean = false,
    val reasons: List<ReasonTag> = emptyList(),
    val logged: Int = 0,
    val endReasons: List<ReasonTag> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val focusedMinutes: Int? = null,
    val plannedMinutes: Int? = null,
) {
    /** The "Next step when you're back?" words while the end sheet is open; null when closed. */
    val note: String? get() = end?.text
}

/** Focus (Full display, M2): the face, and controls a tap away. Paused always shows its controls. */
class FocusViewModel(private val port: EnginePort, private val history: HistoryPort = NoHistory) : ViewModel() {
    private data class Local(val controls: Boolean = false, val reasons: Boolean = false)

    private val local = MutableStateFlow(Local())
    private val endFlow = EndFlow(history)
    private var hide: Job? = null

    val ui = combine(port.state, local, endFlow.draft, history.tags, history.live) { s, l, end, tags, live -> map(s, l, end, tags, live) }
        .stateForUi(viewModelScope, map(port.state.value, Local(), null, history.tags.value, history.live.value))

    /** The end sheet's own controls (reasons, suggestions, pad, keyboard). */
    val end: EndFlow get() = endFlow

    fun now(): Now = port.now()

    /** A tap anywhere: show the controls for [CONTROLS_FOR], or hide them. */
    fun toggleControls() {
        hide?.cancel()
        if (local.value.reasons) {
            local.value = Local()
            return
        }
        local.value = Local(controls = !local.value.controls)
        if (local.value.controls) {
            hide = viewModelScope.launch {
                delay(CONTROLS_FOR)
                local.value = local.value.copy(controls = false)
            }
        }
    }

    /** "Pulled away?" (DESIGN-history §5.2): the reason chips. */
    fun pulledAway() {
        hide?.cancel()
        local.value = Local(reasons = true)
    }

    /** One tap logs a distraction now, at this focus time, and closes the chips. */
    fun logReason(tag: TagId) {
        val session = port.state.value.session?.id
        if (session != null) {
            history.put(Entry.Distraction(history.newEntryId(), session, ui.value.timing.focused(port.now()), history.wallNow(), listOf(tag)))
        }
        local.value = Local()
    }

    fun closeReasons() {
        local.value = Local()
    }

    fun pause() = act(Input.Pause)

    fun resume() = act(Input.Resume)

    /** End: first "Next step when you're back?" (PLAN §3 principle 4); [confirmEnd] ends. */
    fun end() {
        hide?.cancel()
        local.value = Local()
        endFlow.open()
    }

    fun editNote(text: String) = endFlow.editText(text)

    /** Ends early with the reasons and the note, if any: logged as ended early, never as failed. */
    fun confirmEnd() {
        port.send(endFlow.finish(port.state.value.session?.id, ui.value.timing.focused(port.now())))
    }

    fun cancelEnd() = endFlow.close()

    /** The screen went away: no prompt left open for the next block. */
    fun leave() {
        hide?.cancel()
        local.value = Local()
        endFlow.close()
    }

    fun extend(by: Duration = 5.minutes) = act(Input.Extend(by))

    private fun act(input: Input) {
        hide?.cancel()
        local.value = Local()
        port.send(input)
    }

    private fun map(s: FocusState, l: Local, end: EndDraft?, tags: Taxonomy, live: Map<SessionId, List<Entry>>): FocusUi {
        val mode = s.mode
        val paused = s.phase as? Phase.Paused
        val timing = port.timing(s)
        val endOnly = tags.offered(TagScope.EndOnly)
        return FocusUi(
            modeName = mode.name,
            accent = s.settings.accent ?: mode.accent,
            intention = s.run?.intent?.intention,
            face = s.settings.faceOf(mode),
            paused = paused != null,
            overCap = paused?.overCap == true,
            showSeconds = mode.environment.showSeconds,
            canExtend = s.session?.planned != null,
            controls = l.controls || paused != null,
            timing = timing,
            end = end,
            reasonSheet = l.reasons,
            reasons = tags.offered(TagScope.Any),
            logged = s.session?.id?.let { live[it] }.orEmpty().count { it is Entry.Distraction },
            endReasons = endOnly.filter { it.scope == TagScope.EndOnly } + endOnly.filter { it.scope != TagScope.EndOnly },
            suggestions = suggestionsFor(s.run?.intent?.intention),
            focusedMinutes = if (end != null) timing.focused(port.now())?.inWholeMinutes?.toInt() else null,
            plannedMinutes = s.session?.planned?.inWholeMinutes?.toInt(),
        )
    }

    companion object {
        val CONTROLS_FOR: Duration = 8.seconds
        const val MAX_NOTE = EndFlow.MAX_NOTE

        fun factory(port: EnginePort, history: HistoryPort = NoHistory): ViewModelProvider.Factory =
            viewModelFactory { initializer { FocusViewModel(port, history) } }
    }
}
