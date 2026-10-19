package dev.framio.pomodoro.ui.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.framio.pomodoro.core.engine.FocusState
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.engine.Notice
import dev.framio.pomodoro.core.model.IntentionRule
import dev.framio.pomodoro.core.model.Mode
import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Rhythm
import dev.framio.pomodoro.core.model.Task
import dev.framio.pomodoro.di.EnginePort
import dev.framio.pomodoro.ui.stateForUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/** One mode in the carousel. [focusMinutes]/[breakMinutes] are null for Flow (counts up). */
@Immutable
data class ModeCard(
    val id: ModeId,
    val name: String,
    val accent: Int,
    val focusMinutes: Int?,
    val breakMinutes: Int?,
    val builtIn: Boolean,
)

@Immutable
data class HomeUi(
    val modes: List<ModeCard> = emptyList(),
    val selected: ModeId = Presets.CLASSIC_ID,
    val accent: Int = 0,
    /** Recent tasks, most recently used first (at most [MAX_CHIPS]). */
    val tasks: List<String> = emptyList(),
    val chosenTask: String? = null,
    /** The "+ New" text being typed; null when the entry is closed. */
    val draft: String? = null,
    val resumeNote: String? = null,
    val resumeInk: String? = null,
    val done: Int = 0,
    val target: Int = 6,
    val intention: IntentionRule = IntentionRule.Optional,
    val interrupted: Boolean = false,
    /** Begin opens the mode's ritual screen first (Deep), where the intention can still be chosen. */
    val ritual: Boolean = false,
    /** The ritual screen is skipped (your choice after its first run): Home offers a way back into it. */
    val ritualSkipped: Boolean = false,
) {
    val canBegin: Boolean get() = ritual || intention != IntentionRule.Required || chosenTask != null
    val focusMinutes: Int? get() = modes.firstOrNull { it.id == selected }?.focusMinutes
    val breakMinutes: Int? get() = modes.firstOrNull { it.id == selected }?.breakMinutes

    companion object {
        const val MAX_CHIPS = 5
    }
}

/** Home: pick a mode and a task, then Begin. The task choice and the draft are this screen's own state. */
class HomeViewModel(private val port: EnginePort) : ViewModel() {
    private val chosen = MutableStateFlow<String?>(null)
    private val draft = MutableStateFlow<String?>(null)

    val ui = combine(port.state, chosen, draft, ::map).stateForUi(viewModelScope, map(port.state.value, null, null))

    fun selectMode(id: ModeId) = port.send(Input.SelectMode(id))

    /** Tapping the chosen chip again un-chooses it. */
    fun chooseTask(title: String) {
        chosen.value = if (chosen.value.equals(title, ignoreCase = true)) null else title
    }

    fun openDraft() {
        draft.value = ""
    }

    fun editDraft(text: String) {
        draft.value = text.take(Task.MAX_TITLE)
    }

    fun cancelDraft() {
        draft.value = null
    }

    /** Adds the typed task (if any) and chooses it. */
    fun addDraft() {
        val title = draft.value?.trim().orEmpty()
        draft.value = null
        if (title.isEmpty()) return
        port.send(Input.AddTask(title))
        chosen.value = title
    }

    /** Through the ritual when the mode has one (and it isn't skipped), else straight into focus. */
    fun begin() {
        val u = ui.value
        if (!u.canBegin) return
        if (u.ritual) {
            port.send(Input.OpenRitual(chosen.value))
            return
        }
        val s = port.state.value
        val memory = s.ritual(u.selected)
        // A skipped ritual still applies what it remembers: the if-then line and the screen choice
        port.send(Input.Begin(intention = chosen.value, ifThen = memory?.ifThen.takeIf { u.ritualSkipped }, display = memory?.display.takeIf { u.ritualSkipped }))
    }

    /** The ritual on purpose, when it is normally skipped. */
    fun openRitual() = port.send(Input.OpenRitual(chosen.value))

    fun dismissNotice() = port.send(Input.DismissNotice)

    private fun map(s: FocusState, chosenTask: String?, draftText: String?): HomeUi {
        val mode = s.settings.selected
        val titles = s.tasks.take(HomeUi.MAX_CHIPS).map { it.title }
        return HomeUi(
            modes = s.settings.modes.map(::card),
            selected = mode.id,
            accent = s.settings.accent ?: mode.accent,
            tasks = if (chosenTask != null && titles.none { it.equals(chosenTask, true) }) listOf(chosenTask) + titles.take(HomeUi.MAX_CHIPS - 1) else titles,
            chosenTask = chosenTask?.takeIf { mode.ritual.intention != IntentionRule.Off },
            draft = draftText,
            resumeNote = s.lastResumeNote?.takeIf { mode.ritual.showResumeNote },
            resumeInk = s.lastResumeInk?.takeIf { mode.ritual.showResumeNote },
            done = s.completedToday,
            target = s.settings.dailyTarget,
            intention = mode.ritual.intention,
            interrupted = s.notice == Notice.Interrupted,
            ritual = mode.ritual.hasScreen && !s.skipsRitual(mode),
            ritualSkipped = s.skipsRitual(mode),
        )
    }

    private fun card(m: Mode) = ModeCard(
        id = m.id,
        name = m.name,
        accent = m.accent,
        focusMinutes = (m.rhythm as? Rhythm.Fixed)?.focus?.inWholeMinutes?.toInt(),
        breakMinutes = (m.rhythm as? Rhythm.Fixed)?.short?.inWholeMinutes?.toInt(),
        builtIn = m.builtIn,
    )

    companion object {
        fun factory(port: EnginePort): ViewModelProvider.Factory = viewModelFactory { initializer { HomeViewModel(port) } }
    }
}
