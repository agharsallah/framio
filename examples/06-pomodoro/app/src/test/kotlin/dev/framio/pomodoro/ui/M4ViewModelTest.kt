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
import dev.framio.pomodoro.core.time.DayPart
import dev.framio.pomodoro.core.wallAt
import dev.framio.pomodoro.ui.breaks.BreakViewModel
import dev.framio.pomodoro.ui.breaks.FlowTier
import dev.framio.pomodoro.ui.flow.FlowViewModel
import dev.framio.pomodoro.ui.ready.ReadyViewModel
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

/** M4: Flow (count-up, tiers, the nudge), the break (tier, +5, landscape light) and Gentle's "another 10?". */
class M4ViewModelTest {
    private val main = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun <T> TestScope.watch(flow: StateFlow<T>) {
        backgroundScope.launch { flow.collect {} }
        runCurrent()
    }

    private fun flowPort() = FakePort(settings = Settings(selectedMode = Presets.FLOW_ID))

    @Test
    fun `flow counts up in Calm, and what stopping now earns follows the Smits tiers`() = runTest(main) {
        val port = flowPort()
        port.send(Input.Begin(intention = "Thesis"))
        val vm = FlowViewModel(port, FakeHardware())
        watch(vm.ui)
        val ui = vm.ui.value
        assertEquals(Display.Calm, ui.display)
        assertTrue(ui.dark)
        assertEquals("Thesis", ui.intention)
        assertEquals(1.minutes, ui.tick)
        assertEquals(0.minutes, ui.timing.focused(port.now()))
        assertEquals(5, ui.breakIfStopped(port.now()))
        port.clock.advance(25.minutes)
        assertEquals(25.minutes, vm.ui.value.timing.focused(port.now())) // the same frozen timing counts on
        assertEquals(5, vm.ui.value.breakIfStopped(port.now()))
        port.clock.advance(12.minutes)
        assertEquals(37.minutes, vm.ui.value.timing.focused(port.now()))
        assertEquals(8, vm.ui.value.breakIfStopped(port.now()))
        port.clock.advance(14.minutes)
        assertEquals(10, vm.ui.value.breakIfStopped(port.now()))
    }

    @Test
    fun `stop here ends the block, and the break shows the tier it earned`() = runTest(main) {
        val port = flowPort()
        port.send(Input.Begin(intention = "Thesis"))
        val vm = FlowViewModel(port, FakeHardware())
        watch(vm.ui)
        port.clock.advance(37.minutes + 20.seconds)
        vm.stop()
        assertEquals(Input.Complete, port.sent.last())
        val p = port.state.value.phase as Phase.Break
        assertEquals(8.minutes, p.length)
        val brk = BreakViewModel(port)
        watch(brk.ui)
        assertEquals(FlowTier(workedMinutes = 37, breakMinutes = 8), brk.ui.value.tier)
        assertTrue(brk.ui.value.pendingWrapUp)
        brk.wrapUp(Outcome.Done)
        runCurrent()
        assertEquals(FlowTier(37, 8), brk.ui.value.tier) // still shown after answering
    }

    @Test
    fun `the nudge arrives at 90 minutes, once, as a flag the screen glows for`() = runTest(main) {
        val port = flowPort()
        port.send(Input.Begin())
        val vm = FlowViewModel(port, FakeHardware())
        watch(vm.ui)
        port.pass(89.minutes)
        runCurrent()
        assertFalse(vm.ui.value.nudged)
        port.pass(1.minutes)
        runCurrent()
        assertTrue(vm.ui.value.nudged)
        port.pass(30.minutes)
        runCurrent()
        assertTrue(vm.ui.value.nudged)
        assertTrue(port.state.value.phase is Phase.Focus) // a nudge never ends the block
    }

