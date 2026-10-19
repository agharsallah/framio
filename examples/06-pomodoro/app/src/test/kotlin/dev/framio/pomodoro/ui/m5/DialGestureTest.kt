package dev.framio.pomodoro.ui.m5

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.semantics.SemanticsProperties
import dev.framio.pomodoro.ui.FitToDesign
import dev.framio.pomodoro.ui.LANDSCAPE
import dev.framio.pomodoro.ui.components.KeyboardSheet
import dev.framio.pomodoro.ui.editor.DurationDial
import dev.framio.pomodoro.ui.theme.QuietTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The dial and the keyboard by touch, on the frame's API level and size. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], qualifiers = LANDSCAPE)
class DialGestureTest {
    @get:Rule val compose = createComposeRule()

    private val values = mutableListOf<Int>()

    private fun dial(start: Int, range: IntRange = 1..240) {
        compose.setContent {
            QuietTheme {
                FitToDesign {
                    var minutes by remember { mutableIntStateOf(start) }
                    DurationDial("Focus", minutes, range, { minutes = it; values += it }, QuietTheme.colors.accent(0))
                }
            }
        }
        compose.waitForIdle()
    }

    /** Drags around the dial's centre from [fromDeg] to [toDeg] (clockwise from 12 o'clock), in 5° steps. */
    private fun turn(fromDeg: Float, toDeg: Float) {
        compose.onNodeWithTag("dial-Focus").performTouchInput {
            val r = width * 0.4f
            fun at(deg: Float): Offset {
                val a = deg * PI.toFloat() / 180f
                return Offset(center.x + r * sin(a), center.y - r * cos(a))
            }
            down(at(fromDeg))
            val steps = ((toDeg - fromDeg) / 5f).toInt().let { kotlin.math.abs(it) }.coerceAtLeast(1)
            for (i in 1..steps) moveTo(at(fromDeg + (toDeg - fromDeg) * i / steps))
            up()
        }
        compose.waitForIdle()
    }

    private fun shows(text: String) = compose.onNodeWithTag("dial-Focus").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text))

    @Test
    fun `a quarter turn clockwise adds a quarter hour, wherever the finger starts`() {
        dial(25)
        turn(200f, 290f)
        assertEquals(40, values.last())
        shows("40 min")
    }

    @Test
    fun `turning back through 12 o'clock takes time off smoothly and snaps to whole minutes below 15`() {
        dial(25)
        turn(30f, -72f) // 102° anticlockwise ≈ 17 min, across 12 o'clock
        assertEquals(8, values.last())
        assertEquals(values, values.sorted().reversed()) // never a jump of a whole turn
        shows("8 min")
    }

    @Test
    fun `several turns for a long block, held at the range's end`() {
        dial(50, 1..240)
        turn(0f, 360f * 4)
        assertEquals(240, values.last())
        shows("4 h")
    }

    @Test
    fun `the buttons take one step`() {
        dial(25)
        compose.onNodeWithText("+").performClick()
        compose.waitForIdle()
        assertEquals(30, values.last())
        compose.onNodeWithText("−").performClick()
        compose.onNodeWithText("−").performClick()
        compose.waitForIdle()
        assertEquals(20, values.last())
    }

    @Test
    fun `the keyboard types, takes an accent on a long press, and deletes`() {
        var text by mutableStateOf("")
        var done = false
        compose.setContent {
            QuietTheme { FitToDesign { KeyboardSheet("Name", text, { text = it }, { done = true }, {}) } }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("key-c").performClick()
        compose.onNodeWithTag("key-a").performClick()
        compose.onNodeWithTag("key-f").performClick()
        compose.onNodeWithTag("key-e").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("é").performClick()
        compose.waitForIdle()
        assertEquals("Café", text)
        compose.onNodeWithTag("key-backspace").performClick()
        compose.waitForIdle()
        assertEquals("Caf", text)
        compose.onNodeWithContentDescription("Letters or numbers").performClick()
        compose.onNodeWithTag("key-1").performClick()
        compose.onNodeWithContentDescription("Done").performClick()
        compose.waitForIdle()
        assertEquals("Caf1", text)
        assertEquals(true, done)
    }
}
