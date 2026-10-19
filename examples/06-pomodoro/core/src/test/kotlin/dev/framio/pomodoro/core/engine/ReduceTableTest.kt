package dev.framio.pomodoro.core.engine

import dev.framio.pomodoro.core.Driver
import dev.framio.pomodoro.core.FakeClock
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/** Every phase × every loop input: which are accepted, and that a rejection changes nothing. */
class ReduceTableTest {
    private val inputs: List<Pair<String, Input>> = listOf(
        "SelectMode" to Input.SelectMode(Presets.CLASSIC_ID),
        "OpenRitual" to Input.OpenRitual(),
        "CancelRitual" to Input.CancelRitual,
        "Begin" to Input.Begin("Write"),
        "Pause" to Input.Pause,
        "Resume" to Input.Resume,
        "EndEarly" to Input.EndEarly(),
        "Complete" to Input.Complete,
        "Extend" to Input.Extend(),
        "Park" to Input.Park("00"),
        "WrapUp" to Input.WrapUp(),
        "SkipBreak" to Input.SkipBreak,
        "ExtendBreak" to Input.ExtendBreak(),
        "StartNext" to Input.StartNext,
        "Home" to Input.Home,
    )

    /** A driver in each phase, Classic mode unless the phase needs Deep (wrap-up) or Flow. */
    private fun at(phase: String): Driver {
        val mode = when (phase) {
            "WrapUp" -> Presets.DEEP_ID
            "FlowFocus" -> Presets.FLOW_ID
            else -> Presets.CLASSIC_ID
        }
        val d = Driver(FakeClock(), Settings(selectedMode = mode))
        when (phase) {
            "Idle" -> Unit
            "Ritual" -> d.send(Input.OpenRitual())
            "Focus", "FlowFocus" -> d.send(Input.Begin("Write"))
            "Paused" -> { d.send(Input.Begin("Write")); d.send(Input.Pause) }
            "WrapUp" -> { d.send(Input.Begin("Write")); d.pass(50.minutes) }
            "Break" -> { d.send(Input.Begin("Write")); d.pass(25.minutes) }
            "Ready" -> { d.send(Input.Begin("Write")); d.pass(25.minutes); d.pass(5.minutes) }
            else -> error(phase)
        }
        return d
    }

    private val accepted = mapOf(
        "Idle" to setOf("SelectMode", "OpenRitual", "Begin"),
        "Ritual" to setOf("SelectMode", "CancelRitual", "Begin", "Home"),
        "Focus" to setOf("Pause", "EndEarly", "Extend", "Park"),
        "FlowFocus" to setOf("Pause", "EndEarly", "Complete", "Park"),
        "Paused" to setOf("Resume", "EndEarly", "Extend", "Park"),
        "WrapUp" to setOf("WrapUp", "SkipBreak", "Home"),
        "Break" to setOf("WrapUp", "SkipBreak", "ExtendBreak", "Home"),
        "Ready" to setOf("OpenRitual", "Begin", "StartNext", "Home"),
    )

    @Test
    fun `phase by input table`() {
        val problems = mutableListOf<String>()
        for ((phase, ok) in accepted) {
            for ((name, input) in inputs) {
                val d = at(phase)
                val before = d.state
                val next = reduce(before, input, d.clock.now())
                val wasAccepted = next.rejected == null
                if (wasAccepted != (name in ok)) problems += "$phase × $name: ${if (wasAccepted) "accepted" else "rejected (${next.rejected})"}"
                if (!wasAccepted && (next.state != before || next.effects.isNotEmpty())) problems += "$phase × $name: rejection changed state"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `phases reached by the table setup`() {
        assertEquals(Phase.Idle, at("Idle").state.phase)
        assertTrue(at("Ritual").state.phase is Phase.Ritual)
        assertTrue(at("Focus").state.phase is Phase.Focus)
        assertTrue(at("Paused").state.phase is Phase.Paused)
        assertTrue(at("WrapUp").state.phase is Phase.WrapUp)
        assertTrue(at("Break").state.phase is Phase.Break)
        assertEquals(Phase.Ready, at("Ready").state.phase)
    }

    @Test
    fun `scheduler and housekeeping inputs are accepted in every phase`() {
        for (phase in accepted.keys) {
            for (input in listOf(Input.DeadlineReached, Input.TimeTrusted, Input.DismissNotice, Input.AddTask("Taxes"), Input.ChangeSettings(Settings()), Input.ClearParked(), Input.SetRitualSkip(Presets.DEEP_ID, false), Input.MoveMode(Presets.DEEP_ID, 1))) {
                val d = at(phase)
                assertEquals("$phase × $input", null, reduce(d.state, input, d.clock.now()).rejected)
            }
        }
    }

    @Test
    fun `nothing but Restore is accepted before loading`() {
        val clock = FakeClock()
        for ((_, input) in inputs) assertNotNull(reduce(FocusState(), input, clock.now()).rejected)
        assertNotNull(reduce(FocusState(), Input.DeadlineReached, clock.now()).rejected)
        assertTrue(reduce(FocusState(), Input.Restore(null, Settings()), clock.now()).state.loaded)
    }

    @Test
    fun `invalid arguments are rejected`() {
        val d = at("Focus")
        d.reject(Input.Extend((-1).minutes))
        d.reject(Input.Extend(120.minutes))
        d.reject(Input.Park(""))
        val w = at("WrapUp")
        w.reject(Input.WrapUp(rating = 0))
        w.reject(Input.WrapUp(rating = 6))
        w.reject(Input.WrapUp(outcome = dev.framio.pomodoro.core.model.Outcome.EndedEarly))
        val b = at("Break")
        b.reject(Input.ExtendBreak(0.minutes))
        b.reject(Input.ExtendBreak(31.minutes))
        repeat(17) { b.send(Input.ExtendBreak()) } // 5 + 85 = 90 min
        b.reject(Input.ExtendBreak(1.minutes))
        val i = at("Idle")
        i.reject(Input.SelectMode(dev.framio.pomodoro.core.model.ModeId("nope")))
        i.reject(Input.Begin("x", focusOverride = 0.minutes))
        i.reject(Input.Begin(taskId = dev.framio.pomodoro.core.model.TaskId("nope")))
    }
}
