package dev.framio.pomodoro.core.engine

import dev.framio.pomodoro.core.Driver
import dev.framio.pomodoro.core.FakeClock
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/** M3: the ritual remembers its ticks and if-then line and can be skipped after its first run; the parking lot. */
class RitualTest {
    private val d = Driver(FakeClock(), Settings(selectedMode = Presets.DEEP_ID))

    @Test
    fun `opening the ritual carries Home's chosen task`() {
        d.send(Input.OpenRitual("  Thesis ch.3 "))
        assertEquals(Phase.Ritual(Presets.DEEP_ID, "Thesis ch.3"), d.state.phase)
        d.send(Input.CancelRitual)
        d.send(Input.OpenRitual("  "))
        assertNull((d.state.phase as Phase.Ritual).intention)
    }

    @Test
    fun `begin from the ritual remembers the mode's own ticks in order, and the if-then line`() {
        d.send(Input.OpenRitual())
        d.send(Input.Begin("Thesis", ifThen = " close the tab ", checked = setOf("water", "phone", "not-an-item")))
        val m = d.state.ritual(Presets.DEEP_ID) ?: error("not remembered")
        assertEquals(listOf("phone", "water"), m.ticks)
        assertEquals("close the tab", m.ifThen)
        assertEquals(1, m.runs)
        assertTrue(d.state.snapshot().rituals.isNotEmpty()) // survives a kill
    }

    @Test
    fun `a begin that didn't come from the ritual leaves the memory alone`() {
        d.send(Input.Begin("Thesis"))
        assertNull(d.state.ritual(Presets.DEEP_ID))
    }

    @Test
    fun `the ritual can be skipped only after its first run, and only if it has a screen`() {
        assertTrue(d.reject(Input.SetRitualSkip(Presets.DEEP_ID, true)).contains("first run"))
        d.send(Input.OpenRitual())
        d.send(Input.Begin("Thesis", checked = emptySet()))
        d.send(Input.EndEarly())
        assertFalse(d.state.skipsRitual(Presets.deep))
        d.send(Input.SetRitualSkip(Presets.DEEP_ID, true))
        assertTrue(d.state.skipsRitual(Presets.deep))
        assertEquals(1, d.state.ritual(Presets.DEEP_ID)?.runs)
        d.send(Input.SetRitualSkip(Presets.DEEP_ID, false))
        assertFalse(d.state.skipsRitual(Presets.deep))
        // Classic has no ritual screen (intention only): nothing to skip
        d.reject(Input.SetRitualSkip(Presets.CLASSIC_ID, true))
    }

    @Test
    fun `a ritual run keeps the skip choice`() {
        d.send(Input.OpenRitual())
        d.send(Input.Begin("Thesis", checked = setOf("phone")))
        d.send(Input.EndEarly())
        d.send(Input.SetRitualSkip(Presets.DEEP_ID, true))
        d.send(Input.OpenRitual())
        d.send(Input.Begin("Thesis", checked = emptySet()))
        assertEquals(true, d.state.ritual(Presets.DEEP_ID)?.skip)
        assertEquals(emptyList<String>(), d.state.ritual(Presets.DEEP_ID)?.ticks)
        assertEquals(2, d.state.ritual(Presets.DEEP_ID)?.runs)
    }

    @Test
    fun `deleting a mode forgets its ritual`() {
        val mine = Presets.deep.duplicate(dev.framio.pomodoro.core.model.ModeId("mine"), "Mine")
        d.send(Input.SaveMode(mine))
        d.send(Input.SelectMode(mine.id))
        d.send(Input.OpenRitual())
        d.send(Input.Begin("x", checked = setOf("water")))
        d.send(Input.EndEarly())
        d.send(Input.DeleteMode(mine.id))
        assertNull(d.state.ritual(mine.id))
    }
}

class ParkingLotTest {
    private val d = Driver(FakeClock(), Settings(selectedMode = Presets.DEEP_ID))

