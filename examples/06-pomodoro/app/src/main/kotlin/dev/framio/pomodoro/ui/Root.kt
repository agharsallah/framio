package dev.framio.pomodoro.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.framio.pomodoro.core.engine.FocusState
import dev.framio.pomodoro.core.engine.Phase
import dev.framio.pomodoro.core.model.Display
import dev.framio.pomodoro.core.model.Rhythm
import dev.framio.pomodoro.core.model.Theme
import dev.framio.pomodoro.core.model.TimerSize
import dev.framio.pomodoro.ui.settings.SettingsHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import androidx.compose.runtime.mutableFloatStateOf
import dev.framio.pomodoro.di.DeepHardware
import dev.framio.pomodoro.di.EnginePort
import dev.framio.pomodoro.platform.Logs
import dev.framio.pomodoro.data.HistoryPort
import dev.framio.pomodoro.data.NoHistory
import dev.framio.pomodoro.ui.breaks.BreakRoute
import dev.framio.pomodoro.ui.history.HistoryRoute
import dev.framio.pomodoro.ui.insights.InsightsRoute
import dev.framio.pomodoro.ui.deep.CalmBackground
import dev.framio.pomodoro.ui.deep.DeepFocusRoute
import dev.framio.pomodoro.ui.flow.FlowRoute
import dev.framio.pomodoro.ui.focus.FocusRoute
import dev.framio.pomodoro.ui.home.HomeRoute
import dev.framio.pomodoro.ui.ready.ReadyRoute
import dev.framio.pomodoro.ui.ritual.RitualRoute
import dev.framio.pomodoro.ui.settings.SettingsRoute
import dev.framio.pomodoro.ui.theme.QuietMotion
import dev.framio.pomodoro.ui.theme.QuietTheme
import dev.framio.pomodoro.ui.wrapup.WrapUpRoute

/**
 * Which screen a phase shows. Focus and Paused are one screen: Flow's (counts up) for a Flow rhythm, else Deep's for
 * a Calm or Blackout mode, else the Full face.
 */
enum class Screen { Loading, Home, Ritual, Focus, Deep, Flow, WrapUp, Break, Ready }

/** What sits over Home: Settings, History or Insights (one at a time; Home only). */
enum class Overlay { None, Settings, History, Insights }

/** How dark the whole screen is: the root background follows it (Begin's "the background deepens"). */
enum class Look { Normal, Calm, Blackout }

fun screenOf(s: FocusState): Screen = if (!s.loaded) {
    Screen.Loading
} else {
    when (s.phase) {
        Phase.Idle -> Screen.Home
        is Phase.Ritual -> Screen.Ritual
        is Phase.Focus, is Phase.Paused -> when {
            s.mode.rhythm is Rhythm.Flow -> Screen.Flow
            s.mode.environment.display == Display.Full -> Screen.Focus
            else -> Screen.Deep
        }
        is Phase.WrapUp -> Screen.WrapUp
        is Phase.Break -> Screen.Break
        Phase.Ready -> Screen.Ready
    }
}

fun lookOf(s: FocusState): Look = if (s.loaded && s.phase is Phase.Focus) {
    when (s.mode.environment.display) {
        Display.Full -> Look.Normal
        Display.Calm -> Look.Calm
        Display.Blackout -> Look.Blackout
    }
} else {
    Look.Normal
}

@Immutable
data class RootUi(
    val screen: Screen = Screen.Loading,
    val theme: Theme = Theme.Ink,
    val reduceMotion: Boolean = false,
    val look: Look = Look.Normal,
    /** Evening amber (M5): from the evening start until morning, with a trusted clock only. */
    val evening: Boolean = false,
    val timerSize: TimerSize = TimerSize.Medium,
    val systemKeyboard: Boolean = false,
)

class RootViewModel(port: EnginePort, evening: StateFlow<Boolean> = MutableStateFlow(false)) : ViewModel() {
    private fun map(s: FocusState, amber: Boolean) =
        RootUi(screenOf(s), s.settings.theme, s.settings.reduceMotion, lookOf(s), amber, s.settings.timerSize, s.settings.systemKeyboard)

    val ui = combine(port.state, evening, ::map).stateForUi(viewModelScope, map(port.state.value, evening.value))

    companion object {
        fun factory(port: EnginePort, evening: StateFlow<Boolean>): ViewModelProvider.Factory =
            viewModelFactory { initializer { RootViewModel(port, evening) } }
    }
}

