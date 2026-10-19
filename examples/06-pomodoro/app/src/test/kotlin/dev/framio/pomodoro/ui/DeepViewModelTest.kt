@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.framio.pomodoro.ui

import dev.framio.pomodoro.FakeHardware
import dev.framio.pomodoro.FakePort
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.engine.Phase
import dev.framio.pomodoro.core.model.Display
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.ui.deep.DeepFocusViewModel
import dev.framio.pomodoro.ui.home.HomeViewModel
import dev.framio.pomodoro.ui.ritual.RitualViewModel
import dev.framio.pomodoro.ui.settings.SettingsViewModel
import dev.framio.pomodoro.ui.wrapup.WrapUpViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** M3: the ritual, Deep focus (touch lock, panel, peek, parking lot, end note) and Deep's wrap-up, on a fake engine. */
class DeepViewModelTest {
    private val main = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun <T> TestScope.watch(flow: StateFlow<T>) {
        backgroundScope.launch { flow.collect {} }
        runCurrent()
    }

    private fun deepPort() = FakePort(settings = Settings(selectedMode = Presets.DEEP_ID))

    // Ritual

    @Test
    fun `the ritual needs an intention, then begins with the if-then line, the ticks and the screen choice`() = runTest(main) {
        val port = deepPort()
        port.send(Input.AddTask("Thesis"))
        port.send(Input.OpenRitual())
        val vm = RitualViewModel(port)
        watch(vm.ui)
        val ui = vm.ui.value
        assertFalse(ui.canBegin)
        assertEquals(listOf("phone", "water", "one_window"), ui.checklist.map { it.id })
        assertTrue(ui.checklist.none { it.checked })
        assertTrue(ui.darkChoice)
        assertFalse(ui.blackout)
        assertFalse(SettingsViewModel(port).ui.value.rituals.first { it.id == Presets.DEEP_ID }.canSkip) // only after a first run
        vm.begin()
        assertTrue(port.sent.none { it is Input.Begin })
        vm.chooseTask("Thesis")
        vm.editIfThen("close the tab")
        vm.toggle("phone", true)
        vm.toggle("water", true)
        vm.toggle("water", false)
        vm.setBlackout(true)
        runCurrent()
        assertTrue(vm.ui.value.canBegin)
        assertEquals(listOf(true, false, false), vm.ui.value.checklist.map { it.checked })
        vm.begin()
        assertEquals(Input.Begin(intention = "Thesis", ifThen = "close the tab", checked = setOf("phone"), display = Display.Blackout), port.sent.last())
        assertTrue(port.rejected.isEmpty())
        assertTrue(port.state.value.phase is Phase.Focus)
    }

    @Test
    fun `the next ritual remembers the ticks, the if-then line and the screen, and can be skipped from Settings`() = runTest(main) {
        val port = deepPort()
        port.send(Input.OpenRitual())
        port.send(Input.Begin("Thesis", ifThen = "breathe", checked = setOf("water"), display = Display.Blackout))
        port.send(Input.EndEarly("figures"))
        port.send(Input.OpenRitual("Thesis"))
        val vm = RitualViewModel(port)
        watch(vm.ui)
        val ui = vm.ui.value
        assertEquals("Thesis", ui.chosen)
        assertEquals("breathe", ui.ifThen)
        assertEquals(listOf(false, true, false), ui.checklist.map { it.checked })
        assertTrue(ui.blackout)
        assertEquals("figures", ui.resumeNote)
        // Skipping moved to the mode (M5): Settings → Rituals for the read-only presets
        val settings = SettingsViewModel(port)
        watch(settings.ui)
        assertTrue(settings.ui.value.rituals.first { it.id == Presets.DEEP_ID }.canSkip)
        settings.skipRitual(Presets.DEEP_ID, true)
        runCurrent()
        assertTrue(settings.ui.value.rituals.first { it.id == Presets.DEEP_ID }.skip)
        vm.back()
        // Home now goes straight into focus, with what the ritual remembers
        val home = HomeViewModel(port)
        watch(home.ui)
        assertTrue(home.ui.value.ritualSkipped)
        assertFalse(home.ui.value.ritual)
        home.chooseTask("Thesis")
        runCurrent()
        home.begin()
        assertEquals(Input.Begin(intention = "Thesis", ifThen = "breathe", display = Display.Blackout), port.sent.last())
        assertTrue(port.state.value.phase is Phase.Focus)
    }

