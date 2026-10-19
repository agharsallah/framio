package dev.framio.pomodoro.core.engine

import dev.framio.pomodoro.core.Driver
import dev.framio.pomodoro.core.FakeClock
import dev.framio.pomodoro.core.model.CueStyle
import dev.framio.pomodoro.core.model.Display
import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Rhythm
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.core.model.Soundscape
import dev.framio.pomodoro.core.model.Task
import dev.framio.pomodoro.core.rhythm.BreakKind
import dev.framio.pomodoro.core.time.remaining
import dev.framio.pomodoro.core.wallAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ClassicTest {
    private val d = Driver(FakeClock(), Settings(selectedMode = Presets.CLASSIC_ID))

    @Test
    fun `four blocks give short, short, short, long breaks with rotating prompts`() {
        d.send(Input.Begin("Thesis"))
        val kinds = mutableListOf<BreakKind>()
        val prompts = mutableListOf<BreakPrompt>()
        repeat(4) { i ->
            d.pass(25.minutes)
            val p = d.state.phase as Phase.Break
            kinds += p.kind
            prompts += p.prompt
            assertTrue(p.pendingWrapUp)
            d.pass(p.length)
            assertEquals(Phase.Ready, d.state.phase)
            if (i < 3) d.send(Input.StartNext)
        }
        assertEquals(listOf(BreakKind.Short, BreakKind.Short, BreakKind.Short, BreakKind.Long), kinds)
        assertEquals(listOf(BreakPrompt.Move, BreakPrompt.Window, BreakPrompt.Water, BreakPrompt.Messages), prompts)
        assertEquals(4, d.state.completedToday)
        assertEquals(4, d.logged.size)
        assertTrue(d.logged.all { it.outcome == Outcome.Unrated && it.focused == 25.minutes && it.intention == "Thesis" })
        assertEquals(4, d.all<Effect.PlayCue>().count { it.kind == CueKind.FocusEnd })
        assertEquals(4, d.all<Effect.PlayCue>().count { it.kind == CueKind.BreakEnd })
    }

    @Test
    fun `the break starts by itself and the wrap-up can be answered during it`() {
        d.send(Input.Begin("Thesis"))
        d.pass(25.minutes)
        assertTrue(d.effects.contains(Effect.BringToFront))
        d.clock.advance(1.minutes)
        d.send(Input.WrapUp(Outcome.Partly, rating = 4, resumeNote = "figures next"))
        val r = d.logged.single()
        assertEquals(Outcome.Partly, r.outcome)
        assertEquals(4, r.rating)
        assertEquals("figures next", d.state.lastResumeNote)
        assertFalse((d.state.phase as Phase.Break).pendingWrapUp)
        d.reject(Input.WrapUp(Outcome.Done)) // only once
        d.pass(4.minutes)
        assertEquals(1, d.logged.size) // not logged again when the break ends
    }

    @Test
    fun `a deadline that arrives late still counts the planned time`() {
        d.send(Input.Begin("Thesis"))
        d.pass(30.minutes)
        assertEquals(25.minutes, d.state.session?.focused)
        // and the break runs from now
        assertEquals(5.minutes, (d.state.phase as Phase.Break).deadline.remaining(d.clock.now()))
    }

    @Test
    fun `an early wake does nothing`() {
        d.send(Input.Begin("Thesis"))
        val before = d.state
        val next = d.pass(24.minutes)
        assertEquals(before.phase, next.state.phase)
        assertTrue(next.effects.isEmpty())
    }

    @Test
    fun `skipping the break logs the block and waits on Ready, Home leaves the run`() {
        d.send(Input.Begin("Thesis"))
        d.pass(25.minutes)
        d.send(Input.SkipBreak)
        assertEquals(Phase.Ready, d.state.phase)
        assertEquals(Outcome.Unrated, d.logged.single().outcome)
        assertFalse(d.all<Effect.PlayCue>().any { it.kind == CueKind.BreakEnd })
        d.send(Input.Home)
        assertEquals(Phase.Idle, d.state.phase)
        assertNull(d.state.run)
        assertEquals(Effect.StopService, d.effects.last { it is Effect.StartService || it is Effect.StopService })
    }

    @Test
    fun `extend adds time to the block and to the plan`() {
        d.send(Input.Begin("Thesis"))
        d.pass(20.minutes)
        d.send(Input.Extend(5.minutes))
        d.pass(5.minutes)
        assertTrue(d.state.phase is Phase.Focus)
        d.pass(5.minutes)
        assertTrue(d.state.phase is Phase.Break)
        d.send(Input.SkipBreak)
        assertEquals(30.minutes, d.logged.single().planned)
        assertEquals(30.minutes, d.logged.single().focused)
    }

    @Test
    fun `parked thoughts are kept for the break and counted in the log`() {
        d.send(Input.Begin("Thesis"))
        d.send(Input.Park("0a1b"))
        d.send(Input.Pause)
        d.send(Input.Park("2c"))
        d.send(Input.Resume)
        d.pass(25.minutes)
        assertEquals(listOf("0a1b", "2c"), d.state.session?.parked?.map { it.ink })
        d.send(Input.SkipBreak)
        assertEquals(2, d.logged.single().parkedCount)
    }
}

