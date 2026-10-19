package dev.framio.pomodoro.ui.editor

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.framio.pomodoro.core.engine.FocusState
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.model.ChecklistItem
import dev.framio.pomodoro.core.model.CopyTone
import dev.framio.pomodoro.core.model.CueStyle
import dev.framio.pomodoro.core.model.Display
import dev.framio.pomodoro.core.model.Environment
import dev.framio.pomodoro.core.model.Face
import dev.framio.pomodoro.core.model.IntentionRule
import dev.framio.pomodoro.core.model.Mode
import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.ModeRules
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Rhythm
import dev.framio.pomodoro.core.model.Ritual
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.core.model.Soundscape
import dev.framio.pomodoro.core.model.SmitsTiers
import dev.framio.pomodoro.core.model.TouchPolicy
import dev.framio.pomodoro.di.EnginePort
import dev.framio.pomodoro.ui.stateForUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** The editor's three pages: when (rhythm), before (ritual), during (screen and sound). */
enum class EditorTab { Rhythm, Ritual, Screen }

/** What the keyboard sheet is typing for. */
enum class TextTarget { Name, Item }

@Immutable
data class EditorUi(
    /** Null while it isn't there yet (a fresh copy on its way through the engine) or once it is gone ([gone]). */
    val mode: Mode? = null,
    /** The mode was here and isn't any more (deleted): the screen closes. */
    val gone: Boolean = false,
    val tab: EditorTab = EditorTab.Rhythm,
    val typing: TextTarget? = null,
    val text: String = "",
    /** Why the last edit wasn't saved (the pickers keep values in range, so this is rare). */
    val problem: String? = null,
    /** Settings' accent override hides the mode's own accent everywhere. */
    val accentOverride: Int? = null,
    /** Settings' face override hides the mode's own face. */
    val faceOverride: Face? = null,
    /** Room for another mode (Duplicate). */
    val canDuplicate: Boolean = true,
    /** The ritual screen is skipped (Settings → Rituals or here); [canSkip] once it has run. */
    val skipRitual: Boolean = false,
    val canSkip: Boolean = false,
) {
    val readOnly: Boolean get() = mode?.builtIn != false
    val accent: Int get() = accentOverride ?: mode?.accent ?: 0
}

/**
 * The mode editor (PLAN §4, §5.1): built-in presets are shown read-only with "Duplicate & edit"; your own modes change
 * as you go, every edit saved at once (core `SaveMode`, validated by `ModeRules`; the pickers keep values in range).
 *
 * Edits apply to the editor's own copy of the mode first, so two quick changes never write over each other while the
 * engine is still applying the first.
 */
class ModeEditorViewModel(private val port: EnginePort, initial: ModeId) : ViewModel() {
    private data class Local(
        val id: ModeId,
        val draft: Mode? = null,
        val tab: EditorTab = EditorTab.Rhythm,
        val typing: TextTarget? = null,
        val text: String = "",
        val problem: String? = null,
    )

    /** The mode has been seen in the settings once: from then on, missing means deleted. */
    @Volatile private var seen = false

    private val local = MutableStateFlow(Local(initial))

    val ui = combine(port.state, local, ::map).stateForUi(viewModelScope, map(port.state.value, local.value))

    private fun current(s: FocusState = port.state.value, l: Local = local.value): Mode? =
        l.draft?.takeIf { it.id == l.id && s.settings.mode(l.id) != null } ?: s.settings.mode(l.id)

    private fun map(s: FocusState, l: Local): EditorUi {
        val mode = current(s, l)
        if (mode != null) seen = true
        val memory = mode?.let { s.ritual(it.id) }
        return EditorUi(
            mode = mode,
            gone = mode == null && seen,
            tab = l.tab,
            typing = l.typing,
            text = l.text,
            problem = l.problem,
            accentOverride = s.settings.accent,
            faceOverride = s.settings.face,
            canDuplicate = s.settings.modes.size < Settings.MAX_MODES,
            skipRitual = mode != null && s.skipsRitual(mode),
            canSkip = mode != null && mode.ritual.hasScreen && mode.ritual.skippable && (memory?.runs ?: 0) > 0,
        )
    }