    @Test
    fun `a tap shows Stop and Pause for a while and lifts the dark display meanwhile`() = runTest(main) {
        val port = flowPort()
        port.send(Input.Begin())
        val hw = FakeHardware()
        val vm = FlowViewModel(port, hw)
        watch(vm.ui)
        assertFalse(vm.ui.value.controls)
        vm.tap()
        runCurrent()
        assertTrue(vm.ui.value.controls)
        assertTrue(hw.lit)
        advanceTimeBy(FlowViewModel.CONTROLS_FOR + 1.seconds)
        runCurrent()
        assertFalse(vm.ui.value.controls)
        assertFalse(hw.lit)
        vm.tap()
        vm.pause()
        runCurrent()
        assertTrue(vm.ui.value.paused)
        assertTrue(vm.ui.value.controls) // paused: Resume and Stop stay
        assertFalse(vm.ui.value.dark) // a pause lights the display again
    }

    @Test
    fun `flow in Blackout draws nothing until a tap`() = runTest(main) {
        val port = flowPort()
        port.send(Input.Begin(display = Display.Blackout))
        val vm = FlowViewModel(port, FakeHardware())
        watch(vm.ui)
        assertTrue(vm.ui.value.hidden)
        vm.tap()
        runCurrent()
        assertFalse(vm.ui.value.hidden)
    }

    @Test
    fun `gentle start ends on a warm Ready, and Another 10 starts ten more minutes`() = runTest(main) {
        val port = FakePort(settings = Settings(selectedMode = Presets.GENTLE_ID))
        port.send(Input.Begin())
        port.pass(10.minutes)
        val brk = BreakViewModel(port)
        watch(brk.ui)
        assertTrue(brk.ui.value.warm)
        assertEquals(3.minutes, (port.state.value.phase as Phase.Break).length)
        port.pass(3.minutes)
        assertEquals(Phase.Ready, port.state.value.phase)
        val vm = ReadyViewModel(port)
        watch(vm.ui)
        assertTrue(vm.ui.value.warm)
        assertEquals(10, vm.ui.value.nextMinutes)
        vm.start()
        val focus = port.state.value.phase as Phase.Focus
        assertEquals(10.minutes, port.state.value.session?.planned)
        assertTrue(focus.deadline != null)
    }

    @Test
    fun `gentle's Done for now goes home kindly, the block already counted`() = runTest(main) {
        val port = FakePort(settings = Settings(selectedMode = Presets.GENTLE_ID))
        port.send(Input.Begin())
        port.pass(10.minutes)
        port.pass(3.minutes)
        val vm = ReadyViewModel(port)
        watch(vm.ui)
        vm.home()
        assertEquals(Phase.Idle, port.state.value.phase)
        assertEquals(1, port.state.value.completedToday)
    }

    @Test
    fun `classic's Ready is not warm`() = runTest(main) {
        val port = FakePort()
        port.send(Input.Begin())
        port.pass(25.minutes)
        port.pass(5.minutes)
        val vm = ReadyViewModel(port)
        watch(vm.ui)
        assertFalse(vm.ui.value.warm)
    }

    @Test
    fun `plus five on the break, until 90 minutes`() = runTest(main) {
        val port = FakePort()
        port.send(Input.Begin())
        port.pass(25.minutes)
        val vm = BreakViewModel(port)
        watch(vm.ui)
        assertTrue(vm.ui.value.canExtend)
        assertNull(vm.ui.value.tier)
        vm.extend()
        runCurrent()
        assertEquals(10.minutes, (port.state.value.phase as Phase.Break).length)
        repeat(16) { vm.extend() }
        runCurrent()
        assertEquals(90.minutes, (port.state.value.phase as Phase.Break).length)
        assertFalse(vm.ui.value.canExtend)
    }

    @Test
    fun `the landscape is lit for the part of the day, and still with Reduce motion`() = runTest(main) {
        val port = FakePort(settings = Settings(selectedMode = Presets.CLASSIC_ID, reduceMotion = true))
        port.clock.wallMs = wallAt("2026-10-18T19:10")
        port.send(Input.Begin())
        port.pass(25.minutes)
        val vm = BreakViewModel(port)
        watch(vm.ui)
        assertEquals(DayPart.Dusk, vm.ui.value.dayPart)
        assertTrue(vm.ui.value.reduceMotion)
    }
}