class DeepTest {
    private val d = Driver(FakeClock(), Settings(selectedMode = Presets.DEEP_ID))

    @Test
    fun `deep needs an intention`() {
        d.send(Input.OpenRitual())
        d.reject(Input.Begin())
        d.reject(Input.Begin("   "))
        d.send(Input.Begin("Rewrite §3.2", ifThen = "close the browser"))
        val run = d.state.run ?: error("no run")
        assertEquals("Rewrite §3.2", run.intent.intention)
        assertEquals("close the browser", run.intent.ifThen)
        assertEquals("Rewrite §3.2", d.state.task(run.intent.task)?.title)
    }

    @Test
    fun `a deep block is calm, then sunrise into the wrap-up, then a 10 minute break, then a 30 minute one`() {
        d.send(Input.OpenRitual())
        d.send(Input.Begin("Thesis"))
        assertEquals(Effect.SetDisplay(DisplayLevel.Calm), d.all<Effect.SetDisplay>().last())
        assertEquals(Effect.PlayCue(CueKind.FocusEnd, CueStyle.Rising), d.pass(50.minutes).effects.first())
        assertTrue(d.state.phase is Phase.WrapUp)
        assertEquals(Effect.SetDisplay(DisplayLevel.Normal, Derive.SUNRISE), d.all<Effect.SetDisplay>().last())
        assertNull(Derive.wake(d.state)) // the wrap-up waits for you
        d.send(Input.WrapUp(Outcome.Done, rating = 5, resumeNote = "figures"))
        val b = d.state.phase as Phase.Break
        assertEquals(BreakKind.Short, b.kind)
        assertEquals(10.minutes, b.deadline.remaining(d.clock.now()))
        assertEquals(Effect.SetDisplay(DisplayLevel.Dim), d.all<Effect.SetDisplay>().last())
        d.pass(10.minutes)
        assertEquals(Phase.Ready, d.state.phase) // no auto-start in Deep
        d.send(Input.StartNext)
        d.pass(50.minutes)
        d.send(Input.WrapUp())
        assertEquals(BreakKind.Long, (d.state.phase as Phase.Break).kind)
        assertEquals(30.minutes, (d.state.phase as Phase.Break).length)
        assertEquals(listOf(Outcome.Done, Outcome.Unrated), d.logged.map { it.outcome })
        assertEquals("figures", d.state.lastResumeNote)
    }

    @Test
    fun `a pause is capped at 10 minutes, then the app offers to end`() {
        d.send(Input.Begin("Thesis"))
        d.clock.advance(20.minutes)
        d.send(Input.Pause)
        val p = d.state.phase as Phase.Paused
        assertEquals(30.minutes, p.remaining)
        assertEquals(20.minutes, p.elapsed)
        assertEquals(Effect.SetDisplay(DisplayLevel.Normal), d.all<Effect.SetDisplay>().last())
        d.pass(9.minutes)
        assertFalse((d.state.phase as Phase.Paused).overCap)
        val capped = d.pass(1.minutes)
        assertTrue((d.state.phase as Phase.Paused).overCap)
        assertTrue(Effect.PlayCue(CueKind.PauseCap, CueStyle.Rising) in capped.effects)
        assertNull(Derive.wake(d.state))
        d.send(Input.EndEarly("pick up at the intro"))
        val r = d.logged.single()
        assertEquals(Outcome.EndedEarly, r.outcome)
        assertEquals(20.minutes, r.focused)
        assertEquals("pick up at the intro", d.state.lastResumeNote)
        assertEquals(Phase.Idle, d.state.phase)
        assertEquals(0, d.state.completedToday) // ended early doesn't count
    }

    @Test
    fun `resume keeps the remaining time and the focus time`() {
        d.send(Input.Begin("Thesis"))
        d.clock.advance(10.minutes)
        d.send(Input.Pause)
        d.clock.advance(5.minutes)
        d.send(Input.Resume)
        val f = d.state.phase as Phase.Focus
        assertEquals(40.minutes, f.deadline?.remaining(d.clock.now()))
        assertEquals(10.minutes, Timing.focused(d.state, d.clock.now()))
        d.pass(40.minutes)
        assertEquals(50.minutes, d.state.session?.focused)
    }

