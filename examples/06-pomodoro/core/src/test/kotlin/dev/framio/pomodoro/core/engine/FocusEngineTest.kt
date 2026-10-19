@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.framio.pomodoro.core.engine

import app.cash.turbine.test
import dev.framio.pomodoro.core.RecordingEffects
import dev.framio.pomodoro.core.VirtualClock
import dev.framio.pomodoro.core.model.CueStyle
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.core.rhythm.BreakKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The engine on coroutines-test virtual time: the scheduler's delays and the clock move together. */
class FocusEngineTest {
    private fun TestScope.engine(
        clock: VirtualClock,
        effects: RecordingEffects,
        scope: CoroutineScope = backgroundScope,
        config: EngineConfig = EngineConfig.Default,
    ) = FocusEngine(clock, effects, scope, StandardTestDispatcher(testScheduler), config)

    private fun TestScope.pass(d: Duration) {
        advanceTimeBy(d)
        runCurrent()
    }

    @Test
    fun `a full classic cycle`() = runTest {
        val effects = RecordingEffects()
        val engine = engine(VirtualClock(testScheduler), effects)
        engine.state.test {
            assertFalse(awaitItem().loaded)
            engine.submit(Input.Restore(null, Settings(selectedMode = Presets.CLASSIC_ID)))
            assertEquals(Phase.Idle, awaitItem().phase)
            engine.submit(Input.Begin("Thesis"))
            assertTrue(awaitItem().phase is Phase.Focus)

            pass(25.minutes - 1.milliseconds)
            expectNoEvents() // no ticker: nothing happens until the deadline
            pass(1.milliseconds)
            val brk = awaitItem().phase as Phase.Break
            assertEquals(BreakKind.Short, brk.kind)
            assertTrue(brk.pendingWrapUp)
            assertTrue(effects.effects.containsAll(listOf(Effect.PlayCue(CueKind.FocusEnd, CueStyle.Soft), Effect.BringToFront)))

            engine.submit(Input.WrapUp(Outcome.Done, rating = 4))
            assertFalse((awaitItem().phase as Phase.Break).pendingWrapUp)
            pass(5.minutes)
            assertEquals(Phase.Ready, awaitItem().phase)

            engine.submit(Input.StartNext)
            assertTrue(awaitItem().phase is Phase.Focus)
            engine.submit(Input.EndEarly("tests next"))
            val idle = awaitItem()
            assertEquals(Phase.Idle, idle.phase)
            assertEquals("tests next", idle.lastResumeNote)
            pass(2.hours)
            expectNoEvents()
        }
        assertEquals(listOf(Outcome.Done, Outcome.EndedEarly), effects.all<Effect.AppendLog>().map { it.record.outcome })
        assertEquals(Effect.StopService, effects.effects.last { it == Effect.StartService || it == Effect.StopService })
        assertEquals(Effect.CancelWake, effects.all<Effect>().last { it is Effect.ScheduleWake || it is Effect.CancelWake })
    }

    @Test
    fun `a full deep cycle`() = runTest {
        val effects = RecordingEffects()
        val engine = engine(VirtualClock(testScheduler), effects)
        engine.state.test {
            skipItems(1)
            engine.submit(Input.Restore(null, Settings()))
            assertEquals(Presets.DEEP_ID, awaitItem().settings.selectedMode)
            engine.submit(Input.OpenRitual())
            assertEquals(Phase.Ritual(Presets.DEEP_ID), awaitItem().phase)
            engine.submit(Input.Begin("Rewrite the intro", ifThen = "close mail"))
            assertTrue(awaitItem().phase is Phase.Focus)

            pass(30.minutes)
            engine.submit(Input.Pause)
            assertTrue(awaitItem().phase is Phase.Paused)
            pass(10.minutes) // the pause cap
            assertTrue((awaitItem().phase as Phase.Paused).overCap)
            engine.submit(Input.Resume)
            assertTrue(awaitItem().phase is Phase.Focus)

            pass(20.minutes)
            assertTrue(awaitItem().phase is Phase.WrapUp)
            pass(1.hours)
            expectNoEvents() // the wrap-up waits for you
            engine.submit(Input.WrapUp(Outcome.Partly, rating = 3, resumeNote = "figures"))
            val brk = awaitItem().phase as Phase.Break
            assertEquals(10.minutes, brk.length)
            pass(10.minutes)
            assertEquals(Phase.Ready, awaitItem().phase)
        }
        val record = effects.all<Effect.AppendLog>().single().record
        assertEquals(Outcome.Partly, record.outcome)
        assertEquals(50.minutes, record.focused)
        assertEquals("Rewrite the intro", record.intention)
        val displays = effects.all<Effect.SetDisplay>()
        assertEquals(
            listOf(
                Effect.SetDisplay(DisplayLevel.Calm), // focus
                Effect.SetDisplay(DisplayLevel.Normal), // pause
                Effect.SetDisplay(DisplayLevel.Calm), // resume
                Effect.SetDisplay(DisplayLevel.Normal, Derive.SUNRISE), // the block ends
                Effect.SetDisplay(DisplayLevel.Dim), // break
                Effect.SetDisplay(DisplayLevel.Normal), // ready
            ),
            displays.drop(1), // the first is the restore sync
        )
        assertEquals(listOf(CueKind.PauseCap, CueKind.FocusEnd, CueKind.BreakEnd), effects.all<Effect.PlayCue>().map { it.kind })
    }

