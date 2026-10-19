package dev.framio.pomodoro.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.framio.pomodoro.FakePort
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
import kotlin.time.Duration.Companion.minutes

/** The real root on the frame's API level and size: Home → Begin → Focus → controls → Pause → End → Home. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], qualifiers = LANDSCAPE)
class RootFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `a classic block from Home through pause and end`() {
        val port = FakePort()
        compose.setContent { PomodoroRoot(port, playTest = {}, onExit = {}) }
        compose.onNodeWithText("Begin").performClick()
        compose.waitForIdle()
        assertTrue(port.state.value.phase is Phase.Focus)
        // The digits speak as a sentence (semantics), changing once a minute
        compose.onNodeWithContentDescription("25 minutes left in focus", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Pause").assertDoesNotExist()
        compose.onNodeWithText("Focus · Classic", useUnmergedTree = true).performClick() // a tap anywhere
        compose.onNodeWithText("Pause").performClick()
        compose.waitForIdle()
        assertTrue(port.state.value.phase is Phase.Paused)
        compose.onNodeWithText("Paused", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("End").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Next step when you’re back?").assertIsDisplayed() // a resume note first
        compose.onNodeWithText("End session").performClick()
        compose.waitForIdle()
        assertEquals(Phase.Idle, port.state.value.phase)
        compose.onNodeWithText("Begin").assertIsDisplayed()
    }

    @Test
    fun `the break offers the wrap-up, and Ready starts the next block`() {
        val port = FakePort()
        port.send(Input.Begin(intention = "Taxes"))
        port.pass(25.minutes)
        compose.setContent { PomodoroRoot(port, playTest = {}, onExit = {}) }
        compose.onNodeWithText("Stand up and move").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("How did that go?").assertDoesNotExist()
        compose.onNodeWithText("Skip break").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Ready for the next one?").assertIsDisplayed()
        compose.onNodeWithText("Start").performClick()
        compose.waitForIdle()
        assertTrue(port.state.value.phase is Phase.Focus)
        assertEquals("Taxes", port.state.value.run?.intent?.intention)
    }

    @Test
    fun `a flow block counts up, stops at a natural point, and the break shows its tier`() {
        val port = FakePort(settings = Settings(selectedMode = Presets.FLOW_ID))
        compose.setContent { PomodoroRoot(port, playTest = {}, onExit = {}) }
        compose.onNodeWithText("Begin").performClick()
        compose.waitForIdle()
        assertTrue(port.state.value.phase is Phase.Focus)
        compose.onNodeWithContentDescription("0 minutes of focus so far", useUnmergedTree = true).assertIsDisplayed()
        port.clock.advance(37.minutes)
        compose.onNodeWithText("Flow · counting up", useUnmergedTree = true).performClick() // a tap anywhere
        compose.waitForIdle()
        compose.onNodeWithText("Stop at a natural point: 8 minute break", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Stop here").performClick()
        compose.waitForIdle()
        assertTrue(port.state.value.phase is Phase.Break)
        compose.onNodeWithText("Worked 37 min → 8 min break").assertIsDisplayed()
        compose.onNodeWithText("+5 min").performClick()
        compose.waitForIdle()
        assertEquals(13.minutes, (port.state.value.phase as Phase.Break).length)
    }

    @Test
    fun `the break's (i) opens the evidence and closes again`() {
        val port = FakePort()
        port.send(Input.Begin(intention = "Taxes"))
        port.pass(25.minutes)
        compose.setContent { PomodoroRoot(port, playTest = {}, onExit = {}) }
        compose.onNodeWithContentDescription("Why? Show the evidence").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Moderate evidence · helps how you feel more than output").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("A break away from the screen").assertDoesNotExist()
    }

    @Test
    fun `gentle start is ten minutes, three of break, then another 10`() {
        val port = FakePort(settings = Settings(selectedMode = Presets.GENTLE_ID))
        port.send(Input.Begin())
        port.pass(10.minutes)
        port.pass(3.minutes)
        compose.setContent { PomodoroRoot(port, playTest = {}, onExit = {}) }
        compose.onNodeWithText("Ten minutes done. That counts.").assertIsDisplayed()
        compose.onNodeWithText("Another 10").performClick()
        compose.waitForIdle()
        assertEquals(10.minutes, port.state.value.session?.planned)
    }
}
