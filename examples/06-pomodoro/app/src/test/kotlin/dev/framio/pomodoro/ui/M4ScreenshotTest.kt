package dev.framio.pomodoro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.framio.pomodoro.FakePort
import dev.framio.pomodoro.core.engine.BreakPrompt
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.model.Display
import dev.framio.pomodoro.core.model.Face
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.core.model.Theme
import dev.framio.pomodoro.core.time.DayPart
import dev.framio.pomodoro.ui.breaks.BreakActions
import dev.framio.pomodoro.ui.breaks.BreakScreen
import dev.framio.pomodoro.ui.breaks.BreakUi
import dev.framio.pomodoro.ui.breaks.FlowTier
import dev.framio.pomodoro.ui.deep.DeepBackdrop
import dev.framio.pomodoro.ui.flow.FlowActions
import dev.framio.pomodoro.ui.flow.FlowScreen
import dev.framio.pomodoro.ui.flow.FlowUi
import dev.framio.pomodoro.ui.focus.FocusActions
import dev.framio.pomodoro.ui.focus.FocusScreen
import dev.framio.pomodoro.ui.focus.FocusUi
import dev.framio.pomodoro.ui.ready.ReadyScreen
import dev.framio.pomodoro.ui.ready.ReadyUi
import dev.framio.pomodoro.ui.theme.QuietTheme
import dev.framio.pomodoro.ui.why.Why
import dev.framio.pomodoro.ui.why.WhySheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Golden images of the M4 screens: the break landscape (Ink, Paper, Reduce motion at night, dawn standing), Flow focus
 * (Calm, its controls, the nudge), the Disc, Horizon and Digits faces, the Why? sheet and Gentle's Ready. The landscape's
 * clock is an infinite animation, which the test rule doesn't run: every shot is its first frame.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SCREENSHOT_SDK], qualifiers = LANDSCAPE)
class M4ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shot(theme: Theme = Theme.Ink, dark: Display? = null, content: @Composable () -> Unit) {
        compose.setContent {
            QuietTheme(theme = theme) {
                when (dark) {
                    null -> Box(Modifier.fillMaxSize().background(QuietTheme.colors.background)) { FitToDesign { content() } }
                    else -> DeepBackdrop(dark == Display.Blackout) { FitToDesign { content() } }
                }
            }
        }
        compose.onRoot().captureRoboImage()
    }

    private val brk = BreakUi(prompt = BreakPrompt.Window, pendingWrapUp = true, accent = 0, done = 4, target = 6, dayPart = DayPart.Day)

    @Composable
    private fun Break(ui: BreakUi) = BreakScreen(ui, left = { 3.minutes + 12.seconds }, progress = { 0.36f }, actions = BreakActions())

    @Test fun breakInk() = shot(Theme.Ink) { Break(brk) }

    @Test fun breakPaper() = shot(Theme.Paper) { Break(brk) }

    @Test fun breakReduceMotionNight() = shot(Theme.Ink) {
        Break(brk.copy(reduceMotion = true, dayPart = DayPart.Night, pendingWrapUp = false, prompt = BreakPrompt.Eyes))
    }

    @Test fun breakFlowDusk() = shot(Theme.Forest) {
        Break(brk.copy(dayPart = DayPart.Dusk, accent = 2, prompt = BreakPrompt.Move, pendingWrapUp = false, tier = FlowTier(37, 8)))
    }

    @Test fun breakLongDawn() = shot(Theme.Ink) {
        Break(brk.copy(dayPart = DayPart.Dawn, long = true, prompt = BreakPrompt.Messages, pendingWrapUp = false))
    }

    @Test @Config(qualifiers = PORTRAIT)
    fun breakPortrait() = shot(Theme.Ink) { Break(brk.copy(tier = FlowTier(37, 8), accent = 2)) }

    /** A real Flow block 37 minutes in, through the ViewModel's own state. */
    private fun flowUi(minutes: Int = 37, nudged: Boolean = false, controls: Boolean = false): Pair<FlowUi, FakePort> {
        val port = FakePort(settings = Settings(selectedMode = Presets.FLOW_ID))
        port.send(Input.Begin(intention = "Thesis ch.3"))
        port.clock.advance(minutes.minutes)
        val s = port.state.value
        val ui = FlowUi(
            modeName = "Flow", accent = 2, intention = "Thesis ch.3", display = Display.Calm, nudged = nudged,
            timing = port.timing(s), controls = controls,
        )
        return ui to port
    }

    @Test fun flowCalm() {
        val (ui, port) = flowUi()
        shot(dark = Display.Calm) { FlowScreen(ui, port::now, FlowActions()) }
    }

    @Test fun flowControls() {
        val (ui, port) = flowUi(controls = true)
        shot(dark = Display.Calm) { FlowScreen(ui, port::now, FlowActions()) }
    }

    @Test fun flowNudged() {
        val (ui, port) = flowUi(minutes = 92, nudged = true)
        shot(dark = Display.Calm) { FlowScreen(ui, port::now, FlowActions()) }
    }

    private val gentle = FocusUi(modeName = "Gentle start", accent = 3, face = Face.Disc, showSeconds = true)

    @Composable
    private fun Face(ui: FocusUi) = FocusScreen(ui, left = { 6.minutes + 40.seconds }, progress = { 0.333f }, actions = FocusActions())

    @Test fun faceDisc() = shot { Face(gentle) }

    @Test fun faceDiscPaper() = shot(Theme.Paper) { Face(gentle) }

    @Test fun faceHorizon() = shot { Face(gentle.copy(face = Face.Horizon, modeName = "Classic", accent = 0, intention = "Review PR")) }

    @Test fun faceDigits() = shot { Face(gentle.copy(face = Face.Digits, modeName = "Classic", accent = 0)) }

    @Test @Config(qualifiers = PORTRAIT)
    fun faceDiscPortrait() = shot { Face(gentle) }

    @Test @Config(qualifiers = PORTRAIT)
    fun faceHorizonPortrait() = shot { Face(gentle.copy(face = Face.Horizon)) }

    @Test fun whyBreak() = shot { WhySheet(Why.Break, onClose = {}) }

    @Test fun whyTiersPaper() = shot(Theme.Paper) { WhySheet(Why.Tiers, onClose = {}) }

    @Test @Config(qualifiers = PORTRAIT)
    fun whyRitualPortrait() = shot { WhySheet(Why.Ritual, onClose = {}) }

    private val ready = ReadyUi(modeName = "Gentle start", accent = 3, nextMinutes = 10, done = 1, target = 6, warm = true)

    @Test fun readyGentle() = shot { ReadyScreen(ready, onStart = {}, onHome = {}) }

    @Test fun readyGentlePaper() = shot(Theme.Paper) { ReadyScreen(ready, onStart = {}, onHome = {}) }
}
