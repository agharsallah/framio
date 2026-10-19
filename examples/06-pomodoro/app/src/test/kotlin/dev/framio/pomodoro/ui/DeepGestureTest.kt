package dev.framio.pomodoro.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.framio.pomodoro.FakeHardware
import dev.framio.pomodoro.FakePort
import dev.framio.pomodoro.core.engine.Effect
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.engine.Phase
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deep's touch lock through the real root, on the frame's API level and size (PLAN §7): a tap does nothing, a 1.2 s
 * hold opens the panel, End asks for a resume note before it ends anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], qualifiers = LANDSCAPE)
class DeepGestureTest {
    @get:Rule val compose = createComposeRule()

    private val locked = "Deep focus. Hold for a second to open the panel."

    private fun deepFocus(): Pair<FakePort, FakeHardware> {
        val port = FakePort(settings = Settings(selectedMode = Presets.DEEP_ID))
        port.send(Input.Begin("Thesis"))
        val hw = FakeHardware()
        compose.setContent { PomodoroRoot(port, playTest = {}, onExit = {}, hardware = hw) }
        compose.waitForIdle()
        return port to hw
    }

    private fun holdFor(ms: Long) {
        val face = compose.onNodeWithContentDescription(locked)
        compose.mainClock.autoAdvance = false // the press lasts exactly [ms] of virtual time
        face.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(ms)
        face.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test
    fun `a tap does nothing but the faint hint`() {
        val (port, _) = deepFocus()
        compose.onNodeWithContentDescription(locked).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Park a thought").assertDoesNotExist()
        compose.onNodeWithText("Hold to open").assertExists()
        assertTrue(port.state.value.phase is Phase.Focus)
        assertTrue(port.sent.none { it is Input.Pause || it is Input.EndEarly })
    }

    @Test
    fun `a short press is still a tap`() {
        deepFocus()
        holdFor(600)
        compose.onNodeWithText("Park a thought").assertDoesNotExist()
    }

    @Test
    fun `holding for 1·2 s opens the panel and lifts the display`() {
        val (_, hw) = deepFocus()
        holdFor(1_300)
        compose.onNodeWithText("Park a thought").assertIsDisplayed()
        compose.onNodeWithText("Pause").assertIsDisplayed()
        compose.onNodeWithText("End").assertIsDisplayed()
        assertTrue(hw.lit)
    }

    @Test
    fun `End asks for a resume note, and only then ends, logged as ended early`() {
        val (port, _) = deepFocus()
        holdFor(1_300)
        compose.onNodeWithText("End").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Next step when you’re back?").assertIsDisplayed()
        assertTrue(port.state.value.phase is Phase.Focus)
        compose.onNodeWithText("End session").performClick()
        compose.waitForIdle()
        assertEquals(Phase.Idle, port.state.value.phase)
        assertTrue(port.sent.last() is Input.EndEarly)
    }

    @Test
    fun `the accelerometer is on only while deep focus is on screen`() {
        val (port, hw) = deepFocus()
        assertTrue(hw.isListening)
        port.send(Input.Pause) // paused: no peeking, no sensor
        compose.waitForIdle()
        assertEquals(false, hw.isListening)
        port.send(Input.Resume)
        compose.waitForIdle()
        assertTrue(hw.isListening)
        port.send(Input.EndEarly())
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertEquals(false, hw.isListening)
    }

    @Test
    fun `the deep display effects follow the block`() {
        val (port, _) = deepFocus()
        // Calm while focusing; the sunrise is the engine's (core), the screen only lifts on top of it
        val calm = dev.framio.pomodoro.core.engine.Derive.display(port.state.value)
        assertEquals(dev.framio.pomodoro.core.engine.DisplayLevel.Calm, calm)
        assertTrue(Effect.SetDisplay(calm) != Effect.SetDisplay(dev.framio.pomodoro.core.engine.DisplayLevel.Normal))
    }
}
