package dev.framio.pomodoro.core.engine

import dev.framio.pomodoro.core.Driver
import dev.framio.pomodoro.core.FakeClock
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.core.time.remaining
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/** A process kill or a reboot, then a fresh engine restoring the last persisted snapshot. */
class RestoreTest {
    private val clock = FakeClock()
    private val settings = Settings(selectedMode = Presets.CLASSIC_ID)
    private val first = Driver(clock, settings)

    /** The snapshot the app would have on disk: the last Persist, through JSON. */
    private fun onDisk(): Snapshot {
        val last = first.all<Effect.Persist>().last().snapshot
        return CoreJson.decodeSnapshot(CoreJson.encodeSnapshot(last)) ?: error("unreadable")
    }

    private fun restore(snapshot: Snapshot?): Next = reduce(FocusState(), Input.Restore(snapshot, settings), clock.now())

    @Test
    fun `after a kill the block continues where it was, and the app resyncs`() {
        first.send(Input.Begin("Thesis"))
        clock.advance(10.minutes)
        val next = restore(onDisk())
        val f = next.state.phase as Phase.Focus
        assertEquals(15.minutes, f.deadline?.remaining(clock.now()))
        assertTrue(Effect.StartService in next.effects)
        assertTrue(Effect.ScheduleWake(f.deadline ?: error("none")) in next.effects)
        assertTrue(Effect.SetDisplay(DisplayLevel.Normal) in next.effects)
        assertTrue(next.effects.none { it is Effect.Persist }) // nothing changed
    }

    @Test
    fun `a deadline that passed while dead ends the block at the deadline`() {
        first.send(Input.Begin("Thesis"))
        clock.advance(40.minutes)
        val next = restore(onDisk())
        assertTrue(next.state.phase is Phase.Break)
        assertEquals(25.minutes, next.state.session?.focused)
        assertEquals(1, next.state.completedToday)
        assertTrue(next.effects.any { it is Effect.Persist })
    }

    @Test
    fun `a reboot before the clock is trusted interrupts the block and logs what is known`() {
        first.send(Input.Begin("Thesis"))
        clock.advance(10.minutes)
        first.send(Input.Park("00")) // the last snapshot write, 10 minutes in
        clock.advance(5.minutes)
        clock.reboot()
        val next = restore(onDisk())
        assertEquals(Phase.Idle, next.state.phase)
        assertEquals(Notice.Interrupted, next.state.notice)
        val r = next.effects.filterIsInstance<Effect.AppendLog>().single().record
        assertEquals(Outcome.Interrupted, r.outcome)
        assertEquals(10.minutes, r.focused)
        assertEquals(1, r.parkedCount)
        assertTrue(Effect.StopService in next.effects)
    }

    @Test
    fun `a reboot with a trusted clock resumes the block by wall time`() {
        first.send(Input.Begin("Thesis"))
        clock.advance(10.minutes)
        clock.reboot(down = 2.minutes)
        clock.trust()
        val next = restore(onDisk())
        val f = next.state.phase as Phase.Focus
        assertEquals(clock.bootId, f.deadline?.bootId)
        assertEquals(13.minutes, f.deadline?.remaining(clock.now()))
    }

    @Test
    fun `a break that ran out while dead waits on Ready and logs the block`() {
        first.send(Input.Begin("Thesis"))
        first.pass(25.minutes)
        clock.advance(10.minutes)
        val next = restore(onDisk())
        assertEquals(Phase.Ready, next.state.phase)
        assertEquals(Outcome.Unrated, next.effects.filterIsInstance<Effect.AppendLog>().single().record.outcome)
    }

    @Test
    fun `a pause cap lost in a reboot counts as reached`() {
        val deep = Driver(clock, Settings(selectedMode = Presets.DEEP_ID))
        deep.send(Input.Begin("x"))
        deep.send(Input.Pause)
        val snap = deep.all<Effect.Persist>().last().snapshot
        clock.reboot()
        val next = reduce(FocusState(), Input.Restore(snap, settings), clock.now())
        val p = next.state.phase as Phase.Paused
        assertTrue(p.overCap)
        assertNull(Derive.wake(next.state))
    }

    @Test
    fun `no snapshot or an inconsistent one starts clean`() {
        assertEquals(Phase.Idle, restore(null).state.phase)
        val broken = Snapshot(phase = Phase.Ready, run = null, lastResumeNote = "keep")
        val next = restore(broken)
        assertEquals(Phase.Idle, next.state.phase)
        assertEquals("keep", next.state.lastResumeNote)
        assertNull(CoreJson.decodeSnapshot("""{"phase":{"type":"warp"}}"""))
    }
}
