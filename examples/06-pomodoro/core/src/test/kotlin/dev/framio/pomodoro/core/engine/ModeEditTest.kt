package dev.framio.pomodoro.core.engine

import dev.framio.pomodoro.core.Driver
import dev.framio.pomodoro.core.FakeClock
import dev.framio.pomodoro.core.model.Display
import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the mode editor sends: save, move, delete. */
class ModeEditTest {
    private fun ids(d: Driver) = d.state.settings.modes.map { it.id.value }

    @Test
    fun `move a mode along the carousel, presets included, clamped at the ends`() {
        val d = Driver(FakeClock(), Settings())
        d.send(Input.SaveMode(Presets.deep.duplicate(ModeId("own-1"), "Late")))
        assertEquals(listOf("classic", "deep", "flow", "gentle", "own-1"), ids(d))
        d.send(Input.MoveMode(ModeId("own-1"), -4))
        assertEquals(listOf("own-1", "classic", "deep", "flow", "gentle"), ids(d))
        d.send(Input.MoveMode(Presets.CLASSIC_ID, 10))
        assertEquals(listOf("own-1", "deep", "flow", "gentle", "classic"), ids(d))
        d.send(Input.MoveMode(Presets.CLASSIC_ID, 1)) // already last: nothing changes, nothing is written
        assertEquals(0, d.send(Input.MoveMode(Presets.CLASSIC_ID, 1)).effects.size)
        d.reject(Input.MoveMode(ModeId("nope"), 1))
        // The order is saved, and a settings change later keeps it
        assertEquals(listOf("own-1", "deep", "flow", "gentle", "classic"), d.all<Effect.SaveSettings>().last().settings.modes.map { it.id.value })
        d.send(Input.ChangeSettings(d.state.settings.copy(dailyTarget = 4)))
        assertEquals(listOf("own-1", "deep", "flow", "gentle", "classic"), ids(d))
    }

    @Test
    fun `changing a mode's display forgets the ritual's remembered screen for it`() {
        val d = Driver(FakeClock(), Settings())
        val mine = Presets.deep.duplicate(ModeId("own-1"), "Late")
        d.send(Input.SaveMode(mine))
        d.send(Input.SelectMode(mine.id))
        d.send(Input.OpenRitual("Write"))
        d.send(Input.Begin("Write", checked = emptySet(), display = Display.Blackout))
        d.send(Input.EndEarly())
        assertEquals(Display.Blackout, d.state.ritual(mine.id)?.display)
        d.send(Input.SaveMode(mine.copy(name = "Later"))) // same display: remembered choice stays
        assertEquals(Display.Blackout, d.state.ritual(mine.id)?.display)
        d.send(Input.SaveMode(mine.copy(environment = mine.environment.copy(display = Display.Full))))
        assertNull(d.state.ritual(mine.id)?.display)
        assertEquals(1, d.state.ritual(mine.id)?.runs) // the rest of the memory stays
    }

    @Test
    fun `an invalid edit is refused and changes nothing`() {
        val d = Driver(FakeClock(), Settings())
        val mine = Presets.classic.duplicate(ModeId("own-1"), "Mine")
        d.send(Input.SaveMode(mine))
        d.reject(Input.SaveMode(mine.copy(name = "")))
        d.reject(Input.SaveMode(mine.copy(accent = 9)))
        assertEquals("Mine", d.state.settings.mode(mine.id)?.name)
    }
}
