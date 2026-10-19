package dev.framio.hub.core.engine

import dev.framio.hub.core.FakeClock
import dev.framio.hub.core.InMemoryStateStore
import dev.framio.hub.core.RecordingEffects
import dev.framio.hub.core.model.HubState
import dev.framio.hub.core.model.NoteBody
import dev.framio.hub.core.model.Run
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HubEngineTest {
    /** A clock that follows the test scheduler's virtual time. */
    private class VirtualClock(private val scope: TestScope, private val base: FakeClock = FakeClock()) : HubClock {
        override fun now(): Now = base.now().let { it.copy(elapsedMs = it.elapsedMs + scope.currentTime, wallMs = it.wallMs?.plus(scope.currentTime)) }
    }

    @Test fun `the engine loads stored state, applies commands in order and persists`() = runTest {
        val store = InMemoryStateStore(HubState(rev = 41))
        val effects = RecordingEffects()
        val engine = HubEngine(VirtualClock(this), store, effects, backgroundScope)
        runCurrent()
        assertTrue(engine.loaded.value)
        repeat(3) { engine.dispatch(NoteAdd("n$it", "test", NoteBody.Text("note $it"))) }
        runCurrent()
        assertEquals(listOf("n0", "n1", "n2"), engine.state.value.notes.map { it.id })
        assertEquals(44L, engine.state.value.rev)
        assertEquals(engine.state.value, store.stored)
        assertEquals(listOf(42L, 43L, 44L), effects.effects.filterIsInstance<Effect.Changed>().map { it.rev })
    }

    @Test fun `submit answers with the result, including why a command was refused`() = runTest {
        val engine = HubEngine(VirtualClock(this), InMemoryStateStore(), RecordingEffects(), backgroundScope)
        val next = engine.submit(Input.Wire(TimerStart("t", "lan", "Tea", 0)))
        assertEquals("duration must be 1 s to 24 h", next.rejected)
    }

    @Test fun `a failing effect handler doesn't stop the engine`() = runTest {
        val logs = mutableListOf<String>()
        val engine = HubEngine(VirtualClock(this), InMemoryStateStore(), { error("speaker on fire") }, backgroundScope, logs::add)
        engine.submit(Input.Wire(NoteAdd("a", "lan", NoteBody.Text("x"))))
        engine.submit(Input.Wire(NoteAdd("b", "lan", NoteBody.Text("y"))))
        assertEquals(2, engine.state.value.notes.size)
        assertTrue(logs.any { it.contains("speaker on fire") })
    }

    @Test fun `the scheduler rings a timer exactly when it ends`() = runTest {
        val clock = VirtualClock(this)
        val effects = RecordingEffects()
        val engine = HubEngine(clock, InMemoryStateStore(), effects, backgroundScope)
        Scheduler(engine, clock, backgroundScope)
        runCurrent()
        engine.submit(Input.Wire(TimerStart("tea", "frame", "Tea", 180_000)))
        advanceTimeBy(179_999)
        runCurrent()
        assertTrue(engine.state.value.timers.single().run is Run.Running)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(engine.state.value.timers.single().run is Run.Ringing)
        assertTrue(effects.effects.any { it is Effect.Ring })
        advanceTimeBy(Rules.RING_MS)
        runCurrent()
        assertTrue("goes quiet by itself", engine.state.value.timers.single().run is Run.Done)
        assertEquals(Effect.Silence, effects.effects.last { it is Effect.Ring || it is Effect.Silence })
        backgroundScope.cancel()
    }
}