    @Test
    fun `classic has no pause cap`() {
        val c = Driver(FakeClock(), Settings(selectedMode = Presets.CLASSIC_ID))
        c.send(Input.Begin("x"))
        c.send(Input.Pause)
        assertNull(Derive.wake(c.state))
    }

    @Test
    fun `blackout turns the backlight off during focus and a sunrise brings it back at the end`() {
        val blackout = Presets.deep.duplicate(ModeId("dark"), "Dark").let { it.copy(environment = it.environment.copy(display = Display.Blackout)) }
        d.send(Input.SaveMode(blackout))
        d.send(Input.SelectMode(blackout.id))
        d.send(Input.Begin("x"))
        assertEquals(Effect.SetDisplay(DisplayLevel.Blackout), d.all<Effect.SetDisplay>().last())
        d.send(Input.EndEarly())
        assertEquals(Effect.SetDisplay(DisplayLevel.Normal), d.all<Effect.SetDisplay>().last()) // you ended it: no slow fade
        d.send(Input.Begin("x"))
        d.pass(50.minutes)
        assertEquals(Effect.SetDisplay(DisplayLevel.Normal, Derive.SUNRISE), d.all<Effect.SetDisplay>().last())
    }
}

class FlowTest {
    private val d = Driver(FakeClock(), Settings(selectedMode = Presets.FLOW_ID))

    @Test
    fun `flow counts up, nudges once at 90 minutes without a sound, and earns a 10 minute break`() {
        d.send(Input.Begin())
        val f = d.state.phase as Phase.Focus
        assertNull(f.deadline)
        assertEquals(f.startedAt + 90.minutes, Derive.wake(d.state))
        d.pass(89.minutes)
        assertFalse(d.state.session?.nudged ?: true)
        val nudge = d.pass(1.minutes)
        assertTrue(d.state.session?.nudged == true)
        assertEquals(listOf(Effect.CancelWake, Effect.Persist(d.state.snapshot(d.clock.now().moment))), nudge.effects)
        assertNull(Derive.wake(d.state))
        d.clock.advance(5.minutes)
        d.send(Input.Complete)
        assertEquals(10.minutes, (d.state.phase as Phase.Break).length)
        assertEquals(95.minutes, d.state.session?.focused)
    }

    @Test
    fun `tier boundaries through the engine`() {
        fun breakAfter(focused: Duration): Duration {
            val e = Driver(FakeClock(), Settings(selectedMode = Presets.FLOW_ID))
            e.send(Input.Begin())
            e.clock.advance(focused)
            e.send(Input.Complete)
            return (e.state.phase as Phase.Break).length
        }
        assertEquals(5.minutes, breakAfter(25.minutes))
        assertEquals(8.minutes, breakAfter(25.minutes + 1.seconds))
        assertEquals(8.minutes, breakAfter(50.minutes))
        assertEquals(10.minutes, breakAfter(50.minutes + 1.seconds))
    }

    @Test
    fun `completing from a pause uses the focus time before it`() {
        d.send(Input.Begin())
        d.clock.advance(30.minutes)
        d.send(Input.Pause)
        d.clock.advance(20.minutes)
        d.send(Input.Complete)
        assertEquals(30.minutes, d.state.session?.focused)
        assertEquals(8.minutes, (d.state.phase as Phase.Break).length)
    }

    @Test
    fun `flow can't be extended and has no planned length`() {
        d.send(Input.Begin())
        d.reject(Input.Extend())
        d.clock.advance(10.minutes)
        d.send(Input.EndEarly())
        assertNull(d.logged.single().planned)
    }
}

class GentleAndAutoTest {
    @Test
    fun `gentle start ignores intentions and has no long breaks`() {
        val d = Driver(FakeClock(), Settings(selectedMode = Presets.GENTLE_ID))
        d.send(Input.Begin("ignored"))
        assertNull(d.state.run?.intent?.intention)
        assertTrue(d.state.tasks.isEmpty())
        repeat(5) {
            d.pass(10.minutes)
            assertEquals(BreakKind.Short, (d.state.phase as Phase.Break).kind)
            assertEquals(3.minutes, (d.state.phase as Phase.Break).length)
            d.pass(3.minutes)
            d.send(Input.StartNext)
        }
    }

