@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.framio.pomodoro.platform

import dev.framio.pomodoro.core.engine.DisplayLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Blackout's restore paths and Deep's lifts on a fake backlight (M0 rules: restore only what we wrote, only if it
 * still reads that; never touch the window while dark). Virtual time: ramps take no real time.
 */
class DisplayControllerTest {
    private class FakeBacklight(var level: Int = 187) : Backlight {
        val writes = mutableListOf<Int>()
        override fun read(): Int = level
        override fun write(level: Int): Boolean {
            writes += level
            this.level = level
            return true
        }
    }

    private class MemoryMarker : DarkMarker {
        var value: Pair<Int, Int>? = null
        override fun save(wrote: Int, day: Int) { value = wrote to day }
        override fun load() = value
        override fun clear() { value = null }
    }

    /** The controller on its own scope of the test's scheduler (so advanceUntilIdle runs it), cancelled after the test. */
    private class Rig(test: TestScope, level: Int = 187) {
        val scope = CoroutineScope(StandardTestDispatcher(test.testScheduler))
        val backlight = FakeBacklight(level)
        val marker = MemoryMarker()
        val power = DisplayPower(backlight, marker, StandardTestDispatcher(test.testScheduler)) { 120 }
        val display = DisplayController(power, scope)
    }

    private val rigs = mutableListOf<Rig>()

    @After
    fun tearDown() = rigs.forEach { it.scope.cancel() }

    private fun TestScope.rig(level: Int = 187) = Rig(this, level).also { rigs += it; advanceUntilIdle() }

    @Test
    fun `blackout turns the backlight off and leaves the window alone`() = runTest {
        val r = rig()
        val window = r.display.window.value
        r.display.set(DisplayLevel.Blackout, Duration.ZERO)
        advanceUntilIdle()
        assertEquals(0, r.backlight.level)
        assertEquals(0 to 187, r.marker.value)
        assertEquals(window, r.display.window.value)
    }

    @Test
    fun `a lift in blackout steps the backlight up to the peek level and back to dark`() = runTest {
        val r = rig()
        r.display.set(DisplayLevel.Blackout, Duration.ZERO)
        advanceUntilIdle()
        r.display.light(true)
        advanceUntilIdle()
        assertEquals(DisplayController.PEEK_BACKLIGHT, r.backlight.level)
        assertEquals(DisplayController.PEEK_BACKLIGHT, r.marker.value?.first) // a kill now still recovers
        r.display.light(false)
        advanceUntilIdle()
        assertEquals(0, r.backlight.level)
        assertTrue(r.power.isDark)
        // Stepped, not jumped: intermediate levels on the way up and down
        assertTrue(r.backlight.writes.any { it in 1 until DisplayController.PEEK_BACKLIGHT })
    }

    @Test
    fun `the sunrise from blackout rises to the day level over the ramp and lets go`() = runTest {
        val r = rig(150)
        r.display.set(DisplayLevel.Blackout, Duration.ZERO)
        advanceUntilIdle()
        val start = testScheduler.currentTime
        r.display.set(DisplayLevel.Normal, 10.seconds)
        advanceUntilIdle()
        assertEquals(150, r.backlight.level)
        assertTrue(testScheduler.currentTime - start >= 9_000)
        assertTrue(r.backlight.writes.count { it in 1 until 150 } >= 10)
        assertFalse(r.power.owns)
        assertNull(r.marker.value)
        assertEquals(DisplayLevel.Normal, r.display.window.value.level)
    }

    @Test
    fun `pausing, ending or leaving the screen restores at once`() = runTest {
        val r = rig()
        r.display.set(DisplayLevel.Blackout, Duration.ZERO)
        advanceUntilIdle()
        r.display.set(DisplayLevel.Normal, Duration.ZERO) // Pause / End / Home
        advanceUntilIdle()
        assertEquals(187, r.backlight.level)
        r.display.set(DisplayLevel.Blackout, Duration.ZERO)
        r.display.light(true) // the panel is open when the activity stops
        advanceUntilIdle()
        r.display.onLeftScreen()
        advanceUntilIdle()
        assertEquals(187, r.backlight.level)
        assertFalse(r.power.owns)
        assertNull(r.marker.value)
    }

