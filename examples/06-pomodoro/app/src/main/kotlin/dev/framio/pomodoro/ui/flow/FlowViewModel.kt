package dev.framio.pomodoro.ui.flow

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
import dev.framio.pomodoro.core.model.Rhythm
import dev.framio.pomodoro.core.model.SmitsTiers
import dev.framio.pomodoro.core.rhythm.FlowRhythm
import dev.framio.pomodoro.core.time.Now
import dev.framio.pomodoro.di.DeepHardware
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

/** What Flow focus shows: the elapsed time only, and Stop / Pause a tap away. */
@Immutable
data class FlowUi(
    val modeName: String = "",
    val accent: Int = 0,
    val intention: String? = null,
    val display: Display = Display.Calm,
    val paused: Boolean = false,
    /** 90 minutes in (the mode's nudge): one glow and "A real break helps", never a sound. */
    val nudged: Boolean = false,
    /** A minute on the display, in real time (a second in debug "minutes as seconds" runs). */
    val tick: Duration = 1.minutes,
    val reduceMotion: Boolean = false,
    val timing: FaceTiming = FaceTiming.None,
    val rhythm: Rhythm.Flow = Rhythm.Flow(),
    /** Stop · Pause, shown for a few seconds after a tap (and lighting a dark display meanwhile). */
    val controls: Boolean = false,
) {
    /** Dark (Calm or Blackout) while focusing; paused, the display is normal again. */
    val dark: Boolean get() = display != Display.Full && !paused

    /** Blackout: nothing is drawn until a tap lights the display. */
    val hidden: Boolean get() = display == Display.Blackout && !paused && !controls

    /** The break stopping now would earn (Smits tiers), in whole minutes. */
    fun breakIfStopped(now: Now): Int? = timing.focused(now)?.let { FlowRhythm.breakFor(rhythm, it).inWholeMinutes.toInt() }
}

/**
 * Flow focus (PLAN §4): counts up; you stop at a natural point and the break follows from how long you worked. Calm
 * by default: the elapsed minutes only, once a minute. A tap shows Stop and Pause (with the break that stopping now
 * earns) and lifts a dark display while they show.
 */
class FlowViewModel(private val port: EnginePort, private val hw: DeepHardware) : ViewModel() {
    private val controls = MutableStateFlow(false)
    private var hide: Job? = null

    val ui = combine(port.state, controls, ::map).stateForUi(viewModelScope, map(port.state.value, false))

    init {
        viewModelScope.launch { controls.collect(hw::light) }
    }

    fun now(): Now = port.now()

    /** A tap: Stop · Pause for [CONTROLS_FOR], or hide them. */
    fun tap() {
        hide?.cancel()
        controls.value = !controls.value
        if (controls.value) {
            hide = viewModelScope.launch {
                delay(CONTROLS_FOR)
                controls.value = false
            }
        }
    }

    /** Stop at a natural point: the break follows from the tiers (and the wrap-up card is on it). */
    fun stop() = act(Input.Complete)

    fun pause() = act(Input.Pause)

    fun resume() = act(Input.Resume)

    /** The screen went away: nothing left lit or open. */
    fun leave() {
        hide?.cancel()
        controls.value = false
        hw.light(false)
    }

    override fun onCleared() = hw.light(false)

    private fun act(input: Input) {
        hide?.cancel()
        controls.value = false
        port.send(input)
    }

    private fun map(s: FocusState, open: Boolean): FlowUi {
        val mode = s.mode
        val paused = s.phase is Phase.Paused
        return FlowUi(
            modeName = mode.name,
            accent = s.settings.accent ?: mode.accent,
            intention = s.run?.intent?.intention,
            display = mode.environment.display,
            paused = paused,
            nudged = s.session?.nudged == true,
            tick = port.config.real(1.minutes),
            reduceMotion = s.settings.reduceMotion,
            timing = port.timing(s),
            rhythm = mode.rhythm as? Rhythm.Flow ?: Rhythm.Flow(SmitsTiers),
            controls = open || paused,
        )
    }

    companion object {
        val CONTROLS_FOR: Duration = 8.seconds

        fun factory(port: EnginePort, hw: DeepHardware): ViewModelProvider.Factory =
            viewModelFactory { initializer { FlowViewModel(port, hw) } }
    }
}