    fun tab(tab: EditorTab) {
        local.value = local.value.copy(tab = tab)
    }

    // Copy

    /**
     * Saves a copy of this mode as one of your own ("Deep 2") and returns its id, for the screen to open; null when
     * there's no room (at most [Settings.MAX_MODES]).
     */
    fun duplicate(): ModeId? {
        val s = port.state.value
        val base = current(s) ?: return null
        if (s.settings.modes.size >= Settings.MAX_MODES) {
            local.value = local.value.copy(problem = FULL)
            return null
        }
        val copy = base.duplicate(ModeRules.newId(s.settings.modes), ModeRules.copyName(base.name, s.settings.modes))
        port.send(Input.SaveMode(copy))
        return copy.id
    }

    // Name and accent

    fun startName() = startTyping(TextTarget.Name, current()?.name.orEmpty())

    fun accent(index: Int) = edit { it.copy(accent = index) }

    // Rhythm

    fun fixed(on: Boolean) = edit { m ->
        val r = m.rhythm
        when {
            on && r is Rhythm.Flow -> m.copy(rhythm = Rhythm.Fixed(autoBreak = r.autoBreak, pauseCap = r.pauseCap))
            !on && r is Rhythm.Fixed -> m.copy(rhythm = Rhythm.Flow(tiers = SmitsTiers, autoBreak = r.autoBreak, pauseCap = r.pauseCap))
            else -> m
        }
    }

    fun focus(minutes: Int) = fixedEdit { it.copy(focus = minutes.minutes) }

    fun shortBreak(minutes: Int) = fixedEdit { it.copy(short = minutes.minutes) }

    fun longBreak(minutes: Int) = fixedEdit { it.copy(long = minutes.minutes) }

    /** 0 = never. */
    fun longEvery(blocks: Int) = fixedEdit { it.copy(longEvery = blocks.coerceIn(0, ModeRules.MAX_LONG_EVERY)) }

    fun autoBreak(on: Boolean) = edit { m ->
        m.copy(
            rhythm = when (val r = m.rhythm) {
                is Rhythm.Fixed -> r.copy(autoBreak = on)
                is Rhythm.Flow -> r.copy(autoBreak = on)
            },
        )
    }

    fun autoFocus(on: Boolean) = fixedEdit { it.copy(autoFocus = on) }

    /** Null = no cap. */
    fun pauseCap(minutes: Int?) = edit { m ->
        val cap = minutes?.minutes
        m.copy(
            rhythm = when (val r = m.rhythm) {
                is Rhythm.Fixed -> r.copy(pauseCap = cap)
                is Rhythm.Flow -> r.copy(pauseCap = cap)
            },
        )
    }

    fun nudge(on: Boolean) = flowEdit { it.copy(nudgeAfter = if (on) it.nudgeAfter ?: NUDGE else null) }

    fun nudgeAfter(minutes: Int) = flowEdit { it.copy(nudgeAfter = minutes.minutes) }

    // Ritual

    fun intention(rule: IntentionRule) = ritual { it.copy(intention = rule) }

    fun ifThen(on: Boolean) = ritual { it.copy(ifThen = on) }

    fun resumeNote(on: Boolean) = ritual { it.copy(showResumeNote = on) }

    /** The built-in reminders (phone, water, one window) come and go by toggle; your own ones by + and ×. */
    fun builtInItem(id: String, on: Boolean) = ritual { r ->
        val has = r.checklist.any { it.id == id }
        when {
            on && !has -> {
                val item = BuiltInItems.firstOrNull { it.id == id } ?: return@ritual r
                // Built-ins keep their place in front of your own items
                val order = BuiltInItems.map { it.id }
                val builtIns = (r.checklist.filter { it.id in order } + item).sortedBy { order.indexOf(it.id) }
                r.copy(checklist = builtIns + r.checklist.filter { it.id !in order })
            }
            !on && has -> r.copy(checklist = r.checklist.filter { it.id != id })
            else -> r
        }
    }

    fun startItem() = startTyping(TextTarget.Item, "")

    fun removeItem(id: String) = ritual { r -> r.copy(checklist = r.checklist.filter { it.id != id }) }

