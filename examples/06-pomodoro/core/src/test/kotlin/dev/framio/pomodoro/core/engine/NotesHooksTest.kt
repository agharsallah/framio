package dev.framio.pomodoro.core.engine

import dev.framio.pomodoro.core.Driver
import dev.framio.pomodoro.core.FakeClock
import dev.framio.pomodoro.core.log.SessionLog
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/** The engine's hooks for the notes journal (DESIGN-history pending changes P1–P3). */
class NotesHooksTest {
    private fun deep() = Driver(FakeClock(), Settings(selectedMode = Presets.DEEP_ID)).also { it.send(Input.Begin("Thesis")) }

    @Test
    fun `parking a thought tells the journal the session and the focus time`() {
        val d = deep()
        d.clock.advance(12.minutes)
        d.send(Input.Park("a0b1"))
        val e = d.all<Effect.ThoughtParked>().single()
        assertEquals(d.state.session?.id, e.session)
        assertEquals(12.minutes, e.at)
        assertEquals(d.state.session?.parked?.single(), e.thought)
    }

    @Test
    fun `clearing one or all parked thoughts tells the journal which`() {
        val d = deep()
        d.send(Input.Park("a0"))
        d.send(Input.Park("b1"))
        d.send(Input.Park("c2"))
        val all = d.state.session?.parked.orEmpty()
        d.send(Input.ClearParked(1))
        assertEquals(listOf(Effect.ThoughtsCleared(listOf(all[1]))), d.all<Effect.ThoughtsCleared>())
        d.send(Input.ClearParked(null))
        assertEquals(Effect.ThoughtsCleared(listOf(all[0], all[2])), d.all<Effect.ThoughtsCleared>().last())
        val before = d.effects.size
        d.send(Input.ClearParked(null)) // nothing left: no effect
        assertTrue(d.effects.drop(before).none { it is Effect.ThoughtsCleared })
    }

    @Test
    fun `a handwritten resume note on ending early is logged and remembered, and survives a kill`() {
        val d = deep()
        d.clock.advance(20.minutes)
        d.send(Input.EndEarly(resumeNote = null, resumeInk = "ink1"))
        val r = d.logged.single()
        assertEquals("ink1", r.resumeInk)
        assertNull(r.resumeNote)
        assertEquals("ink1", d.state.lastResumeInk)
        assertEquals(r, SessionLog.decode(SessionLog.encode(r)).records.single())
        val snap = CoreJson.decodeSnapshot(CoreJson.encodeSnapshot(d.state.snapshot()))
        assertEquals("ink1", snap?.lastResumeInk)
    }

    @Test
    fun `a new resume note replaces words and ink together, none keeps the last`() {
        val d = deep()
        d.send(Input.EndEarly(resumeNote = "figures", resumeInk = "ink1"))
        d.send(Input.Begin("Thesis"))
        d.send(Input.EndEarly())
        assertEquals("figures" to "ink1", d.state.lastResumeNote to d.state.lastResumeInk)
        d.send(Input.Begin("Thesis"))
        d.pass(50.minutes)
        d.send(Input.WrapUp(Outcome.Done, resumeInk = "ink2"))
        assertNull(d.state.lastResumeNote)
        assertEquals("ink2", d.state.lastResumeInk)
    }

    @Test
    fun `the session stays known through Deep's wrap-up and Classic's pending break card`() {
        val d = deep()
        val id = d.state.session?.id
        d.pass(50.minutes)
        assertTrue(d.state.phase is Phase.WrapUp)
        assertEquals(id, d.state.session?.id)

        val c = Driver(FakeClock(), Settings(selectedMode = Presets.CLASSIC_ID))
        c.send(Input.Begin())
        val cid = c.state.session?.id
        c.pass(25.minutes)
        val p = c.state.phase as Phase.Break
        assertTrue(p.pendingWrapUp)
        assertNotNull(cid)
        assertEquals(cid, c.state.session?.id)
    }
}
