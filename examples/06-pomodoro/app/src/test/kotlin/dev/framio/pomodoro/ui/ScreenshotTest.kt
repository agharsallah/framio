package dev.framio.pomodoro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.framio.pomodoro.core.engine.BreakPrompt
import dev.framio.pomodoro.core.model.IntentionRule
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Theme
import dev.framio.pomodoro.ui.breaks.BreakActions
import dev.framio.pomodoro.ui.breaks.BreakScreen
import dev.framio.pomodoro.ui.breaks.BreakUi
import dev.framio.pomodoro.ui.focus.FocusActions
import dev.framio.pomodoro.ui.focus.FocusScreen
import dev.framio.pomodoro.ui.focus.FocusUi
import dev.framio.pomodoro.ui.home.HomeActions
import dev.framio.pomodoro.ui.home.HomeScreen
import dev.framio.pomodoro.ui.home.HomeUi
import dev.framio.pomodoro.ui.home.ModeCard
import dev.framio.pomodoro.ui.ready.ReadyScreen
import dev.framio.pomodoro.ui.ready.ReadyUi
import dev.framio.pomodoro.ui.settings.SettingsActions
import dev.framio.pomodoro.ui.settings.SettingsScreen
import dev.framio.pomodoro.ui.settings.SettingsSection
import dev.framio.pomodoro.ui.settings.SettingsUi
import dev.framio.pomodoro.ui.theme.QuietTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Golden images of the M2 screens in the Ink theme, at the frame's 1280×800 (mdpi: 1 dp = 1 px) and standing
 * (800×1280). Record with `./gradlew recordRoborazziDebug` after an intentional change; `verifyRoborazziDebug`
 * fails on any pixel drift. States are fixed (no clock), so the images are deterministic.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SCREENSHOT_SDK], qualifiers = LANDSCAPE)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    /** Screens are transparent (the root draws the background, M3): the shot draws the theme's background behind. */
    private fun shot(content: @Composable () -> Unit) {
        compose.setContent {
            QuietTheme(theme = Theme.Ink) {
                Box(Modifier.fillMaxSize().background(QuietTheme.colors.background)) { FitToDesign { content() } }
            }
        }
        compose.onRoot().captureRoboImage()
    }

    private val home = HomeUi(
        modes = listOf(
            ModeCard(Presets.CLASSIC_ID, "Classic", 0, 25, 5, true),
            ModeCard(Presets.DEEP_ID, "Deep", 1, 50, 10, true),
            ModeCard(Presets.FLOW_ID, "Flow", 2, null, null, true),
            ModeCard(Presets.GENTLE_ID, "Gentle start", 3, 10, 3, true),
        ),
        selected = Presets.CLASSIC_ID,
        accent = 0,
        tasks = listOf("Thesis ch.3", "Review PR", "Taxes"),
        chosenTask = "Thesis ch.3",
        resumeNote = "rewrite §3.2 intro, then figures",
        done = 3,
        target = 6,
        intention = IntentionRule.Optional,
    )

    private val focus = FocusUi(modeName = "Classic", accent = 0, intention = "Thesis ch.3", showSeconds = true)

    @Composable
    private fun Focus(ui: FocusUi) = FocusScreen(ui, left = { 17.minutes + 42.seconds }, progress = { 0.2919f }, actions = FocusActions())

    private val breakUi = BreakUi(long = false, prompt = BreakPrompt.Window, pendingWrapUp = true, accent = 0, done = 4, target = 6)

    @Composable
    private fun Break() = BreakScreen(breakUi, left = { 3.minutes + 12.seconds }, progress = { 0.36f }, actions = BreakActions())

    private val ready = ReadyUi(modeName = "Classic", accent = 0, intention = "Thesis ch.3", nextMinutes = 25, done = 4, target = 6)

    @Test fun home() = shot { HomeScreen(home, HomeActions()) }

    @Test fun focusRing() = shot { Focus(focus) }

    @Test fun focusControls() = shot { Focus(focus.copy(controls = true)) }

    @Test fun focusPaused() = shot { Focus(focus.copy(paused = true, controls = true)) }

    @Test fun breakWithWrapUp() = shot { Break() }

    @Test fun ready() = shot { ReadyScreen(ready, onStart = {}, onHome = {}) }

    @Test fun settings() = shot { SettingsScreen(SettingsUi(), SettingsSection.Sound, SettingsActions()) }

    @Test @Config(qualifiers = PORTRAIT)
    fun homePortrait() = shot { HomeScreen(home, HomeActions()) }

    @Test @Config(qualifiers = PORTRAIT)
    fun focusRingPortrait() = shot { Focus(focus.copy(controls = true)) }

    @Test @Config(qualifiers = PORTRAIT)
    fun breakPortrait() = shot { Break() }

    @Test @Config(qualifiers = PORTRAIT)
    fun readyPortrait() = shot { ReadyScreen(ready, onStart = {}, onHome = {}) }
}

/**
 * Robolectric's native graphics (what Roborazzi draws with) exist from API 26 only; at 23 it falls back to wireframe
 * boxes. So goldens render at 26, the closest to the frame's 23; behaviour tests stay at 23.
 */
const val SCREENSHOT_SDK = 26
const val LANDSCAPE = "w1280dp-h800dp-land-mdpi"
const val PORTRAIT = "w800dp-h1280dp-port-mdpi"
