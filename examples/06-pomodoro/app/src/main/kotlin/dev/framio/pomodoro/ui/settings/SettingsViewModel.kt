package dev.framio.pomodoro.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.framio.pomodoro.core.engine.FocusState
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.model.Display
import dev.framio.pomodoro.core.model.Face
import dev.framio.pomodoro.core.model.Mode
import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.ModeRules
import dev.framio.pomodoro.core.model.Rhythm
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.core.model.Soundscape
import dev.framio.pomodoro.core.model.Theme
import dev.framio.pomodoro.core.model.TimerSize
import dev.framio.pomodoro.di.EnginePort
import dev.framio.pomodoro.ui.stateForUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalTime
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/** What Settings needs from the platform: the cue test, the soundscape preview, the export, the version. */
class SettingsHost(
    val playTest: (Float) -> Unit = {},
    val preview: (Soundscape, Float) -> Unit = { _, _ -> },
    /** Writes the export; returns where it went (or null if it failed). */
    val export: suspend () -> String? = { null },
    val version: String = "",
)

/** Settings, grouped (M5): left rail lying down, a chip row standing. */
enum class SettingsSection { Modes, Rituals, Deep, Sound, Appearance, Goals, Reasons, Data, About }

/** A mode in Settings → Modes. */
@Immutable
data class ModeRow(
    val id: ModeId,
    val name: String,
    val accent: Int,
    val builtIn: Boolean,
    val focusMinutes: Int?,
    val breakMinutes: Int?,
    val display: Display,
    val onHome: Boolean = true,
)

/** A mode whose ritual has a screen, in Settings → Rituals. */
@Immutable
data class RitualRow(val id: ModeId, val name: String, val skip: Boolean, val canSkip: Boolean)

/** The export's outcome line. */
@Immutable
sealed interface ExportState {
    data object Idle : ExportState
    data object Working : ExportState
    data class Done(val where: String) : ExportState
    data object Failed : ExportState
}

@Immutable
data class SettingsUi(
    val theme: Theme = Theme.Ink,
    val accentOverride: Int? = null,
    val face: Face? = null,
    val timerSize: TimerSize = TimerSize.Medium,
    val reduceMotion: Boolean = false,
    val evening: Boolean = true,
    val eveningStart: LocalTime = Settings.DEFAULT_EVENING,
    val eveningEnd: LocalTime = Settings.DEFAULT_MORNING,
    val systemKeyboard: Boolean = false,
    val volumePercent: Int = 60,
    val soundscapePercent: Int = 35,
    val target: Int = Settings.DEFAULT_TARGET,
    val modes: List<ModeRow> = emptyList(),
    val canAdd: Boolean = true,
    val rituals: List<RitualRow> = emptyList(),
    val parkedKept: Int = 0,
    /** A mode was just deleted: its name for the Undo bar. */
    val deleted: String? = null,
    /** Settings were just reset: the Undo bar. */
    val reset: Boolean = false,
    val export: ExportState = ExportState.Idle,
    val version: String = "",
)

/**
 * Settings (M5): modes (order, copy, delete with Undo), ritual screens, sound (cue volume and test, soundscape volume
 * and preview), appearance (theme, accent, face, timer size, reduce motion, evening amber, keyboard), the daily
 * target, data (export, reset with Undo), about. Every change is one `ChangeSettings` (or a mode input) at once.
 */
class SettingsViewModel(private val port: EnginePort, private val host: SettingsHost = SettingsHost()) : ViewModel() {
    /** Kept for the M2 call sites and tests: only the cue test. */
    constructor(port: EnginePort, playTest: (Float) -> Unit) : this(port, SettingsHost(playTest = playTest))

    private data class Local(
        val deleted: Pair<Mode, Int>? = null,
        val wasSelected: Boolean = false,
        val beforeReset: Settings? = null,
        val export: ExportState = ExportState.Idle,
    )

    private val local = MutableStateFlow(Local())
    private var undoTimer: Job? = null

    val ui = combine(port.state, local, ::map).stateForUi(viewModelScope, map(port.state.value, local.value))

    // Sound

    fun volume(delta: Int) = change { it.copy(soundVolume = step(it.soundVolume, delta)) }

    fun test() = host.playTest(port.state.value.settings.soundVolume)

    fun soundscapeVolume(delta: Int) = change { it.copy(soundscapeVolume = step(it.soundscapeVolume, delta)) }

    fun preview(soundscape: Soundscape) = host.preview(soundscape, port.state.value.settings.soundscapeVolume)

    // Appearance

    fun theme(theme: Theme) = change { it.copy(theme = theme) }

    fun accent(index: Int?) = change { it.copy(accent = index) }

    fun face(face: Face?) = change { it.copy(face = face) }

    fun timerSize(size: TimerSize) = change { it.copy(timerSize = size) }

    fun reduceMotion(on: Boolean) = change { it.copy(reduceMotion = on) }

    fun evening(on: Boolean) = change { it.copy(evening = on) }

    /** ± half an hour. */
    fun eveningStart(deltaMinutes: Int) = change { it.copy(eveningStart = it.eveningStart.plusMinutes(deltaMinutes.toLong())) }

    fun eveningEnd(deltaMinutes: Int) = change { it.copy(eveningEnd = it.eveningEnd.plusMinutes(deltaMinutes.toLong())) }

    fun systemKeyboard(on: Boolean) = change { it.copy(systemKeyboard = on) }