    @Test
    fun `kill and restore from the snapshot`() = runTest {
        val clock = VirtualClock(testScheduler)
        val before = RecordingEffects()
        val job = Job()
        val first = engine(clock, before, CoroutineScope(backgroundScope.coroutineContext + job))
        first.submit(Input.Restore(null, Settings(selectedMode = Presets.CLASSIC_ID)))
        first.submit(Input.Begin("Thesis"))
        pass(10.minutes)
        val disk = CoreJson.encodeSnapshot(before.all<Effect.Persist>().last().snapshot)
        job.cancel() // am kill

        pass(3.minutes)
        val after = RecordingEffects()
        val second = engine(clock, after)
        second.state.test {
            skipItems(1)
            second.submit(Input.Restore(CoreJson.decodeSnapshot(disk), Settings(selectedMode = Presets.CLASSIC_ID)))
            val restored = awaitItem()
            assertTrue(restored.phase is Phase.Focus)
            assertEquals(12.minutes, Timing.left(restored, clock.now()))
            pass(12.minutes)
            assertTrue(awaitItem().phase is Phase.Break)
        }
        assertEquals(25.minutes, second.state.value.session?.focused)
        assertTrue(after.effects.contains(Effect.StartService))
    }

    @Test
    fun `a paused block survives a reboot, a running one is interrupted`() = runTest {
        val clock = VirtualClock(testScheduler)
        val before = RecordingEffects()
        val job = Job()
        val first = engine(clock, before, CoroutineScope(backgroundScope.coroutineContext + job))
        first.submit(Input.Restore(null, Settings(selectedMode = Presets.CLASSIC_ID)))
        first.submit(Input.Begin("Thesis"))
        pass(10.minutes)
        first.submit(Input.Pause) // the last write, 10 minutes in
        val snapshot = before.all<Effect.Persist>().last().snapshot
        job.cancel()
        clock.reboot()

        val after = RecordingEffects()
        val second = engine(clock, after)
        second.submit(Input.Restore(snapshot, Settings(selectedMode = Presets.CLASSIC_ID)))
        // a paused block has no deadline to lose: it stays paused
        assertTrue(second.state.value.phase is Phase.Paused)
        second.submit(Input.Resume)
        assertEquals(15.minutes, Timing.left(second.state.value, clock.now()))

        // now running, with a deadline from this untrusted boot: the next reboot loses it
        val running = after.all<Effect.Persist>().last().snapshot
        clock.reboot()
        val third = RecordingEffects()
        val engine3 = engine(clock, third)
        engine3.submit(Input.Restore(running, Settings(selectedMode = Presets.CLASSIC_ID)))
        assertEquals(Phase.Idle, engine3.state.value.phase)
        assertEquals(Notice.Interrupted, engine3.state.value.notice)
        val record = third.all<Effect.AppendLog>().single().record
        assertEquals(Outcome.Interrupted, record.outcome)
        assertEquals(10.minutes, record.focused)
    }

    @Test
    fun `flow nudges once on virtual time, silently`() = runTest {
        val effects = RecordingEffects()
        val engine = engine(VirtualClock(testScheduler), effects)
        engine.submit(Input.Restore(null, Settings(selectedMode = Presets.FLOW_ID)))
        engine.submit(Input.Begin())
        pass(90.minutes - 1.seconds)
        assertEquals(false, engine.state.value.session?.nudged)
        pass(1.seconds)
        assertEquals(true, engine.state.value.session?.nudged)
        pass(3.hours)
        assertTrue(effects.all<Effect.PlayCue>().isEmpty())
        engine.submit(Input.Complete)
        assertEquals(10.minutes, (engine.state.value.phase as Phase.Break).length)
    }

    @Test
    fun `minutes as seconds for device tests`() = runTest {
        val effects = RecordingEffects()
        val engine = engine(VirtualClock(testScheduler), effects, config = EngineConfig.MinutesAsSeconds)
        engine.submit(Input.Restore(null, Settings(selectedMode = Presets.CLASSIC_ID)))
        engine.submit(Input.Begin("x"))
        pass(25.seconds)
        assertTrue(engine.state.value.phase is Phase.Break)
        pass(5.seconds)
        assertEquals(Phase.Ready, engine.state.value.phase)
        assertEquals(25.minutes, effects.all<Effect.AppendLog>().single().record.focused)
    }

    @Test
    fun `inputs before the restore and failing effect handlers don't break the engine`() = runTest {
        val clock = VirtualClock(testScheduler)
        val log = mutableListOf<String>()
        var fail = true
        val engine = FocusEngine(
            clock,
            { if (fail && it is Effect.StartService) error("service refused") },
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            log = { log += it },
        )
        val early = engine.submit(Input.Begin("x"))
        assertTrue(early.rejected != null)
        assertFalse(engine.state.value.loaded)
        engine.submit(Input.Restore(null, Settings(selectedMode = Presets.CLASSIC_ID)))
        engine.submit(Input.Begin("x"))
        assertTrue(engine.state.value.phase is Phase.Focus)
        assertTrue(log.any { it.contains("service refused") })
        fail = false
        pass(25.minutes)
        assertTrue(engine.state.value.phase is Phase.Break)
        assertTrue(engine.dispatch(Input.SkipBreak))
        runCurrent()
        assertEquals(Phase.Ready, engine.state.value.phase)
    }
}