    @Test
    fun `auto focus starts the next block when the break ends`() {
        val mode = Presets.classic.duplicate(ModeId("auto"), "Auto").copy(rhythm = Rhythm.Fixed(autoFocus = true))
        val d = Driver(FakeClock(), Settings(modes = Presets.all + mode, selectedMode = mode.id))
        d.send(Input.Begin("Thesis"))
        val first = d.state.session?.id
        d.pass(25.minutes)
        d.pass(5.minutes)
        assertTrue(d.state.phase is Phase.Focus)
        assertTrue(d.state.session?.id != first)
        assertEquals("Thesis", d.state.run?.intent?.intention)
        assertEquals(1, d.logged.size)
    }

    @Test
    fun `a debug time scale runs minutes as seconds but logs minutes`() {
        val d = Driver(FakeClock(), Settings(selectedMode = Presets.CLASSIC_ID), EngineConfig.MinutesAsSeconds)
        d.send(Input.Begin("x"))
        assertEquals(25.seconds, (d.state.phase as Phase.Focus).deadline?.remaining(d.clock.now()))
        assertEquals(25.minutes, Timing.left(d.state, d.clock.now(), d.config))
        d.pass(25.seconds)
        assertEquals(5.seconds, (d.state.phase as Phase.Break).deadline.remaining(d.clock.now()))
        d.send(Input.SkipBreak)
        assertEquals(25.minutes, d.logged.single().focused)
    }

    @Test
    fun `focus override replaces the length once`() {
        val d = Driver(FakeClock(), Settings(selectedMode = Presets.CLASSIC_ID))
        d.send(Input.Begin("x", focusOverride = 1.minutes))
        d.pass(1.minutes)
        assertTrue(d.state.phase is Phase.Break)
        d.pass(5.minutes)
        d.send(Input.StartNext)
        assertEquals(25.minutes, (d.state.phase as Phase.Focus).deadline?.remaining(d.clock.now()))
    }
}

class DerivedEffectsTest {
    private val d = Driver(FakeClock(), Settings(selectedMode = Presets.CLASSIC_ID))

    @Test
    fun `begin starts the service, schedules a wake and persists`() {
        val next = d.send(Input.Begin("x"))
        val f = d.state.phase as Phase.Focus
        assertTrue(Effect.StartService in next.effects)
        assertTrue(Effect.ScheduleWake(f.deadline ?: error("none")) in next.effects)
        val persist = next.effects.filterIsInstance<Effect.Persist>().single()
        assertEquals(d.state.snapshot(), persist.snapshot.copy(savedAt = null))
        assertEquals(d.clock.now().moment, persist.snapshot.savedAt)
    }

    @Test
    fun `selecting a mode saves settings but writes no snapshot`() {
        val next = d.send(Input.SelectMode(Presets.FLOW_ID))
        assertEquals(listOf(Effect.SaveSettings(d.state.settings)), next.effects)
    }

    @Test
    fun `cues off means no cue effects`() {
        val quiet = Presets.classic.duplicate(ModeId("q"), "Quiet").let { it.copy(environment = it.environment.copy(cues = CueStyle.Off)) }
        d.send(Input.SaveMode(quiet))
        d.send(Input.SelectMode(quiet.id))
        d.send(Input.Begin("x"))
        d.pass(25.minutes)
        assertTrue(d.all<Effect.PlayCue>().isEmpty())
    }

    @Test
    fun `a soundscape plays only during focus`() {
        val rain = Presets.classic.duplicate(ModeId("r"), "Rain").let { it.copy(environment = it.environment.copy(soundscape = Soundscape.Rain)) }
        d.send(Input.SaveMode(rain))
        d.send(Input.SelectMode(rain.id))
        assertTrue(d.send(Input.Begin("x")).effects.contains(Effect.SetSoundscape(Soundscape.Rain)))
        assertTrue(d.send(Input.Pause).effects.contains(Effect.SetSoundscape(null)))
        assertTrue(d.send(Input.Resume).effects.contains(Effect.SetSoundscape(Soundscape.Rain)))
    }
}

class DayAndTimeTest {
    @Test
    fun `the daily count resets at local midnight`() {
        val clock = FakeClock(wallMs = wallAt("2026-10-18T23:00"))
        val d = Driver(clock, Settings(selectedMode = Presets.CLASSIC_ID))
        d.send(Input.Begin("x"))
        d.pass(25.minutes)
        assertEquals(1, d.state.completedToday)
        d.pass(40.minutes) // 00:05 the next day
        assertEquals("2026-10-19", d.state.today.toString())
        assertEquals(0, d.state.completedToday)
    }

