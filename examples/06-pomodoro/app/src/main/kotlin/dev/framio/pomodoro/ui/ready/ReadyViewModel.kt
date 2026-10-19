package dev.framio.pomodoro.ui.ready

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.framio.pomodoro.core.engine.FocusState
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.model.CopyTone
import dev.framio.pomodoro.core.model.Rhythm
import dev.framio.pomodoro.di.EnginePort
import dev.framio.pomodoro.ui.stateForUi
import kotlinx.coroutines.flow.map

@Immutable
data class ReadyUi(
    val modeName: String = "",
    val accent: Int = 0,
    val intention: String? = null,
    /** Null for Flow (counts up). */
    val nextMinutes: Int? = null,
    val done: Int = 0,
    val target: Int = 6,
    /** Gentle start: "Ten minutes done. That counts. Another 10?" (self-compassion, PLAN principle 10). */
    val warm: Boolean = false,
)

/** "Ready?": the break is over; a tap starts the next block with the same mode and intention. */
class ReadyViewModel(private val port: EnginePort) : ViewModel() {
    val ui = port.state.map(::map).stateForUi(viewModelScope, map(port.state.value))

    fun start() = port.send(Input.StartNext)

    fun home() = port.send(Input.Home)

    private fun map(s: FocusState) = ReadyUi(
        modeName = s.mode.name,
        accent = s.settings.accent ?: s.mode.accent,
        intention = s.run?.intent?.intention,
        nextMinutes = (s.mode.rhythm as? Rhythm.Fixed)?.focus?.inWholeMinutes?.toInt(),
        done = s.completedToday,
        target = s.settings.dailyTarget,
        warm = s.mode.environment.tone == CopyTone.Warm,
    )

    companion object {
        fun factory(port: EnginePort): ViewModelProvider.Factory = viewModelFactory { initializer { ReadyViewModel(port) } }
    }
}
