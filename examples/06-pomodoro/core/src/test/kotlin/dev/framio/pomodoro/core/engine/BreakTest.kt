package dev.framio.pomodoro.core.engine

import dev.framio.pomodoro.core.Driver
import dev.framio.pomodoro.core.FakeClock
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** M4: the break's +5 min, its prompts, and Flow's tier through a whole block. */
class BreakTest {
    @Test
    fun `plus five moves the deadline and the length, and the break ends later`() {
        val d = Driver(FakeClock(), Settings(selectedMode = Presets.CLASSIC_ID))
        d.send(Input.Begin("Write"))
        d.pass(25.minutes)
        d.pass(2.minutes)
        val extended = d.send(Input.ExtendBreak())
        val p = d.state.phase as Phase.Break
        assertEquals(10.minutes, p.length)
        assertEquals(8.minutes, Timing.left(d.state, d.clock.now()))
        assertTrue(extended.effects.any { it is Effect.ScheduleWake })
        d.pass(3.minutes + 59.seconds)
        assertTrue(d.state.phase is Phase.Break)
        d.pass(4.minutes + 1.seconds)
        assertEquals(Phase.Ready, d.state.phase)
    }

    @Test
    fun `plus five in a time-scaled run is five logical minutes`() {
        val d = Driver(FakeClock(), Settings(selectedMode = Presets.CLASSIC_ID), EngineConfig.MinutesAsSeconds)
        d.send(Input.Begin("Write"))
        d.pass(25.seconds)
        d.send(Input.ExtendBreak())
        assertEquals(10.minutes, Timing.left(d.state, d.clock.now(), d.config))
        d.pass(10.seconds)
        assertEquals(Phase.Ready, d.state.phase)
    }

    @Test
    fun `short breaks rotate the four prompts, the long one is about messages`() {
        val d = Driver(FakeClock(), Settings(selectedMode = Presets.CLASSIC_ID))
        d.send(Input.Begin("Write"))
        val prompts = (1..5).map {
            d.pass(25.minutes)
            val prompt = (d.state.phase as Phase.Break).prompt
            d.send(Input.SkipBreak)
            d.send(Input.StartNext)
            prompt
        }
        assertEquals(listOf(BreakPrompt.Move, BreakPrompt.Window, BreakPrompt.Water, BreakPrompt.Messages, BreakPrompt.Move), prompts)
    }

    @Test
    fun `a flow block of 37 minutes earns the 8 minute tier, and the session keeps what was worked during the break`() {
        val d = Driver(FakeClock(), Settings(selectedMode = Presets.FLOW_ID))
        d.send(Input.Begin("Write"))
        d.clock.advance(37.minutes)
        d.send(Input.Complete)
        val p = d.state.phase as Phase.Break
        assertEquals(8.minutes, p.length)
        assertEquals(37.minutes, d.state.session?.focused)
        assertTrue(p.pendingWrapUp)
    }

    @Test
    fun `gentle is ten and three, and waits on Ready for another ten`() {
        val d = Driver(FakeClock(), Settings(selectedMode = Presets.GENTLE_ID))
        d.send(Input.Begin())
        assertEquals(10.minutes, Timing.left(d.state, d.clock.now()))
        d.pass(10.minutes)
        assertEquals(3.minutes, (d.state.phase as Phase.Break).length)
        d.pass(3.minutes)
        assertEquals(Phase.Ready, d.state.phase)
        d.send(Input.StartNext)
        assertEquals(10.minutes, Timing.left(d.state, d.clock.now()))
        assertEquals(1, d.state.completedToday)
    }
}
