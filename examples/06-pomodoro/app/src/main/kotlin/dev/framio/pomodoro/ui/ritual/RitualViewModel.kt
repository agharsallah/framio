package dev.framio.pomodoro.ui.ritual

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.framio.pomodoro.core.engine.FocusState
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.engine.Phase
import dev.framio.pomodoro.core.model.Display
import dev.framio.pomodoro.core.model.IntentionRule
import dev.framio.pomodoro.core.model.Mode
import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Rhythm
import dev.framio.pomodoro.core.model.Task
import dev.framio.pomodoro.di.EnginePort
import dev.framio.pomodoro.ui.home.HomeUi
import dev.framio.pomodoro.ui.stateForUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/** A checklist box. [label] is null for the built-in ids (phone, water, one_window): the screen translates those. */
@Immutable
data class CheckItem(val id: String, val label: String?, val checked: Boolean)

@Immutable
data class RitualUi(
    val modeId: ModeId = Presets.DEEP_ID,
    val modeName: String = "",
    val accent: Int = 0,
    val intention: IntentionRule = IntentionRule.Optional,
    val tasks: List<String> = emptyList(),
    val chosen: String? = null,
    val draft: String? = null,
    val ifThenOn: Boolean = false,
    val ifThen: String = "",
    /** The if-then line is being typed (the keyboard sheet is open). */
    val typingIfThen: Boolean = false,
    val checklist: List<CheckItem> = emptyList(),
    val resumeNote: String? = null,
    val resumeInk: String? = null,
    /** Parked thoughts kept from earlier blocks (ink). */
    val parked: List<String> = emptyList(),
    /** A Calm/Blackout mode: the screen choice is offered. */
    val darkChoice: Boolean = false,
    val blackout: Boolean = false,
    val focusMinutes: Int? = null,
    val breakMinutes: Int? = null,
) {
    val canBegin: Boolean get() = intention != IntentionRule.Required || chosen != null
}

/**
 * The ritual (PLAN §4.1, principles 2 and 5): what you'll work on (required in Deep), an optional if-then plan, the
 * checklist (a reminder, never a gate), the last resume note, and Calm or screen off. The ticks, the if-then line and
 * the screen choice are remembered per mode (core RitualMemory, written on Begin).
 *
 * The edits are this screen's own state, seeded from the engine whenever a new ritual opens (the phase changes), so a
 * stale edit from an earlier ritual never shows.
 */
class RitualViewModel(private val port: EnginePort) : ViewModel() {
    private data class Local(
        val forPhase: Phase.Ritual? = null,
        val chosen: String? = null,
        val draft: String? = null,
        val ifThen: String = "",
        val ticks: Set<String> = emptySet(),
        val blackout: Boolean = false,
        /** The if-then line before typing started (Cancel puts it back); null when not typing. */
        val ifThenBefore: String? = null,
    )

    private val local = MutableStateFlow(Local())

    val ui = combine(port.state, local) { s, l -> map(s, current(s, l)) }
        .stateForUi(viewModelScope, port.state.value.let { map(it, current(it, Local())) })

    fun chooseTask(title: String) = edit { it.copy(chosen = if (it.chosen.equals(title, ignoreCase = true)) null else title) }

    fun openDraft() = edit { it.copy(draft = "") }

    fun editDraft(text: String) = edit { it.copy(draft = text.take(Task.MAX_TITLE)) }

    fun cancelDraft() = edit { it.copy(draft = null) }

    fun addDraft() {
        val title = current(port.state.value, local.value).draft?.trim().orEmpty()
        edit { it.copy(draft = null, chosen = title.ifEmpty { it.chosen }) }
        if (title.isNotEmpty()) port.send(Input.AddTask(title))
    }

    fun editIfThen(text: String) = edit { it.copy(ifThen = text.take(MAX_IF_THEN)) }

    fun openIfThen() = edit { it.copy(ifThenBefore = it.ifThen) }

    fun doneIfThen() = edit { it.copy(ifThen = it.ifThen.trim(), ifThenBefore = null) }