    /** Skips the ritual screen from now on (allowed once it has run once). */
    fun skipRitual(on: Boolean) {
        val mode = current() ?: return
        port.send(Input.SetRitualSkip(mode.id, on))
    }

    // Screen and sound

    fun display(display: Display) = environment { e ->
        when (display) {
            // A dark screen has no seconds and opens by a hold; Full may keep what you chose
            Display.Full -> e.copy(display = display)
            else -> e.copy(display = display, showSeconds = false, touch = e.touch.takeIf { it is TouchPolicy.HoldToReveal } ?: TouchPolicy.HoldToReveal())
        }
    }

    fun face(face: Face) = environment { it.copy(face = face) }

    fun seconds(on: Boolean) = environment { it.copy(showSeconds = on) }

    fun holdToOpen(on: Boolean) = environment { it.copy(touch = if (on) TouchPolicy.HoldToReveal() else TouchPolicy.Open) }

    fun knock(on: Boolean) = environment { it.copy(knockToPeek = on) }

    fun cues(style: CueStyle) = environment { it.copy(cues = style) }

    fun soundscape(soundscape: Soundscape?) = environment { it.copy(soundscape = soundscape) }

    /** Null = off. */
    fun checkIn(minutes: Int?) = environment { it.copy(checkIn = minutes?.minutes) }

    fun sunrise(on: Boolean) = environment { it.copy(sunrise = on) }

    fun warm(on: Boolean) = environment { it.copy(tone = if (on) CopyTone.Warm else CopyTone.Neutral) }

    // The keyboard sheet

    fun type(text: String) {
        val max = if (local.value.typing == TextTarget.Name) ModeRules.MAX_NAME else ModeRules.MAX_ITEM
        local.value = local.value.copy(text = text.take(max))
    }

    fun finishTyping() {
        val l = local.value
        val text = l.text.trim()
        local.value = l.copy(typing = null, text = "")
        if (text.isEmpty()) return
        when (l.typing) {
            TextTarget.Name -> edit { it.copy(name = text) }
            TextTarget.Item -> ritual { r ->
                if (r.checklist.size >= ModeRules.MAX_CHECKLIST) r else r.copy(checklist = r.checklist + ChecklistItem(ModeRules.newItemId(r.checklist), text))
            }
            null -> Unit
        }
    }

    fun cancelTyping() {
        local.value = local.value.copy(typing = null, text = "")
    }

    fun dismissProblem() {
        local.value = local.value.copy(problem = null)
    }

    private fun startTyping(target: TextTarget, text: String) {
        if (current()?.builtIn != false) return
        local.value = local.value.copy(typing = target, text = text)
    }

    private fun fixedEdit(f: (Rhythm.Fixed) -> Rhythm.Fixed) = edit { m -> (m.rhythm as? Rhythm.Fixed)?.let { m.copy(rhythm = f(it)) } ?: m }

    private fun flowEdit(f: (Rhythm.Flow) -> Rhythm.Flow) = edit { m -> (m.rhythm as? Rhythm.Flow)?.let { m.copy(rhythm = f(it)) } ?: m }

    private fun ritual(f: (Ritual) -> Ritual) = edit { it.copy(ritual = f(it.ritual)) }

    private fun environment(f: (Environment) -> Environment) = edit { it.copy(environment = f(it.environment)) }

    /** Applies [f] to the editor's copy and saves it; presets never change. */
    private fun edit(f: (Mode) -> Mode) {
        val base = current() ?: return
        if (base.builtIn) return
        val next = f(base)
        if (next == base) return
        val problem = ModeRules.problem(next)
        if (problem != null) {
            local.value = local.value.copy(problem = problem)
            return
        }
        local.value = local.value.copy(draft = next, problem = null)
        port.send(Input.SaveMode(next))
    }

    companion object {
        const val FULL = "full"
        val NUDGE: Duration = 90.minutes

        /** The preset's reminders, in their order (the screen shows translated labels for these ids). */
        val BuiltInItems: List<ChecklistItem> = Presets.deep.ritual.checklist

        fun factory(port: EnginePort, id: ModeId): ViewModelProvider.Factory =
            viewModelFactory { initializer { ModeEditorViewModel(port, id) } }
    }
}
