package dev.framio.pomodoro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.framio.pomodoro.core.model.ChecklistItem
import dev.framio.pomodoro.core.model.Display
import dev.framio.pomodoro.core.model.Face
import dev.framio.pomodoro.core.model.IntentionRule
import dev.framio.pomodoro.core.model.Mode
import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Rhythm
import dev.framio.pomodoro.core.model.Soundscape
import dev.framio.pomodoro.core.model.Theme
import dev.framio.pomodoro.core.model.TimerSize
import dev.framio.pomodoro.ui.components.KeyboardSheet
import dev.framio.pomodoro.ui.editor.EditorActions
import dev.framio.pomodoro.ui.editor.EditorTab
import dev.framio.pomodoro.ui.editor.EditorUi
import dev.framio.pomodoro.ui.editor.ModeEditorScreen
import dev.framio.pomodoro.ui.focus.FocusActions
import dev.framio.pomodoro.ui.focus.FocusScreen
import dev.framio.pomodoro.ui.focus.FocusUi
import dev.framio.pomodoro.ui.home.HomeActions
import dev.framio.pomodoro.ui.home.HomeScreen
import dev.framio.pomodoro.ui.home.HomeUi
import dev.framio.pomodoro.ui.home.ModeCard
import dev.framio.pomodoro.ui.settings.ExportState
import dev.framio.pomodoro.ui.settings.ModeRow
import dev.framio.pomodoro.ui.settings.RitualRow
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
 * Golden images of M5: the mode editor (each page, a preset, Flow, standing), the built-in keyboard (both layers, the
 * accent strip, standing), each Settings section, evening amber on Home and Focus, every theme × Home, and the timer
 * sizes. Record with `recordRoborazziDebug` after an intentional change.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SCREENSHOT_SDK], qualifiers = LANDSCAPE)