    fun cancelIfThen() = edit { it.copy(ifThen = it.ifThenBefore ?: it.ifThen, ifThenBefore = null) }

    fun toggle(id: String, on: Boolean) = edit { it.copy(ticks = if (on) it.ticks + id else it.ticks - id) }

    fun setBlackout(on: Boolean) = edit { it.copy(blackout = on) }

    fun clearParked() = port.send(Input.ClearParked())

    /** Begin: the "breathe in" follows in the root. Does nothing until the intention rule is met. */
    fun begin() {
        val s = port.state.value
        val l = current(s, local.value)
        val u = map(s, l)
        if (!u.canBegin) return
        port.send(
            Input.Begin(
                intention = l.chosen,
                ifThen = l.ifThen.takeIf { u.ifThenOn },
                checked = l.ticks,
                display = if (!u.darkChoice) null else if (l.blackout) Display.Blackout else Display.Calm,
            ),
        )
    }

    fun back() = port.send(Input.CancelRitual)

    private fun edit(f: (Local) -> Local) {
        local.value = f(current(port.state.value, local.value))
    }

    private fun modeOf(s: FocusState, p: Phase.Ritual?): Mode {
        val id = p?.modeId ?: return s.mode
        return s.run?.mode?.takeIf { it.id == id } ?: s.settings.mode(id) ?: s.mode
    }

    private fun current(s: FocusState, l: Local): Local {
        val p = s.phase as? Phase.Ritual ?: return l
        if (l.forPhase == p) return l
        val mode = modeOf(s, p)
        val memory = s.ritual(mode.id)
        return Local(
            forPhase = p,
            chosen = p.intention ?: s.run?.intent?.intention,
            ifThen = memory?.ifThen.orEmpty(),
            ticks = memory?.ticks.orEmpty().toSet(),
            blackout = (memory?.display ?: mode.environment.display) == Display.Blackout,
        )
    }

    private fun map(s: FocusState, l: Local): RitualUi {
        val p = s.phase as? Phase.Ritual
        val mode = modeOf(s, p)
        val ritual = mode.ritual
        val preset = s.settings.mode(mode.id) ?: mode
        val titles = s.tasks.take(HomeUi.MAX_CHIPS).map { it.title }
        val chosen = l.chosen
        return RitualUi(
            modeId = mode.id,
            modeName = mode.name,
            accent = s.settings.accent ?: mode.accent,
            intention = ritual.intention,
            tasks = if (chosen != null && titles.none { it.equals(chosen, true) }) listOf(chosen) + titles.take(HomeUi.MAX_CHIPS - 1) else titles,
            chosen = chosen?.takeIf { ritual.intention != IntentionRule.Off },
            draft = l.draft,
            ifThenOn = ritual.ifThen,
            ifThen = l.ifThen,
            typingIfThen = l.ifThenBefore != null,
            checklist = ritual.checklist.map { CheckItem(it.id, it.label.takeIf { _ -> it.id !in BUILT_IN }, it.id in l.ticks) },
            resumeNote = s.lastResumeNote?.takeIf { ritual.showResumeNote },
            resumeInk = s.lastResumeInk?.takeIf { ritual.showResumeNote },
            parked = s.parkedLot.map { it.ink },
            darkChoice = preset.environment.display != Display.Full,
            blackout = l.blackout,
            focusMinutes = (mode.rhythm as? Rhythm.Fixed)?.focus?.inWholeMinutes?.toInt(),
            breakMinutes = (mode.rhythm as? Rhythm.Fixed)?.short?.inWholeMinutes?.toInt(),
        )
    }

    companion object {
        const val MAX_IF_THEN = 120
        private val BUILT_IN = setOf(Presets.CHECK_PHONE, Presets.CHECK_WATER, Presets.CHECK_ONE_WINDOW)

        fun factory(port: EnginePort): ViewModelProvider.Factory = viewModelFactory { initializer { RitualViewModel(port) } }
    }
}
