package dev.framio.pomodoro.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.framio.pomodoro.FakeHardware
import dev.framio.pomodoro.FakeHistory
import dev.framio.pomodoro.FakePort
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.core.notes.Entry
import dev.framio.pomodoro.core.notes.Taxonomy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The hold panel's reason chips through the real root, at the frame's API level and size (DESIGN-history §5.1): one
 * tap logs once, a second chip within 2 s joins the same distraction, the panel then goes by itself; a tap outside
 * closes it without logging anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], qualifiers = LANDSCAPE)
class ReasonGestureTest {
    @get:Rule val compose = createComposeRule()

    private val locked = "Deep focus. Hold for a second to open the panel."

    private fun deepFocus(): Pair<FakePort, FakeHistory> {
        val port = FakePort(settings = Settings(selectedMode = Presets.DEEP_ID))
        port.send(Input.Begin("Thesis"))
        val history = FakeHistory()
        compose.setContent { PomodoroRoot(port, playTest = {}, onExit = {}, hardware = FakeHardware(), history = history) }
        compose.waitForIdle()
        val face = compose.onNodeWithContentDescription(locked)
        compose.mainClock.autoAdvance = false
        face.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_300)
        face.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        return port to history
    }

    @Test
    fun `one chip logs one distraction, and the panel goes by itself`() {
        val (port, history) = deepFocus()
        compose.onNodeWithText("Phone").performClick()
        compose.waitForIdle()
        val d = history.entries(port.state.value.session?.id).single() as Entry.Distraction
        assertEquals(listOf(Taxonomy.PHONE), d.tags)
        compose.onNodeWithText("Logged · Phone").assertIsDisplayed()
        // The ViewModel's 2 s window runs on the main looper, not the compose clock
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2_500))
        compose.waitForIdle()
        compose.onNodeWithText("Park a thought").assertDoesNotExist()
    }

    @Test
    fun `a second chip within two seconds joins the same distraction`() {
        val (port, history) = deepFocus()
        compose.onNodeWithText("Phone").performClick()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("Noise").performClick()
        compose.waitForIdle()
        val d = history.entries(port.state.value.session?.id).single() as Entry.Distraction
        assertEquals(listOf(Taxonomy.PHONE, Taxonomy.NOISE), d.tags)
    }

    @Test
    fun `a tap outside the panel closes it and logs nothing`() {
        val (_, history) = deepFocus()
        compose.onNodeWithText("Park a thought").assertIsDisplayed()
        compose.onNodeWithContentDescription(locked).performTouchInput { click(Offset(200f, 60f)) }
        compose.waitForIdle()
        compose.onNodeWithText("Park a thought").assertDoesNotExist()
        assertTrue(history.ops.isEmpty())
    }

    @Test
    fun `More shows the rest of the reasons`() {
        deepFocus()
        compose.onNodeWithText("Other").assertDoesNotExist()
        compose.onNodeWithText("More").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Other").assertIsDisplayed()
    }
}