class M5ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shot(
        theme: Theme = Theme.Ink,
        evening: Boolean = false,
        timerSize: TimerSize = TimerSize.Medium,
        act: () -> Unit = {},
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            QuietTheme(theme = theme, evening = evening, timerSize = timerSize) {
                Box(Modifier.fillMaxSize().background(QuietTheme.colors.background)) { FitToDesign { content() } }
            }
        }
        compose.waitForIdle()
        act()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage()
    }

    // The mode editor

    private val own: Mode = Presets.deep.duplicate(ModeId("own-1"), "Late writing").let { m ->
        m.copy(
            accent = 5,
            rhythm = (m.rhythm as Rhythm.Fixed).copy(focus = 40.minutes, short = 8.minutes, longEvery = 3),
            ritual = m.ritual.copy(checklist = m.ritual.checklist.take(2) + ChecklistItem("own-1", "Desk clear")),
            environment = m.environment.copy(soundscape = Soundscape.Rain),
        )
    }

    private fun editor(mode: Mode = own, tab: EditorTab = EditorTab.Rhythm) = EditorUi(mode = mode, tab = tab, canSkip = true)

    @Test fun editorRhythm() = shot { ModeEditorScreen(editor(), EditorActions()) }

    @Test fun editorRitual() = shot { ModeEditorScreen(editor(tab = EditorTab.Ritual), EditorActions()) }

    @Test fun editorScreenCalm() = shot { ModeEditorScreen(editor(tab = EditorTab.Screen), EditorActions()) }

    @Test fun editorScreenFullPaper() = shot(Theme.Paper) {
        val full = Presets.classic.duplicate(ModeId("own-2"), "Reading").let { it.copy(environment = it.environment.copy(face = Face.Disc), accent = 6) }
        ModeEditorScreen(editor(full, EditorTab.Screen), EditorActions())
    }

    @Test fun editorBlackoutForest() = shot(Theme.Forest) {
        ModeEditorScreen(editor(own.copy(environment = own.environment.copy(display = Display.Blackout)), EditorTab.Screen), EditorActions())
    }

    @Test fun editorFlow() = shot {
        val flow = Presets.flow.duplicate(ModeId("own-3"), "Long flow").copy(accent = 4)
        ModeEditorScreen(editor(flow), EditorActions())
    }

    @Test fun editorPreset() = shot { ModeEditorScreen(EditorUi(mode = Presets.deep), EditorActions()) }

    @Test @Config(qualifiers = PORTRAIT)
    fun editorPortrait() = shot { ModeEditorScreen(editor(), EditorActions()) }

    // The keyboard

    @Composable
    private fun Keyboard(text: String = "Café au lait") = KeyboardSheet("A checklist item", text, {}, {}, {})

    @Test fun keyboardLetters() = shot { Keyboard() }

    @Test fun keyboardSymbols() = shot(act = { compose.onNodeWithContentDescription("Letters or numbers").performClick() }) { Keyboard() }

    @Test fun keyboardAccents() = shot(act = { compose.onNodeWithTag("key-o").performTouchInput { longClick() } }) { Keyboard("Sch") }

    @Test @Config(qualifiers = PORTRAIT)
    fun keyboardPortrait() = shot(Theme.Paper) { Keyboard("") }

    // Settings

    private val settings = SettingsUi(
        modes = listOf(
            ModeRow(Presets.CLASSIC_ID, "Classic", 0, true, 25, 5, Display.Full),
            ModeRow(ModeId("own-1"), "Late writing", 5, false, 40, 8, Display.Calm),
            ModeRow(Presets.DEEP_ID, "Deep", 1, true, 50, 10, Display.Calm),
            ModeRow(Presets.FLOW_ID, "Flow", 2, true, null, null, Display.Calm),
            ModeRow(Presets.GENTLE_ID, "Gentle start", 3, true, 10, 3, Display.Full),
        ),
        rituals = listOf(RitualRow(Presets.DEEP_ID, "Deep", skip = false, canSkip = true), RitualRow(ModeId("own-1"), "Late writing", skip = true, canSkip = true)),
        parkedKept = 3,
        version = "0.1",
    )

    private fun settingsShot(section: SettingsSection, ui: SettingsUi = settings, theme: Theme = Theme.Ink) =
        shot(theme) { SettingsScreen(ui, section, SettingsActions()) }

    @Test fun settingsModes() = settingsShot(SettingsSection.Modes)

    @Test fun settingsModesUndo() = settingsShot(SettingsSection.Modes, settings.copy(deleted = "Late writing"))

    @Test fun settingsRituals() = settingsShot(SettingsSection.Rituals)

    @Test fun settingsDeep() = settingsShot(SettingsSection.Deep)

    @Test fun settingsSound() = settingsShot(SettingsSection.Sound)

    @Test fun settingsAppearance() = settingsShot(SettingsSection.Appearance)

    @Test fun settingsAppearancePaper() = settingsShot(SettingsSection.Appearance, settings.copy(theme = Theme.Paper, accentOverride = 2), Theme.Paper)

    @Test fun settingsGoals() = settingsShot(SettingsSection.Goals)

    @Test fun settingsData() = settingsShot(SettingsSection.Data, settings.copy(export = ExportState.Done("/storage/emulated/0/Android/data/dev.framio.pomodoro/files/export")))

    @Test fun settingsAbout() = settingsShot(SettingsSection.About)

    @Test @Config(qualifiers = PORTRAIT)
    fun settingsModesPortrait() = settingsShot(SettingsSection.Modes)

    // Evening amber, themes, timer sizes

    private val home = HomeUi(
        modes = listOf(
            ModeCard(Presets.CLASSIC_ID, "Classic", 0, 25, 5, true),
            ModeCard(Presets.DEEP_ID, "Deep", 1, 50, 10, true),
            ModeCard(Presets.FLOW_ID, "Flow", 2, null, null, true),
            ModeCard(Presets.GENTLE_ID, "Gentle start", 3, 10, 3, true),
            ModeCard(ModeId("own-1"), "Late writing", 5, 40, 8, false),
        ),
        selected = Presets.DEEP_ID,
        accent = 1,
        tasks = listOf("Thesis ch.3", "Review PR", "Taxes"),
        chosenTask = "Thesis ch.3",
        resumeNote = "rewrite §3.2 intro, then figures",
        done = 3,
        target = 6,
        intention = IntentionRule.Required,
        ritual = true,
    )

    private val focus = FocusUi(modeName = "Classic", accent = 0, intention = "Thesis ch.3", showSeconds = true)

    @Composable
    private fun Focus(ui: FocusUi = focus) = FocusScreen(ui, left = { 17.minutes + 42.seconds }, progress = { 0.2919f }, actions = FocusActions())

    @Test fun homeEvening() = shot(evening = true) { HomeScreen(home, HomeActions()) }

    @Test fun focusEvening() = shot(evening = true) { Focus() }

    @Test fun focusEveningDisc() = shot(Theme.Paper, evening = true) { Focus(focus.copy(face = Face.Disc, accent = 3)) }

    @Test fun homeInk() = shot(Theme.Ink) { HomeScreen(home, HomeActions()) }

    @Test fun homePaper() = shot(Theme.Paper) { HomeScreen(home, HomeActions()) }

    @Test fun homeForest() = shot(Theme.Forest) { HomeScreen(home, HomeActions()) }

    @Test fun homeTomato() = shot(Theme.Tomato) { HomeScreen(home, HomeActions()) }

    @Test fun focusLarge() = shot(timerSize = TimerSize.Large) { Focus() }

    @Test fun focusSmallDigits() = shot(timerSize = TimerSize.Small) { Focus(focus.copy(face = Face.Digits)) }
}