    @Test
    fun `edits from an abandoned ritual don't leak into the next mode's ritual`() = runTest(main) {
        val port = deepPort()
        port.send(Input.OpenRitual())
        val vm = RitualViewModel(port)
        watch(vm.ui)
        vm.toggle("phone", true)
        vm.editIfThen("x")
        runCurrent()
        port.send(Input.CancelRitual)
        port.send(Input.OpenRitual("Other"))
        runCurrent()
        assertTrue(vm.ui.value.checklist.none { it.checked })
        assertEquals("", vm.ui.value.ifThen)
        assertEquals("Other", vm.ui.value.chosen)
    }

    // Deep focus

    private fun TestScope.deepFocus(display: Display = Display.Calm): Triple<FakePort, FakeHardware, DeepFocusViewModel> {
        val port = deepPort()
        port.send(Input.Begin("Thesis", display = display))
        val hw = FakeHardware()
        val vm = DeepFocusViewModel(port, hw)
        watch(vm.ui)
        return Triple(port, hw, vm)
    }

    @Test
    fun `a tap only shows the faint hint for two seconds and sends nothing`() = runTest(main) {
        val (port, hw, vm) = deepFocus()
        val sent = port.sent.size
        vm.tap()
        runCurrent()
        assertTrue(vm.ui.value.hint)
        assertFalse(vm.ui.value.panel)
        assertFalse(hw.lit) // the hint doesn't light the screen
        advanceTimeBy(2.1.seconds)
        runCurrent()
        assertFalse(vm.ui.value.hint)
        assertEquals(sent, port.sent.size)
        assertTrue(port.state.value.phase is Phase.Focus)
    }

    @Test
    fun `in Blackout a tap does nothing at all`() = runTest(main) {
        val (_, _, vm) = deepFocus(Display.Blackout)
        assertTrue(vm.ui.value.blackout)
        vm.tap()
        runCurrent()
        assertFalse(vm.ui.value.hint)
        assertFalse(vm.ui.value.revealed)
    }

    @Test
    fun `the hold opens the panel and lifts the display, and it closes by itself`() = runTest(main) {
        val (_, hw, vm) = deepFocus()
        vm.hold()
        runCurrent()
        assertTrue(vm.ui.value.panel)
        assertTrue(hw.lit)
        advanceTimeBy(DeepFocusViewModel.PANEL_FOR + 1.seconds)
        runCurrent()
        assertFalse(vm.ui.value.panel)
        assertFalse(hw.lit)
    }

    @Test
    fun `a tap while the panel shows closes it`() = runTest(main) {
        val (_, _, vm) = deepFocus()
        vm.hold()
        vm.tap()
        runCurrent()
        assertFalse(vm.ui.value.panel)
        assertFalse(vm.ui.value.hint)
    }

    @Test
    fun `pause from the panel pauses, and the deep pause is capped`() = runTest(main) {
        val (port, _, vm) = deepFocus()
        vm.hold()
        vm.pause()
        runCurrent()
        assertTrue(vm.ui.value.paused)
        assertFalse(vm.ui.value.panel)
        port.pass(10.minutes)
        runCurrent()
        assertTrue(vm.ui.value.overCap)
        vm.resume()
        runCurrent()
        assertFalse(vm.ui.value.paused)
    }

    @Test
    fun `End asks for the resume note, then logs ended early with it`() = runTest(main) {
        val (port, hw, vm) = deepFocus()
        port.clock.advance(12.minutes)
        vm.hold()
        vm.askEnd()
        runCurrent()
        assertEquals("", vm.ui.value.note)
        assertFalse(vm.ui.value.panel)
        assertTrue(hw.lit)
        assertTrue(port.sent.none { it is Input.EndEarly })
        vm.editNote("rewrite the intro")
        vm.confirmEnd()
        runCurrent()
        assertEquals(Input.EndEarly("rewrite the intro"), port.sent.last())
        assertEquals(Phase.Idle, port.state.value.phase)
        assertEquals("rewrite the intro", port.state.value.lastResumeNote)
        assertFalse(hw.lit)
    }

    @Test
    fun `a double knock peeks for five seconds, lifting the display, then it goes dark again`() = runTest(main) {
        val (_, hw, vm) = deepFocus(Display.Blackout)
        hw.knock()
        runCurrent()
        assertTrue(vm.ui.value.peeking)
        assertTrue(vm.ui.value.revealed)
        assertTrue(hw.lit)
        advanceTimeBy(4.seconds)
        hw.knock() // a second knock extends it
        runCurrent()
        advanceTimeBy(4.seconds)
        runCurrent()
        assertTrue(vm.ui.value.peeking)
        advanceTimeBy(1.5.seconds)
        runCurrent()
        assertFalse(vm.ui.value.peeking)
        assertEquals(listOf(false, true, false), hw.lights)
    }