/**
 * Evening amber arrives (and leaves) once a day: the palette steps from the theme to amber over [AMBER_MS] in
 * [AMBER_STEPS] steps. Each step recomposes the tree (the colours are a static local), so few steps, slowly, and never
 * while nothing changes; Reduce motion makes it one step. On start-up it is simply what it is.
 */
private const val AMBER_MS = 4_000L
private const val AMBER_STEPS = 16

private val NoEvening: StateFlow<Boolean> = MutableStateFlow(false)

/** Whether this process has logged Home's first frame (`EVENT|app|home on screen`). */
private var homeShown = false

/** 1/255: no visible pixel changes, yet HWUI draws the layer (it skips alpha 0). */
private const val PREWARM_ALPHA = 0.004f

private val PreFocus = setOf(Screen.Home, Screen.Ritual, Screen.Ready)
private val InFocus = setOf(Screen.Focus, Screen.Deep, Screen.Flow)

/** How one screen change moves: the outgoing fade, then the incoming one (never both at once), and Begin's breath. */
private class Move(val exitMs: Int, val enterMs: Int, val breathMs: Int)

private fun moveOf(from: Screen, to: Screen, motion: QuietMotion): Move = when {
    // Start-up: Loading is an empty screen, so the first real one is simply there (no fade to wait for, LOG M6)
    from == Screen.Loading -> Move(0, 0, 0)
    motion.reduce -> Move(motion.quick / 2, motion.quick, 0)
    // Begin: "breathe in" (PLAN §5.2), 3 s in all: the room goes quiet, then the timer arrives and settles
    from in PreFocus && to in InFocus -> Move(400, 1_200, motion.breathe - 400)
    // Deep's sunrise into the wrap-up: slower, with the display rising underneath
    from == Screen.Deep && to == Screen.WrapUp -> Move(600, 1_500, 0)
    else -> Move(motion.screen / 2, motion.screen, 0)
}

/**
 * The whole app: one phase-driven AnimatedContent, no navigation library (PLAN §6.1). Settings is an overlay over
 * Home.
 *
 * Cost on the Mali-400 (LOG M3): only the root draws a background (screens are transparent, so a fade never blends
 * two full-screen fills), and it is read in the draw phase. Screens fade with a draw-phase alpha applied per draw call
 * (no offscreen layer), **one after the other**: the incoming screen starts when the outgoing one is gone, and a
 * layer at alpha 0 isn't drawn, so no frame ever draws two screens. Begin's breath is a scale on the timer's layer
 * only.
 */
