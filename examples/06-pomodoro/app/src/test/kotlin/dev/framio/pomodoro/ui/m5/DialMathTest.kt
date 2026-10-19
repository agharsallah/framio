package dev.framio.pomodoro.ui.m5

import androidx.compose.ui.geometry.Offset
import dev.framio.pomodoro.ui.editor.DialMath
import org.junit.Assert.assertEquals
import org.junit.Test

class DialMathTest {
    private val c = Offset(100f, 100f)

    @Test
    fun `angles run clockwise from 12 o'clock`() {
        assertEquals(0f, DialMath.angle(Offset(100f, 0f), c), 0.01f)
        assertEquals(90f, DialMath.angle(Offset(200f, 100f), c), 0.01f)
        assertEquals(180f, DialMath.angle(Offset(100f, 200f), c), 0.01f)
        assertEquals(270f, DialMath.angle(Offset(0f, 100f), c), 0.01f)
    }

    @Test
    fun `a turn across 12 o'clock is small, not a whole circle`() {
        assertEquals(20f, DialMath.turn(350f, 10f), 0.01f)
        assertEquals(-20f, DialMath.turn(10f, 350f), 0.01f)
        assertEquals(90f, DialMath.turn(0f, 90f), 0.01f)
        assertEquals(15f, DialMath.minutesFor(90f), 0.01f) // a quarter turn is a quarter hour
    }

    @Test
    fun `snaps to whole minutes below 15, to fives above, within the range`() {
        assertEquals(7, DialMath.snap(7.4f, 1..60))
        assertEquals(14, DialMath.snap(14.4f, 1..60))
        assertEquals(15, DialMath.snap(16.2f, 1..60))
        assertEquals(25, DialMath.snap(23.8f, 1..240))
        assertEquals(50, DialMath.snap(51.9f, 1..240))
        assertEquals(1, DialMath.snap(-3f, 1..60))
        assertEquals(60, DialMath.snap(75f, 1..60))
    }

    @Test
    fun `the buttons step by one below 15 and by five above, onto the grid`() {
        assertEquals(11, DialMath.step(10, up = true, 1..60))
        assertEquals(15, DialMath.step(14, up = true, 1..60))
        assertEquals(20, DialMath.step(15, up = true, 1..60))
        assertEquals(25, DialMath.step(22, up = true, 1..60))
        assertEquals(20, DialMath.step(22, up = false, 1..60))
        assertEquals(20, DialMath.step(25, up = false, 1..60))
        assertEquals(14, DialMath.step(15, up = false, 1..60))
        assertEquals(1, DialMath.step(1, up = false, 1..60))
        assertEquals(60, DialMath.step(60, up = true, 1..60))
    }

    @Test
    fun `the knob sits within the hour`() {
        assertEquals(150f, DialMath.knobDegrees(25), 0.01f)
        assertEquals(180f, DialMath.knobDegrees(90), 0.01f)
    }
}
