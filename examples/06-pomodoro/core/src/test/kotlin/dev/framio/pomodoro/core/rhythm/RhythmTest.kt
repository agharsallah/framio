package dev.framio.pomodoro.core.rhythm

import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Rhythm
import dev.framio.pomodoro.core.model.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class FixedRhythmTest {
    private val classic = Presets.classic.rhythm as Rhythm.Fixed
    private val deep = Presets.deep.rhythm as Rhythm.Fixed

    @Test
    fun `classic has a long break after every 4th block`() {
        val kinds = (1..9).map { FixedRhythm.breakAfter(classic, it).kind }
        assertEquals(
            listOf(BreakKind.Short, BreakKind.Short, BreakKind.Short, BreakKind.Long, BreakKind.Short, BreakKind.Short, BreakKind.Short, BreakKind.Long, BreakKind.Short),
            kinds,
        )
        assertEquals(BreakPlan(BreakKind.Long, 20.minutes), FixedRhythm.breakAfter(classic, 4))
        assertEquals(BreakPlan(BreakKind.Short, 5.minutes), FixedRhythm.breakAfter(classic, 5))
    }

    @Test
    fun `deep has a 30 minute break after every 2nd block`() {
        assertEquals(BreakPlan(BreakKind.Short, 10.minutes), FixedRhythm.breakAfter(deep, 1))
        assertEquals(BreakPlan(BreakKind.Long, 30.minutes), FixedRhythm.breakAfter(deep, 2))
        assertEquals(BreakPlan(BreakKind.Short, 10.minutes), FixedRhythm.breakAfter(deep, 3))
    }

    @Test
    fun `longEvery 0 never gives a long break, nor does cycle 0`() {
        val gentle = Presets.gentle.rhythm as Rhythm.Fixed
        assertTrue((1..12).none { FixedRhythm.isLong(gentle, it) })
        assertFalse(FixedRhythm.isLong(classic, 0))
    }

    @Test
    fun `auto-start flags`() {
        assertTrue(classic.autoBreak)
        assertFalse(BreakPolicy.autoFocus(classic))
        assertFalse(deep.autoBreak)
        assertTrue(BreakPolicy.autoFocus(classic.copy(autoFocus = true)))
        assertFalse(BreakPolicy.autoFocus(Presets.flow.rhythm))
    }
}

class FlowRhythmTest {
    private val flow = Presets.flow.rhythm as Rhythm.Flow

    private fun rest(focused: Duration) = FlowRhythm.breakFor(flow, focused)

    @Test
    fun `Smits tier boundaries`() {
        assertEquals(5.minutes, rest(1.seconds))
        assertEquals(5.minutes, rest(25.minutes))
        assertEquals(8.minutes, rest(25.minutes + 1.seconds))
        assertEquals(8.minutes, rest(50.minutes))
        assertEquals(10.minutes, rest(50.minutes + 1.seconds))
        assertEquals(10.minutes, rest(240.minutes))
    }

    @Test
    fun `break policy uses the tiers for flow`() {
        assertEquals(BreakPlan(BreakKind.Short, 8.minutes), BreakPolicy.after(flow, 3, 40.minutes))
    }

    @Test
    fun `custom tiers, and empty tiers fall back to Smits`() {
        val custom = flow.copy(tiers = listOf(Tier(10.minutes, 2.minutes), Tier(null, 4.minutes)))
        assertEquals(2.minutes, FlowRhythm.breakFor(custom, 10.minutes))
        assertEquals(4.minutes, FlowRhythm.breakFor(custom, 11.minutes))
        assertEquals(8.minutes, FlowRhythm.breakFor(flow.copy(tiers = emptyList()), 30.minutes))
    }

    @Test
    fun `the nudge comes at 90 minutes, not before, and never when off`() {
        assertFalse(FlowRhythm.nudgeDue(flow, 89.minutes + 59.seconds))
        assertTrue(FlowRhythm.nudgeDue(flow, 90.minutes))
        assertFalse(FlowRhythm.nudgeDue(flow.copy(nudgeAfter = null), 300.minutes))
    }
}