@Composable
fun PomodoroRoot(
    port: EnginePort,
    playTest: (Float) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    hardware: DeepHardware = DeepHardware.None,
    history: HistoryPort = NoHistory,
    settingsHost: SettingsHost = SettingsHost(playTest = playTest),
    evening: StateFlow<Boolean> = NoEvening,
    vm: RootViewModel = viewModel(factory = RootViewModel.factory(port, evening)),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var overlay by rememberSaveable { mutableStateOf(Overlay.None) }
    val today = remember(port) { { port.now().let { n -> java.time.Instant.ofEpochMilli(n.wallMs ?: System.currentTimeMillis()).atZone(n.zone).toLocalDate() } } }
    var amber by remember { mutableFloatStateOf(if (ui.evening) 1f else 0f) }
    LaunchedEffect(ui.evening, ui.reduceMotion) {
        val from = amber
        val to = if (ui.evening) 1f else 0f
        if (from == to) return@LaunchedEffect
        val steps = if (ui.reduceMotion) 1 else AMBER_STEPS
        for (i in 1..steps) {
            amber = from + (to - from) * i / steps
            if (i < steps) delay(AMBER_MS / steps)
        }
    }
    QuietTheme(theme = ui.theme, reduceMotion = ui.reduceMotion, amber = amber, timerSize = ui.timerSize, systemKeyboard = ui.systemKeyboard) {
        val motion = QuietTheme.motion
        // "The background deepens": a near-black layer over the theme's background whose alpha animates. Only a layer
        // property changes per frame; animating the root's own colour re-recorded the root on every frame (LOG M3).
        val night = remember { Animatable(if (ui.look == Look.Normal) 0f else 1f) }
        var nightColor by remember { mutableStateOf(if (ui.look == Look.Blackout) Color.Black else CalmBackground) }
        LaunchedEffect(ui.look) {
            if (ui.look != Look.Normal) nightColor = if (ui.look == Look.Blackout) Color.Black else CalmBackground
            val to = if (ui.look == Look.Normal) 0f else 1f
            val ms = when {
                motion.reduce -> motion.quick
                to == 1f -> motion.breathe
                else -> 2_000 // a sunrise, or a pause
            }
            night.animateTo(to, tween(ms, easing = FastOutSlowInEasing))
        }
        if (ui.screen == Screen.Home && !homeShown) {
            // For the cold-start measurements: logged once per process, a frame after Home is first composed
            LaunchedEffect(Unit) {
                withFrameNanos { }
                homeShown = true
                Logs.event("app", "home on screen")
            }
        }
        val phase = updateTransition(ui.screen, label = "phase")
        val background = QuietTheme.colors.background
        Box(
            modifier.fillMaxSize().drawBehind {
                // The break's landscape covers every pixel: once it is settled, no fill under it (on this GPU a
                // full-screen fill is about a tenth of a frame, LOG M4)
                if (phase.currentState != Screen.Break || phase.targetState != Screen.Break) drawRect(background)
            },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = night.value // at 0 the layer isn't drawn at all
                        // One rect: alpha on the draw call. With the default strategy HWUI rendered it through a
                        // full-screen offscreen layer on every frame of the breath (atrace, LOG M3)
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .drawBehind { drawRect(nightColor) },
            )
            FitToDesign {
                phase.AnimatedContent(transitionSpec = { EnterTransition.None togetherWith ExitTransition.None }) { screen ->
                    val move = moveOf(phase.segment.initialState, phase.segment.targetState, motion)
                    val alpha by transition.animateFloat(
                        transitionSpec = {
                            if (targetState == EnterExitState.Visible) {
                                tween(move.enterMs, delayMillis = move.exitMs, easing = LinearOutSlowInEasing)
                            } else {
                                tween(move.exitMs, easing = FastOutSlowInEasing)
                            }
                        },
                        label = "fade",
                    ) {
                        when (it) {
                            EnterExitState.Visible -> 1f
                            // Invisible, but drawn: the incoming screen's first-draw work (glyphs, path textures) lands
                            // in the first frame, which composes it anyway, not mid-fade (LOG M3)
                            EnterExitState.PreEnter -> PREWARM_ALPHA
                            EnterExitState.PostExit -> 0f
                        }
                    }
                    val breathing = transition.animateFloat(
                        transitionSpec = {
                            if (targetState == EnterExitState.Visible && move.breathMs > 0) {
                                tween(move.breathMs, delayMillis = move.exitMs, easing = FastOutSlowInEasing)
                            } else {
                                snap()
                            }
                        },
                        label = "breath",
                    ) { if (it == EnterExitState.Visible) 1f else 0f }
                    val breath = { if (transition.targetState == EnterExitState.Visible) breathing.value else 1f }
                    // The outgoing screen no longer changes: drawn once into a texture, its fade is one textured quad
                    // per frame. Fading it op by op (ModulateAlpha) re-issued every chip and text with alpha on every
                    // frame: all the Begin jank sat in that first half second (LOG M3). The incoming screen changes
                    // (the timer breathes, ticks) so it keeps the per-op alpha, which needs no buffer.
                    val exiting = transition.targetState == EnterExitState.PostExit
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            this.alpha = alpha
                            compositingStrategy = if (exiting) CompositingStrategy.Offscreen else CompositingStrategy.ModulateAlpha
                        },
                    ) {
                        when (screen) {
                            Screen.Loading -> Box(Modifier.fillMaxSize())
                            Screen.Home -> HomeRoute(
                                port, onSettings = { overlay = Overlay.Settings }, onExit = onExit,
                                onHistory = { overlay = Overlay.History }, onInsights = { overlay = Overlay.Insights },
                            )
                            Screen.Ritual -> RitualRoute(port)
                            Screen.Focus -> FocusRoute(port, breath = breath, history = history)
                            Screen.Deep -> DeepFocusRoute(port, hardware, breath = breath, history = history)
                            Screen.Flow -> FlowRoute(port, hardware, breath = breath)
                            Screen.WrapUp -> WrapUpRoute(port, history = history)
                            Screen.Break -> BreakRoute(port, history = history)
                            Screen.Ready -> ReadyRoute(port)
                        }
                    }
                }
                if (ui.screen == Screen.Home) {
                    when (overlay) {
                        Overlay.None -> Unit
                        Overlay.Settings -> SettingsRoute(port, playTest, onClose = { overlay = Overlay.None }, history = history, host = settingsHost)
                        Overlay.History -> HistoryRoute(history, today, onClose = { overlay = Overlay.None }, onInsights = { overlay = Overlay.Insights })
                        Overlay.Insights -> InsightsRoute(history, port, today, onClose = { overlay = Overlay.None }, onHistory = { overlay = Overlay.History })
                    }
                }
            }
        }
    }
}