    // Goals

    fun target(delta: Int) = change { it.copy(dailyTarget = (it.dailyTarget + delta).coerceIn(1, Settings.MAX_TARGET)) }

    // Modes

    fun move(id: ModeId, by: Int) = port.send(Input.MoveMode(id, by))

    /** A copy of [id] as one of your own; its id, for the editor to open, or null when there's no room. */
    fun duplicate(id: ModeId): ModeId? {
        val s = port.state.value.settings
        val base = s.mode(id) ?: return null
        if (s.modes.size >= Settings.MAX_MODES) return null
        val copy = base.duplicate(ModeRules.newId(s.modes), ModeRules.copyName(base.name, s.modes))
        port.send(Input.SaveMode(copy))
        return copy.id
    }

    /** Deletes one of your own modes at once; Undo puts it back where it was for [UNDO]. */
    fun delete(id: ModeId) {
        val s = port.state.value.settings
        val index = s.modes.indexOfFirst { it.id == id }
        val mode = s.modes.getOrNull(index)?.takeIf { !it.builtIn } ?: return
        port.send(Input.DeleteMode(id))
        local.value = local.value.copy(deleted = mode to index, wasSelected = s.selectedMode == id)
        armUndo { local.value = local.value.copy(deleted = null) }
    }

    fun undoDelete() {
        val l = local.value
        val (mode, index) = l.deleted ?: return
        undoTimer?.cancel()
        local.value = l.copy(deleted = null)
        port.send(Input.SaveMode(mode))
        val now = port.state.value.settings.modes.indexOfFirst { it.id == mode.id }
        if (now >= 0 && now != index) port.send(Input.MoveMode(mode.id, index - now))
        if (l.wasSelected) port.send(Input.SelectMode(mode.id))
    }

    // Rituals

    fun skipRitual(id: ModeId, on: Boolean) = port.send(Input.SetRitualSkip(id, on))

    fun clearParked() = port.send(Input.ClearParked())

    // Data

    fun export() {
        if (local.value.export == ExportState.Working) return
        local.value = local.value.copy(export = ExportState.Working)
        viewModelScope.launch {
            val where = try {
                host.export()
            } catch (_: Exception) {
                null
            }
            local.value = local.value.copy(export = where?.let(ExportState::Done) ?: ExportState.Failed)
        }
    }

    /** Back to the defaults for everything but your modes, the selected mode and the order; Undo for [UNDO]. */
    fun reset() {
        val before = port.state.value.settings
        port.send(Input.ChangeSettings(Settings(modes = before.modes, selectedMode = before.selectedMode)))
        local.value = local.value.copy(beforeReset = before)
        armUndo { local.value = local.value.copy(beforeReset = null) }
    }

    fun undoReset() {
        val before = local.value.beforeReset ?: return
        undoTimer?.cancel()
        local.value = local.value.copy(beforeReset = null)
        // Modes may have changed meanwhile (the undo window is short, but be exact): keep today's modes
        val now = port.state.value.settings
        port.send(Input.ChangeSettings(before.copy(modes = now.modes, selectedMode = now.selectedMode)))
    }

    private fun armUndo(expire: () -> Unit) {
        undoTimer?.cancel()
        undoTimer = viewModelScope.launch {
            delay(UNDO)
            expire()
        }
    }

    private fun change(f: (Settings) -> Settings) = port.send(Input.ChangeSettings(f(port.state.value.settings)))

    private fun step(value: Float, delta: Int) = ((value * 100).roundToInt() + delta).coerceIn(0, 100) / 100f

    private fun map(s: FocusState, l: Local): SettingsUi {
        val set = s.settings
        return SettingsUi(
            theme = set.theme,
            accentOverride = set.accent,
            face = set.face,
            timerSize = set.timerSize,
            reduceMotion = set.reduceMotion,
            evening = set.evening,
            eveningStart = set.eveningStart,
            eveningEnd = set.eveningEnd,
            systemKeyboard = set.systemKeyboard,
            volumePercent = (set.soundVolume * 100).roundToInt(),
            soundscapePercent = (set.soundscapeVolume * 100).roundToInt(),
            target = set.dailyTarget,
            modes = set.modes.map(::row),
            canAdd = set.modes.size < Settings.MAX_MODES,
            rituals = set.modes.filter { it.ritual.hasScreen }.map { m ->
                RitualRow(m.id, m.name, s.skipsRitual(m), m.ritual.skippable && (s.ritual(m.id)?.runs ?: 0) > 0)
            },
            parkedKept = s.parkedLot.size,
            deleted = l.deleted?.first?.name,
            reset = l.beforeReset != null,
            export = l.export,
            version = host.version,
        )
    }

    private fun row(m: Mode) = ModeRow(
        id = m.id,
        name = m.name,
        accent = m.accent,
        builtIn = m.builtIn,
        focusMinutes = (m.rhythm as? Rhythm.Fixed)?.focus?.inWholeMinutes?.toInt(),
        breakMinutes = (m.rhythm as? Rhythm.Fixed)?.short?.inWholeMinutes?.toInt(),
        display = m.environment.display,
    )

    companion object {
        const val STEP = 10

        /** Half an hour, for the evening times. */
        const val TIME_STEP = 30
        val UNDO = 6.seconds

        fun factory(port: EnginePort, host: SettingsHost): ViewModelProvider.Factory =
            viewModelFactory { initializer { SettingsViewModel(port, host) } }
    }
}
