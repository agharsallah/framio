package dev.framio.hub.core.engine

import dev.framio.hub.core.FakeClock
import dev.framio.hub.core.model.HubState
import dev.framio.hub.core.model.Run
import dev.framio.hub.core.timers.remainingMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Applies inputs to a state with a fake clock, keeping every effect. */
class Harness(var state: HubState = HubState(), val clock: FakeClock = FakeClock()) {
    val effects = mutableListOf<Effect>()
    var lastRejected: String? = null
    private var n = 0

    fun send(command: Command): Next = apply(Input.Wire(command))

    fun apply(input: Input): Next {
        val next = reduce(state, input, clock.now())
        state = next.state
        effects += next.effects
        lastRejected = next.rejected
        return next
    }

    fun tick() = apply(Input.Tick)

    fun id() = "c${++n}"

    fun startTimer(label: String = "Pasta", ms: Long = 8 * 60_000): String = id().also { send(TimerStart(it, "test", label, ms)) }

    fun timer(id: String) = state.timers.single { it.id == id }
}

class ReduceTimersTest {
    private val h = Harness()

    @Test fun `a started timer runs with its own colour and melody`() {
        val a = h.startTimer("Pasta")
        val b = h.startTimer("Eggs")
        assertTrue(h.timer(a).run is Run.Running)
        assertTrue(h.timer(a).color != h.timer(b).color)
        assertTrue(h.timer(a).melody != h.timer(b).melody)
        assertEquals(8 * 60_000L, h.timer(a).remainingMs(h.clock.now()))
    }

    @Test fun `a timer rings when it ends, then goes quiet after two minutes`() {
        val a = h.startTimer(ms = 60_000)
        h.clock.advance(59_999)
        h.tick()
        assertTrue(h.timer(a).run is Run.Running)
        h.clock.advance(1)
        h.tick()
        assertTrue(h.timer(a).run is Run.Ringing)
        assertTrue(h.effects.contains(Effect.Ring(a, h.timer(a).melody)))
        h.clock.advance(Rules.RING_MS)
        h.tick()
        assertTrue(h.timer(a).run is Run.Done)
        assertEquals(Effect.Silence, h.effects.last { it is Effect.Ring || it is Effect.Silence })
    }

    @Test fun `stop removes the ringing timer on top, and the next one rings`() {
        val a = h.startTimer("Pasta", 60_000)
        h.clock.advance(1_000)
        val b = h.startTimer("Eggs", 60_000)
        h.clock.advance(60_000)
        h.tick()
        // both ring; Eggs started ringing last, so it's on top
        assertEquals(b, topRinging(h.state, h.clock.now())?.id)
        h.send(TimerStop(h.id(), "test"))
        assertEquals(listOf(a), h.state.timers.map { it.id })
        assertEquals(Effect.Ring(a, h.timer(a).melody), h.effects.last { it is Effect.Ring || it is Effect.Silence })
        h.apply(Input.DoubleKnock)
        assertTrue(h.state.timers.isEmpty())
        assertEquals(Effect.Silence, h.effects.last { it is Effect.Ring || it is Effect.Silence })
    }

    @Test fun `pause holds the remaining time and resume continues it`() {
        val a = h.startTimer(ms = 120_000)
        h.clock.advance(20_000)
        h.send(TimerPause(h.id(), "test", a))
        h.clock.advance(600_000)
        h.tick()
        assertEquals(Run.Paused(100_000), h.timer(a).run)
        h.send(TimerResume(h.id(), "test", a))
        assertEquals(100_000L, h.timer(a).remainingMs(h.clock.now()))
    }

    @Test fun `plus one minute extends a running timer and restarts a ringing one`() {
        val a = h.startTimer(ms = 60_000)
        h.send(TimerAdd(h.id(), "test", a, 60_000))
        assertEquals(120_000L, h.timer(a).remainingMs(h.clock.now()))
        h.clock.advance(120_000)
        h.tick()
        assertTrue(h.timer(a).run is Run.Ringing)
        h.send(TimerAdd(h.id(), "test", a, 60_000))
        assertEquals(60_000L, h.timer(a).remainingMs(h.clock.now()))
        assertEquals(Effect.Silence, h.effects.last { it is Effect.Ring || it is Effect.Silence })
    }

    @Test fun `a timer survives the process dying in the same boot`() {
        val a = h.startTimer(ms = 300_000)
        val stored = StateCodec.decode(StateCodec.encode(h.state)) // what the store would give back
        h.clock.advance(100_000)
        val revived = Harness(stored, h.clock)
        revived.tick()
        assertEquals(200_000L, revived.timer(a).remainingMs(h.clock.now()))
    }

    @Test fun `a timer survives a reboot once the clock is trusted again`() {
        val a = h.startTimer(ms = 600_000)
        h.clock.reboot(downMs = 60_000)
        h.tick()
        assertTrue("unknown until the time is trusted, but not lost", h.timer(a).run is Run.Running)
        assertNull(h.timer(a).remainingMs(h.clock.now()))
        h.clock.advance(5_000)
        h.clock.trust()
        h.apply(Input.TimeTrusted)
        val run = h.timer(a).run as Run.Running
        assertEquals("rebased onto the new boot", h.clock.bootId, run.until.bootId)
        assertEquals(600_000L - 60_000 - 5_000, h.timer(a).remainingMs(h.clock.now()))
    }

    @Test fun `a timer started before the clock was trusted is marked interrupted after a reboot`() {
        h.clock.wallMs = null
        val a = h.startTimer(ms = 600_000)
        h.clock.reboot()
        h.clock.trust()
        h.tick()
        assertEquals(Run.Interrupted, h.timer(a).run)
        h.send(TimerClear(h.id(), "test"))
        assertTrue(h.state.timers.isEmpty())
    }

    @Test fun `a timer that ended long before the Hub reopened is finished, not ringing`() {
        val a = h.startTimer(ms = 60_000)
        h.clock.advance(60_000 + Rules.RING_MS + 1)
        h.tick()
        assertTrue(h.timer(a).run is Run.Done)
        assertTrue(h.effects.none { it is Effect.Ring })
    }

    @Test fun `finished chips disappear after six hours`() {
        val a = h.startTimer(ms = 60_000)
        h.clock.advance(60_000 + Rules.RING_MS)
        h.tick()
        assertTrue(h.timer(a).run is Run.Done)
        h.clock.advance(Rules.DONE_KEEP_MS)
        h.tick()
        assertTrue(h.state.timers.isEmpty())
    }

    @Test fun `limits are enforced with a reason`() {
        h.send(TimerStart(h.id(), "test", "x", 0))
        assertEquals("duration must be 1 s to 24 h", h.lastRejected)
        repeat(HubState.MAX_TIMERS) { h.startTimer() }
        h.send(TimerStart(h.id(), "test", "one more", 60_000))
        assertEquals("at most 8 timers", h.lastRejected)
        assertEquals(HubState.MAX_TIMERS, h.state.timers.size)
    }

    @Test fun `labels are trimmed and a blank one gets a name`() {
        val a = h.startTimer("   ")
        assertEquals("Timer", h.timer(a).label)
        val b = h.startTimer("x".repeat(100))
        assertEquals(Rules.MAX_LABEL, h.timer(b).label.length)
    }
}
