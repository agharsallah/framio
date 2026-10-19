@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.framio.pomodoro.ui

import dev.framio.pomodoro.FakePort
import dev.framio.pomodoro.core.engine.BreakPrompt
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.engine.Notice
import dev.framio.pomodoro.core.model.IntentionRule
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.ui.breaks.BreakViewModel
import dev.framio.pomodoro.ui.focus.FocusViewModel
import dev.framio.pomodoro.ui.home.HomeViewModel
import dev.framio.pomodoro.ui.ready.ReadyViewModel
import dev.framio.pomodoro.ui.settings.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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

/** The screens' ViewModels against a fake engine (the real reducer on a fake clock). */
class ViewModelTest {
    private val main = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Subscribes like the UI does, so WhileSubscribed shares. */
    private fun <T> TestScope.watch(flow: kotlinx.coroutines.flow.StateFlow<T>) {
        backgroundScope.launch { flow.collect {} }
        runCurrent()
    }

    @Test
    fun `home shows the modes, today's progress, and begins with the chosen task`() = runTest(main) {
        val port = FakePort()
        port.send(Input.AddTask("Thesis ch.3"))
        val vm = HomeViewModel(port)
        watch(vm.ui)
        val ui = vm.ui.value
        assertEquals(listOf("classic", "deep", "flow", "gentle"), ui.modes.map { it.id.value })
        assertEquals(Presets.CLASSIC_ID, ui.selected)
        assertEquals(25, ui.focusMinutes)
        assertEquals(5, ui.breakMinutes)
        assertEquals(listOf("Thesis ch.3"), ui.tasks)
        assertEquals(0 to Settings.DEFAULT_TARGET, ui.done to ui.target)
        vm.chooseTask("Thesis ch.3")
        runCurrent()
        assertEquals("Thesis ch.3", vm.ui.value.chosenTask)
        vm.begin()
        assertEquals(Input.Begin(intention = "Thesis ch.3"), port.sent.last())
        assertTrue(port.rejected.isEmpty())
    }

    @Test
    fun `a new task typed in the draft is added and chosen, an empty one is dropped`() = runTest(main) {
        val port = FakePort()
        val vm = HomeViewModel(port)
        watch(vm.ui)
        vm.openDraft()
        runCurrent()
        assertEquals("", vm.ui.value.draft)
        vm.editDraft("  Taxes ")
        vm.addDraft()
        runCurrent()
        assertNull(vm.ui.value.draft)
        assertEquals("Taxes", vm.ui.value.chosenTask)
        assertEquals("Taxes", port.state.value.tasks.first().title)
        vm.openDraft()
        vm.addDraft()
        runCurrent()
        assertEquals(1, port.sent.count { it is Input.AddTask })
    }

    @Test
    fun `deep's Begin on Home opens the ritual with the chosen task, never focus directly`() = runTest(main) {
        val port = FakePort(settings = Settings(selectedMode = Presets.DEEP_ID))
        port.send(Input.AddTask("Thesis"))
        val vm = HomeViewModel(port)
        watch(vm.ui)
        assertEquals(IntentionRule.Required, vm.ui.value.intention)
        assertTrue(vm.ui.value.ritual)
        assertTrue(vm.ui.value.canBegin) // the ritual asks for the intention
        vm.chooseTask("Thesis")
        runCurrent()
        vm.begin()
        assertEquals(Input.OpenRitual("Thesis"), port.sent.last())
        assertTrue(port.sent.none { it is Input.Begin })
        assertEquals(dev.framio.pomodoro.core.engine.Phase.Ritual(Presets.DEEP_ID, "Thesis"), port.state.value.phase)
    }

    @Test
    fun `selecting a mode goes through the engine and shows its rhythm`() = runTest(main) {
        val port = FakePort()
        val vm = HomeViewModel(port)
        watch(vm.ui)
        vm.selectMode(Presets.GENTLE_ID)
        runCurrent()
        assertEquals(Presets.GENTLE_ID, vm.ui.value.selected)
        assertEquals(10, vm.ui.value.focusMinutes)
        assertEquals(IntentionRule.Off, vm.ui.value.intention)
    }

    @Test
    fun `the interrupted notice shows until dismissed`() = runTest(main) {
        val port = FakePort()
        port.send(Input.Begin())
        port.clock.reboot()
        port.send(Input.Restore(port.state.value.snapshot(), port.state.value.settings)) // reboot before the clock is trusted
        assertEquals(Notice.Interrupted, port.state.value.notice)
        val vm = HomeViewModel(port)
        watch(vm.ui)
        assertTrue(vm.ui.value.interrupted)
        vm.dismissNotice()
        runCurrent()
        assertFalse(vm.ui.value.interrupted)
    }

