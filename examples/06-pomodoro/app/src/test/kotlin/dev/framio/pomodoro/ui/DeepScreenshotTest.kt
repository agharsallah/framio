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
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.model.Display
import dev.framio.pomodoro.core.model.InkCodec
import dev.framio.pomodoro.core.model.IntentionRule
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.core.model.Stroke
import dev.framio.pomodoro.core.model.Theme
import dev.framio.pomodoro.ui.deep.DeepActions
import dev.framio.pomodoro.ui.deep.DeepBackdrop
import dev.framio.pomodoro.ui.deep.DeepFocusScreen
import dev.framio.pomodoro.ui.deep.DeepUi
import dev.framio.pomodoro.ui.deep.ParkPad
import dev.framio.pomodoro.ui.ritual.CheckItem
import dev.framio.pomodoro.ui.ritual.RitualActions
import dev.framio.pomodoro.ui.ritual.RitualScreen
import dev.framio.pomodoro.ui.ritual.RitualUi
import dev.framio.pomodoro.ui.theme.QuietTheme
import dev.framio.pomodoro.ui.wrapup.WrapUpActions
import dev.framio.pomodoro.ui.wrapup.WrapUpScreen
import dev.framio.pomodoro.ui.wrapup.WrapUpUi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration.Companion.minutes

/** Golden images of the M3 (Deep) screens in Ink and Paper, at 1280×800, plus the ritual standing. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SCREENSHOT_SDK], qualifiers = LANDSCAPE)
class DeepScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shot(theme: Theme, dark: Display? = null, content: @Composable () -> Unit) {
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

    /** A scribbled loop and a line: a stand-in for a handwritten word. */
    private val ink: String = InkCodec.encode(
        listOf(
            Stroke(FloatArray(40) { i -> val t = (i / 2) / 19f * 2 * PI.toFloat(); if (i % 2 == 0) 0.3f + 0.15f * cos(t) else 0.5f + 0.3f * sin(t) }),
            Stroke(floatArrayOf(0.55f, 0.2f, 0.62f, 0.8f, 0.7f, 0.3f, 0.82f, 0.75f)),
        ),
    )

    private val ritual = RitualUi(
        modeId = Presets.DEEP_ID,
        modeName = "Deep",
        accent = 1,
        intention = IntentionRule.Required,
        tasks = listOf("Thesis ch.3", "Review PR", "Taxes"),
        chosen = "Thesis ch.3",
        ifThenOn = true,
        ifThen = "write it on the pad and go on",
        checklist = listOf(CheckItem("phone", null, true), CheckItem("water", null, true), CheckItem("one_window", null, false)),
        resumeNote = "rewrite §3.2 intro, then figures",
        parked = listOf(ink),
        darkChoice = true,
        blackout = false,
        focusMinutes = 50,
        breakMinutes = 10,
    )

    @Test fun ritualInk() = shot(Theme.Ink) { RitualScreen(ritual, RitualActions()) }

    @Test fun ritualPaper() = shot(Theme.Paper) { RitualScreen(ritual, RitualActions()) }

    @Test @Config(qualifiers = PORTRAIT)
    fun ritualPortrait() = shot(Theme.Ink) { RitualScreen(ritual, RitualActions()) }

    /** Deep focus 12 minutes into a 50-minute block: 38 min left. */
    private fun deep(display: Display = Display.Calm): Pair<FakePort, DeepUi> {
        val port = FakePort(settings = Settings(selectedMode = Presets.DEEP_ID))
        port.send(Input.Begin("Thesis ch.3", display = display))
        port.clock.advance(12.minutes)
        val ui = DeepUi(modeName = "Deep", accent = 1, intention = "Thesis ch.3", blackout = display == Display.Blackout, knock = true, timing = port.timing(port.state.value))
        return port to ui
    }

    @Composable
    private fun Deep(port: FakePort, ui: DeepUi) = DeepFocusScreen(ui, port::now, { null }, DeepActions())

    @Test fun calmInk() = deep().let { (p, ui) -> shot(Theme.Ink, Display.Calm) { Deep(p, ui) } }

    @Test fun calmPaper() = deep().let { (p, ui) -> shot(Theme.Paper, Display.Calm) { Deep(p, ui) } }

    @Test @Config(qualifiers = PORTRAIT)
    fun calmPortrait() = deep().let { (p, ui) -> shot(Theme.Ink, Display.Calm) { Deep(p, ui) } }

    @Test fun holdPanelInk() = deep().let { (p, ui) -> shot(Theme.Ink, Display.Calm) { Deep(p, ui.copy(panel = true)) } }

    @Test fun holdPanelPaper() = deep().let { (p, ui) -> shot(Theme.Paper, Display.Calm) { Deep(p, ui.copy(panel = true)) } }

    @Test fun blackoutPeek() = deep(Display.Blackout).let { (p, ui) -> shot(Theme.Ink, Display.Blackout) { Deep(p, ui.copy(peeking = true)) } }

    @Test fun pausedDeep() = deep().let { (p, ui) -> shot(Theme.Ink) { Deep(p, ui.copy(paused = true, overCap = true)) } }

    @Test fun endNote() = deep().let { (p, ui) -> shot(Theme.Ink, Display.Calm) { Deep(p, ui.copy(end = dev.framio.pomodoro.ui.notes.EndDraft())) } }

    @Test fun parkPadInk() = shot(Theme.Ink) { ParkPad(QuietTheme.colors.accent(1), {}, {}) }

    @Test fun parkPadPaper() = shot(Theme.Paper) { ParkPad(QuietTheme.colors.accent(1), {}, {}) }

    private val wrap = WrapUpUi(accent = 1, breakMinutes = 10, intention = "Thesis ch.3", outcome = Outcome.Partly, rating = 4, note = "figures for §3.2", parked = listOf(ink, ink))

    @Test fun wrapUpInk() = shot(Theme.Ink) { WrapUpScreen(wrap, WrapUpActions()) }

    @Test fun wrapUpPaper() = shot(Theme.Paper) { WrapUpScreen(wrap, WrapUpActions()) }

    @Test fun breakParked() = shot(Theme.Ink) {
        dev.framio.pomodoro.ui.breaks.BreakScreen(
            dev.framio.pomodoro.ui.breaks.BreakUi(prompt = dev.framio.pomodoro.core.engine.BreakPrompt.Window, accent = 1, done = 4, target = 6, parked = listOf(ink, ink)),
            left = { 7.minutes },
            progress = { 0.3f },
            actions = dev.framio.pomodoro.ui.breaks.BreakActions(),
        )
    }
}