    private fun deepBlockWithThoughts(vararg ink: String) {
        d.send(Input.Begin("Thesis"))
        ink.forEach { d.send(Input.Park(it)) }
    }

    @Test
    fun `thoughts parked in focus show at the wrap-up and can be cleared one by one`() {
        deepBlockWithThoughts("aa", "bb", "cc")
        d.pass(50.minutes)
        assertTrue(d.state.phase is Phase.WrapUp)
        d.send(Input.ClearParked(1))
        assertEquals(listOf("aa", "cc"), d.state.session?.parked?.map { it.ink })
        d.reject(Input.ClearParked(5))
        d.send(Input.WrapUp(Outcome.Done))
        // kept through the break
        assertEquals(listOf("aa", "cc"), d.state.session?.parked?.map { it.ink })
        assertEquals(2, d.logged.single().parkedCount)
    }

    @Test
    fun `thoughts not cleared are kept when the block is over, and can be cleared later`() {
        deepBlockWithThoughts("aa", "bb")
        d.pass(50.minutes)
        d.send(Input.WrapUp())
        d.send(Input.SkipBreak)
        assertEquals(Phase.Ready, d.state.phase)
        assertEquals(listOf("aa", "bb"), d.state.parkedLot.map { it.ink })
        d.send(Input.Home)
        d.send(Input.ClearParked(0))
        assertEquals(listOf("bb"), d.state.parkedLot.map { it.ink })
        d.send(Input.ClearParked())
        assertTrue(d.state.parkedLot.isEmpty())
    }

    @Test
    fun `ending early keeps the block's thoughts`() {
        deepBlockWithThoughts("aa")
        d.send(Input.EndEarly("next: figures"))
        assertEquals(listOf("aa"), d.state.parkedLot.map { it.ink })
        assertEquals("next: figures", d.state.lastResumeNote)
    }

    @Test
    fun `clearing all during the wrap-up keeps nothing`() {
        deepBlockWithThoughts("aa", "bb")
        d.pass(50.minutes)
        d.send(Input.ClearParked())
        d.send(Input.Home)
        assertTrue(d.state.parkedLot.isEmpty())
    }

    @Test
    fun `the kept lot is bounded`() {
        repeat(4) {
            d.send(Input.Begin("Thesis"))
            repeat(10) { i -> d.send(Input.Park("k$i")) }
            d.send(Input.EndEarly())
        }
        assertEquals(30, d.state.parkedLot.size)
    }
}

class DisplayChoiceTest {
    private val d = Driver(FakeClock(), Settings(selectedMode = Presets.DEEP_ID))

    @Test
    fun `the ritual's Blackout toggle darkens this run only, and is remembered`() {
        d.send(Input.OpenRitual())
        d.send(Input.Begin("Thesis", checked = emptySet(), display = dev.framio.pomodoro.core.model.Display.Blackout))
        assertEquals(Effect.SetDisplay(DisplayLevel.Blackout), d.all<Effect.SetDisplay>().last())
        assertEquals(dev.framio.pomodoro.core.model.Display.Blackout, d.state.ritual(Presets.DEEP_ID)?.display)
        assertEquals(dev.framio.pomodoro.core.model.Display.Calm, d.state.settings.mode(Presets.DEEP_ID)?.environment?.display) // the preset is untouched
        d.pass(50.minutes)
        d.send(Input.WrapUp())
        d.pass(10.minutes)
        d.send(Input.StartNext)
        assertEquals(Effect.SetDisplay(DisplayLevel.Blackout), d.all<Effect.SetDisplay>().last()) // the whole run
    }

    @Test
    fun `a full mode stays full`() {
        val c = Driver(FakeClock(), Settings(selectedMode = Presets.CLASSIC_ID))
        c.send(Input.Begin("x", display = dev.framio.pomodoro.core.model.Display.Blackout))
        assertEquals(dev.framio.pomodoro.core.model.Display.Full, c.state.mode.environment.display)
    }
}