    @Test
    fun `knocks do nothing outside a dark focus block`() = runTest(main) {
        val (port, hw, vm) = deepFocus()
        port.send(Input.Pause)
        hw.knock()
        runCurrent()
        assertFalse(vm.ui.value.peeking)
        val classic = FakePort()
        classic.send(Input.Begin())
        val hw2 = FakeHardware()
        val vm2 = DeepFocusViewModel(classic, hw2)
        watch(vm2.ui)
        hw2.knock()
        runCurrent()
        assertFalse(vm2.ui.value.peeking)
    }

    @Test
    fun `a parked scribble is saved to the block, an empty pad parks nothing`() = runTest(main) {
        val (port, hw, vm) = deepFocus()
        vm.hold()
        vm.openPark()
        runCurrent()
        assertTrue(vm.ui.value.park)
        assertFalse(vm.ui.value.panel)
        assertTrue(hw.lit)
        vm.park("0a1b.2c3d")
        runCurrent()
        assertFalse(vm.ui.value.park)
        assertEquals(listOf("0a1b.2c3d"), port.state.value.session?.parked?.map { it.ink })
        vm.openPark()
        vm.park(null)
        assertEquals(1, port.state.value.session?.parked?.size)
    }

    @Test
    fun `leaving the screen forgets the overlays, lowers the display and stops listening`() = runTest(main) {
        val (_, hw, vm) = deepFocus()
        vm.listen(true)
        vm.hold()
        runCurrent()
        vm.leave()
        runCurrent()
        assertFalse(vm.ui.value.panel)
        assertFalse(hw.lit)
        assertFalse(hw.isListening)
    }

    @Test
    fun `the face counts minutes in real time, a second per minute when scaled`() = runTest(main) {
        val (_, _, vm) = deepFocus()
        assertEquals(1.minutes, vm.ui.value.tick)
        assertEquals(50.minutes, vm.ui.value.timing.left(vm.now()))
        val scaled = FakePort(settings = Settings(selectedMode = Presets.DEEP_ID), config = dev.framio.pomodoro.core.engine.EngineConfig.MinutesAsSeconds)
        scaled.send(Input.Begin("x"))
        val vm2 = DeepFocusViewModel(scaled, FakeHardware())
        watch(vm2.ui)
        assertEquals(1.seconds, vm2.ui.value.tick)
    }

    // Wrap-up

    @Test
    fun `the deep wrap-up logs what was answered and starts the break`() = runTest(main) {
        val port = deepPort()
        port.send(Input.Begin("Thesis"))
        port.send(Input.Park("aa"))
        port.send(Input.Park("bb"))
        port.pass(50.minutes)
        val vm = WrapUpViewModel(port)
        watch(vm.ui)
        assertEquals(10, vm.ui.value.breakMinutes)
        assertEquals(listOf("aa", "bb"), vm.ui.value.parked)
        vm.outcome(Outcome.Partly)
        vm.rating(4)
        vm.rating(4) // tapping again clears
        vm.rating(3)
        vm.note("figures")
        vm.removeParked(0)
        runCurrent()
        assertEquals(listOf("bb"), vm.ui.value.parked)
        vm.startBreak()
        val logged = port.state.value
        assertTrue(logged.phase is Phase.Break)
        assertEquals(Input.WrapUp(Outcome.Partly, 3, "figures"), port.sent.last())
        assertEquals("figures", logged.lastResumeNote)
    }

    @Test
    fun `skipping the break still logs the answers, and a new block's wrap-up starts empty`() = runTest(main) {
        val port = deepPort()
        port.send(Input.Begin("Thesis"))
        port.pass(50.minutes)
        val vm = WrapUpViewModel(port)
        watch(vm.ui)
        vm.outcome(Outcome.Done)
        vm.skipBreak()
        assertEquals(listOf(Input.WrapUp(Outcome.Done), Input.SkipBreak), port.sent.takeLast(2))
        assertEquals(Phase.Ready, port.state.value.phase)
        port.send(Input.StartNext)
        port.pass(50.minutes)
        runCurrent()
        assertNull(vm.ui.value.outcome)
    }
}