    @Test
    fun `someone else's change wins over a lift and over the sunrise`() = runTest {
        val r = rig()
        r.display.set(DisplayLevel.Blackout, Duration.ZERO)
        advanceUntilIdle()
        r.backlight.level = 0
        r.backlight.level = 90 // Frameo or the system wrote it meanwhile
        r.display.light(true)
        advanceUntilIdle()
        assertEquals(90, r.backlight.level)
        assertFalse(r.power.owns)
        r.display.set(DisplayLevel.Normal, 10.seconds)
        advanceUntilIdle()
        assertEquals(90, r.backlight.level)
    }

    @Test
    fun `a kill in the middle of a peek is recovered at the next start`() = runTest {
        val r = rig()
        r.display.set(DisplayLevel.Blackout, Duration.ZERO)
        r.display.light(true)
        advanceUntilIdle()
        // kill -9 here: no handler runs. The next process starts with the same marker:
        val next = DisplayPower(r.backlight, r.marker, StandardTestDispatcher(testScheduler)) { 120 }
        next.recoverAtStart()
        assertEquals(187, r.backlight.level)
        assertNull(r.marker.value)
    }

    @Test
    fun `the crash path restores a lifted backlight too`() = runTest {
        val r = rig()
        r.display.set(DisplayLevel.Blackout, Duration.ZERO)
        r.display.light(true)
        advanceUntilIdle()
        r.power.restoreBlocking()
        assertEquals(187, r.backlight.level)
    }

    @Test
    fun `calm lifts the window, not the backlight`() = runTest {
        val r = rig()
        r.display.set(DisplayLevel.Calm, Duration.ZERO)
        advanceUntilIdle()
        assertEquals(WindowLevels.CALM, WindowLevels.of(r.display.window.value))
        r.display.light(true)
        advanceUntilIdle()
        assertEquals(WindowLevels.PEEK, WindowLevels.of(r.display.window.value))
        assertTrue(r.display.window.value.ramp.isPositive())
        r.display.light(false)
        advanceUntilIdle()
        assertEquals(WindowLevels.CALM, WindowLevels.of(r.display.window.value))
        assertEquals(emptyList<Int>(), r.backlight.writes)
    }

    @Test
    fun `a lift outside calm and blackout changes nothing`() = runTest {
        val r = rig()
        r.display.set(DisplayLevel.Normal, 10.seconds)
        advanceUntilIdle()
        val before = r.display.window.value
        r.display.light(true)
        advanceUntilIdle()
        assertEquals(before, r.display.window.value)
        assertEquals(emptyList<Int>(), r.backlight.writes)
    }

    @Test
    fun `evening amber caps the window, eased in, and lets go again`() = runTest {
        val r = rig()
        r.display.evening(true)
        advanceUntilIdle()
        val capped = r.display.window.value
        assertEquals(DisplayController.EVENING_CAP, capped.cap)
        assertEquals(DisplayController.EVENING_RAMP, capped.ramp) // the cap arriving on its own eases in
        assertEquals(0.35f, WindowLevels.capped(capped, system = 187 / 255f), 0.001f)
        assertEquals(0.2f, WindowLevels.capped(capped, system = 0.2f), 0.001f) // never brighter than the system
        r.display.set(DisplayLevel.Dim, Duration.ZERO)
        advanceUntilIdle()
        assertEquals(0.3f, WindowLevels.capped(r.display.window.value, system = 0.73f), 0.001f) // Dim is below the cap
        r.display.set(DisplayLevel.Calm, Duration.ZERO)
        r.display.light(true)
        advanceUntilIdle()
        assertEquals(0.3f, WindowLevels.capped(r.display.window.value, system = 0.73f), 0.001f) // a Calm peek stays under it
        r.display.light(false)
        r.display.set(DisplayLevel.Normal, Duration.ZERO)
        r.display.evening(false)
        advanceUntilIdle()
        assertNull(r.display.window.value.cap)
        assertEquals(WindowLevels.SYSTEM, WindowLevels.capped(r.display.window.value, system = 0.73f))
    }

    @Test
    fun `in the evening, Blackout's sunrise rises only to the cap`() = runTest {
        val r = rig()
        r.display.evening(true)
        r.display.set(DisplayLevel.Blackout, Duration.ZERO)
        advanceUntilIdle()
        assertEquals(0, r.backlight.level)
        r.display.set(DisplayLevel.Normal, 10.seconds)
        advanceUntilIdle()
        assertEquals((DisplayController.EVENING_CAP * 255).toInt(), r.backlight.level)
        assertTrue(r.backlight.writes.none { it > (DisplayController.EVENING_CAP * 255).toInt() })
    }
}