    @Test
    fun `focus counts down from the clock, pauses, resumes and extends`() = runTest(main) {
        val port = FakePort()
        port.send(Input.Begin(intention = "Review PR"))
        val vm = FocusViewModel(port)
        watch(vm.ui)
        val ui = vm.ui.value
        assertEquals("Review PR", ui.intention)
        assertEquals(25.minutes, ui.timing.left(port.now()))
        port.clock.advance(10.minutes)
        assertEquals(15.minutes, vm.ui.value.timing.left(port.now()))
        assertEquals(0.4f, vm.ui.value.timing.progress(port.now()), 0.001f)
        vm.pause()
        runCurrent()
        assertTrue(vm.ui.value.paused)
        assertTrue(vm.ui.value.controls) // paused always shows Resume
        port.clock.advance(3.minutes)
        assertEquals(15.minutes, vm.ui.value.timing.left(port.now()))
        vm.resume()
        vm.extend()
        runCurrent()
        assertFalse(vm.ui.value.paused)
        assertEquals(20.minutes, vm.ui.value.timing.left(port.now()))
    }

    @Test
    fun `a tap shows the controls for eight seconds`() = runTest(main) {
        val port = FakePort()
        port.send(Input.Begin())
        val vm = FocusViewModel(port)
        watch(vm.ui)
        vm.toggleControls()
        runCurrent()
        assertTrue(vm.ui.value.controls)
        advanceTimeBy(7.seconds)
        runCurrent()
        assertTrue(vm.ui.value.controls)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertFalse(vm.ui.value.controls)
        vm.toggleControls()
        vm.toggleControls()
        runCurrent()
        assertFalse(vm.ui.value.controls)
    }

    @Test
    fun `end asks for a resume note first, then goes home and keeps the note`() = runTest(main) {
        val port = FakePort()
        port.send(Input.Begin())
        val vm = FocusViewModel(port)
        watch(vm.ui)
        port.clock.advance(4.minutes)
        vm.end()
        runCurrent()
        assertEquals("", vm.ui.value.note)
        assertTrue(port.sent.none { it is Input.EndEarly })
        vm.editNote("  figures next ")
        vm.confirmEnd()
        runCurrent()
        assertEquals(Input.EndEarly("figures next"), port.sent.last())
        assertEquals(dev.framio.pomodoro.core.engine.Phase.Idle, port.state.value.phase)
        assertEquals("figures next", port.state.value.lastResumeNote)
        assertNull(vm.ui.value.note)
    }

    @Test
    fun `keep going closes the end prompt and the block runs on`() = runTest(main) {
        val port = FakePort()
        port.send(Input.Begin())
        val vm = FocusViewModel(port)
        watch(vm.ui)
        vm.end()
        vm.cancelEnd()
        runCurrent()
        assertNull(vm.ui.value.note)
        assertTrue(port.state.value.phase is dev.framio.pomodoro.core.engine.Phase.Focus)
    }

    @Test
    fun `the break shows its prompt and the wrap-up card until answered`() = runTest(main) {
        val port = FakePort()
        port.send(Input.Begin())
        port.pass(25.minutes)
        val vm = BreakViewModel(port)
        watch(vm.ui)
        assertEquals(BreakPrompt.Move, vm.ui.value.prompt)
        assertFalse(vm.ui.value.long)
        assertTrue(vm.ui.value.pendingWrapUp)
        assertEquals(1, vm.ui.value.done)
        assertEquals(5.minutes, vm.ui.value.timing.left(port.now()))
        vm.wrapUp(Outcome.Partly)
        runCurrent()
        assertFalse(vm.ui.value.pendingWrapUp)
        vm.skip()
        assertEquals(dev.framio.pomodoro.core.engine.Phase.Ready, port.state.value.phase)
    }

    @Test
    fun `ready starts the next block with the same intention`() = runTest(main) {
        val port = FakePort()
        port.send(Input.Begin(intention = "Taxes"))
        port.pass(25.minutes)
        port.pass(5.minutes)
        val vm = ReadyViewModel(port)
        watch(vm.ui)
        assertEquals("Taxes", vm.ui.value.intention)
        assertEquals(25, vm.ui.value.nextMinutes)
        vm.start()
        assertTrue(port.state.value.phase is dev.framio.pomodoro.core.engine.Phase.Focus)
        assertEquals("Taxes", port.state.value.run?.intent?.intention)
    }

    @Test
    fun `settings change volume in steps within 0 to 100 and play the test at the stored volume`() = runTest(main) {
        val port = FakePort()
        val played = mutableListOf<Float>()
        val vm = SettingsViewModel(port) { played += it }
        watch(vm.ui)
        assertEquals(60, vm.ui.value.volumePercent)
        repeat(6) { vm.volume(SettingsViewModel.STEP) }
        runCurrent()
        assertEquals(100, vm.ui.value.volumePercent)
        vm.volume(-SettingsViewModel.STEP)
        vm.test()
        assertEquals(listOf(0.9f), played)
        vm.target(-10)
        runCurrent()
        assertEquals(1, vm.ui.value.target)
        advanceUntilIdle()
    }
}