    @Test
    fun `sessions while the clock is unknown count for the day it becomes known, and get back-dated`() {
        val clock = FakeClock(wallMs = null)
        val d = Driver(clock, Settings(selectedMode = Presets.CLASSIC_ID))
        d.send(Input.Begin("x"))
        val start = clock.elapsedMs
        d.pass(25.minutes)
        assertNull(d.state.today)
        assertEquals(1, d.state.completedToday)
        clock.wallMs = wallAt("2026-10-18T10:00")
        d.send(Input.TimeTrusted)
        assertEquals(1, d.state.completedToday)
        assertEquals("2026-10-18", d.state.today.toString())
        assertNotNull(d.state.session?.start?.wallMs)
        d.send(Input.SkipBreak)
        val r = d.logged.single()
        assertEquals(wallAt("2026-10-18T10:00") - (clock.elapsedMs - start), r.startWallMs)
    }
}

class TasksAndModesTest {
    private val d = Driver(FakeClock(), Settings(selectedMode = Presets.CLASSIC_ID))

    @Test
    fun `tasks are found by title, used ones move to the front`() {
        d.send(Input.AddTask("Taxes", 3))
        d.send(Input.AddTask("Thesis"))
        assertEquals(listOf("Thesis", "Taxes"), d.state.tasks.map { it.title })
        d.send(Input.Begin("taxes"))
        assertEquals(listOf("Taxes", "Thesis"), d.state.tasks.map { it.title })
        assertEquals(1, d.state.tasks.first().useCount)
        assertEquals("taxes", d.state.run?.intent?.intention)
        d.send(Input.EndEarly())
        d.send(Input.Begin(taskId = d.state.tasks[1].id))
        assertEquals("Thesis", d.state.run?.intent?.intention)
    }

    @Test
    fun `estimates are validated, removal works, the list stays bounded`() {
        d.send(Input.AddTask("A"))
        val id = d.state.tasks.single().id
        d.reject(Input.SetEstimate(id, 0))
        d.send(Input.SetEstimate(id, 4))
        assertEquals(4, d.state.tasks.single().estimateSessions)
        d.send(Input.RemoveTask(id))
        assertTrue(d.state.tasks.isEmpty())
        d.reject(Input.AddTask(" "))
        repeat(Task.MAX_TASKS + 5) {
            d.clock.advance(1.seconds)
            d.send(Input.AddTask("T$it"))
        }
        assertEquals(Task.MAX_TASKS, d.state.tasks.size)
        assertEquals("T${Task.MAX_TASKS + 4}", d.state.tasks.first().title)
    }

    @Test
    fun `the next block survives its task being removed`() {
        d.send(Input.Begin("Thesis"))
        d.pass(25.minutes)
        d.send(Input.RemoveTask(d.state.tasks.single().id))
        d.pass(5.minutes)
        d.send(Input.StartNext)
        assertNull(d.state.run?.intent?.task)
        assertEquals("Thesis", d.state.run?.intent?.intention)
    }

    @Test
    fun `built-in modes are read-only, own modes are bounded, deleting the selected one selects Deep`() {
        d.reject(Input.SaveMode(Presets.deep.copy(name = "Mine")))
        d.reject(Input.SaveMode(Presets.deep.copy(id = ModeId("x"))))
        d.reject(Input.DeleteMode(Presets.DEEP_ID))
        repeat(4) { d.send(Input.SaveMode(Presets.classic.duplicate(ModeId("m$it"), "M$it"))) }
        d.reject(Input.SaveMode(Presets.classic.duplicate(ModeId("m9"), "M9")))
        d.send(Input.SelectMode(ModeId("m1")))
        d.send(Input.DeleteMode(ModeId("m1")))
        assertEquals(Presets.DEEP_ID, d.state.settings.selectedMode)
    }

    @Test
    fun `a running session keeps its mode when the mode is edited or deleted`() {
        val mine = Presets.classic.duplicate(ModeId("mine"), "Mine")
        d.send(Input.SaveMode(mine))
        d.send(Input.SelectMode(mine.id))
        d.send(Input.Begin("x"))
        d.send(Input.DeleteMode(mine.id))
        assertEquals(mine, d.state.run?.mode)
        d.pass(25.minutes)
        assertTrue(d.state.phase is Phase.Break)
        d.send(Input.SkipBreak)
        assertEquals(ModeId("mine"), d.logged.single().mode)
    }

    @Test
    fun `selecting another mode in the ritual switches the ritual`() {
        d.send(Input.OpenRitual())
        d.send(Input.SelectMode(Presets.DEEP_ID))
        assertEquals(Phase.Ritual(Presets.DEEP_ID), d.state.phase)
        d.send(Input.CancelRitual)
        assertEquals(Phase.Idle, d.state.phase)
    }
}
