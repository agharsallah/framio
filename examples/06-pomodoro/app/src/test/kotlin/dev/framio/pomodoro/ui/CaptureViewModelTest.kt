@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.framio.pomodoro.ui

import dev.framio.pomodoro.FakeHardware
import dev.framio.pomodoro.FakeHistory
import dev.framio.pomodoro.FakePort
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.engine.Phase
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.core.notes.Entry
import dev.framio.pomodoro.core.notes.JournalOp
import dev.framio.pomodoro.core.notes.TagScope
import dev.framio.pomodoro.core.notes.Taxonomy
import dev.framio.pomodoro.ui.breaks.BreakViewModel
import dev.framio.pomodoro.ui.deep.DeepFocusViewModel
import dev.framio.pomodoro.ui.focus.FocusViewModel
import dev.framio.pomodoro.ui.wrapup.WrapInput
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

/** Capturing reasons and notes (DESIGN-history §5): the hold panel, the end sheet, the wrap-up and the break card. */
class CaptureViewModelTest {
    private val main = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun <T> TestScope.watch(flow: StateFlow<T>) {
        backgroundScope.launch { flow.collect {} }
        runCurrent()
    }

    private fun deep(history: FakeHistory = FakeHistory()): Triple<FakePort, FakeHistory, DeepFocusViewModel> {
        val port = FakePort(settings = Settings(selectedMode = Presets.DEEP_ID))
        port.send(Input.Begin("Thesis"))
        return Triple(port, history, DeepFocusViewModel(port, FakeHardware(), history))
    }

    @Test
    fun `the panel offers six reasons and More, in the user's order`() = runTest(main) {
        val (_, _, vm) = deep()
        watch(vm.ui)
        assertEquals(Taxonomy().offered(TagScope.Any), vm.ui.value.reasons)
        vm.hold()
        vm.moreReasons()
        runCurrent()
        assertTrue(vm.ui.value.moreReasons)
    }

    @Test
    fun `one chip tap logs a distraction at this focus time, and the panel closes by itself 2 s later`() = runTest(main) {
        val (port, h, vm) = deep()
        watch(vm.ui)
        port.clock.advance(12.minutes)
        vm.hold()
        vm.logReason(Taxonomy.PHONE)
        runCurrent()
        val d = h.entries(port.state.value.session?.id).single() as Entry.Distraction
        assertEquals(listOf(Taxonomy.PHONE), d.tags)
        assertEquals(12.minutes, d.at)
        assertEquals(setOf(Taxonomy.PHONE), vm.ui.value.logging)
        assertEquals(1, vm.ui.value.logged)
        assertTrue(vm.ui.value.panel)
        advanceTimeBy(DeepFocusViewModel.LOG_WINDOW + 0.1.seconds)
        runCurrent()
        assertFalse(vm.ui.value.panel)
        assertTrue(vm.ui.value.logging.isEmpty())
    }

    @Test
    fun `a second chip within the window adds a reason to the same distraction, a repeat tap removes it`() = runTest(main) {
        val (port, h, vm) = deep()
        watch(vm.ui)
        vm.hold()
        vm.logReason(Taxonomy.PHONE)
        advanceTimeBy(1.seconds)
        vm.logReason(Taxonomy.NOISE)
        runCurrent()
        val session = port.state.value.session?.id
        assertEquals(listOf(Taxonomy.PHONE, Taxonomy.NOISE), (h.entries(session).single() as Entry.Distraction).tags)
        vm.logReason(Taxonomy.PHONE)
        vm.logReason(Taxonomy.NOISE) // nothing left: the distraction goes
        runCurrent()
        assertTrue(h.entries(session).isEmpty())
        assertTrue(h.ops.last() is JournalOp.Delete)
        advanceTimeBy(1.5.seconds) // the window restarted at the last tap
        runCurrent()
        assertTrue(vm.ui.value.panel)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertFalse(vm.ui.value.panel)
    }

    @Test
    fun `after the window a new tap is a new distraction`() = runTest(main) {
        val (port, h, vm) = deep()
        watch(vm.ui)
        vm.hold()
        vm.logReason(Taxonomy.PHONE)
        advanceTimeBy(3.seconds)
        vm.hold()
        vm.logReason(Taxonomy.PHONE)
        runCurrent()
        assertEquals(2, h.entries(port.state.value.session?.id).size)
        assertEquals(2, vm.ui.value.logged)
    }

    @Test
    fun `ending early writes the reasons, the words and the ink, then logs ended early`() = runTest(main) {
        val (port, h, vm) = deep()
        watch(vm.ui)
        port.clock.advance(23.minutes)
        vm.askEnd()
        runCurrent()
        val ui = vm.ui.value
        assertEquals(23, ui.focusedMinutes)
        assertEquals(50, ui.plannedMinutes)
        assertEquals(listOf(Taxonomy.DONE, Taxonomy.LEAVE), ui.endReasons.take(2).map { it.id }) // end-only first
        assertEquals(listOf("Continue Thesis", "Re-read where I stopped"), ui.suggestions)
        val session = port.state.value.session?.id
        vm.end.toggle(Taxonomy.LEAVE)
        vm.end.toggle(Taxonomy.PERSON)
        vm.end.suggest("Continue Thesis")
        vm.end.openPad()
        vm.end.savePad("a0b1")
        vm.confirmEnd()
        runCurrent()
        val reason = h.entries(session).single() as Entry.EndReason
        assertEquals(setOf(Taxonomy.LEAVE, Taxonomy.PERSON), reason.tags.toSet())
        assertEquals(23.minutes, reason.at)
        assertEquals(Input.EndEarly("Continue Thesis", "a0b1"), port.sent.last())
        assertEquals(Phase.Idle, port.state.value.phase)
        assertEquals("a0b1", port.state.value.lastResumeInk)
        assertNull(vm.ui.value.end)
    }

    @Test
    fun `ending with nothing chosen writes no reason`() = runTest(main) {
        val (port, h, vm) = deep()
        watch(vm.ui)
        vm.askEnd()
        vm.confirmEnd()
        runCurrent()
        assertTrue(h.ops.isEmpty())
        assertEquals(Input.EndEarly(), port.sent.last())
    }

    @Test
    fun `full focus - Pulled away, one chip, logged and closed`() = runTest(main) {
        val port = FakePort()
        port.send(Input.Begin("Review"))
        val h = FakeHistory()
        val vm = FocusViewModel(port, h)
        watch(vm.ui)
        port.clock.advance(7.minutes)
        vm.pulledAway()
        runCurrent()
        assertTrue(vm.ui.value.reasonSheet)
        vm.logReason(Taxonomy.PERSON)
        runCurrent()
        assertFalse(vm.ui.value.reasonSheet)
        assertEquals(7.minutes, (h.entries(port.state.value.session?.id).single() as Entry.Distraction).at)
        assertEquals(1, vm.ui.value.logged)
    }

    @Test
    fun `the deep wrap-up shows what was logged, adds recalled reasons and takes them back, and keeps notes`() = runTest(main) {
        val (port, h, focus) = deep()
        watch(focus.ui)
        focus.hold()
        focus.logReason(Taxonomy.PHONE)
        port.pass(50.minutes)
        val vm = WrapUpViewModel(port, h)
        watch(vm.ui)
        val session = port.state.value.session?.id
        assertEquals(mapOf(Taxonomy.PHONE to 1), vm.ui.value.counts)
        vm.toggleReason(Taxonomy.PHONE) // a second one, recalled
        vm.toggleReason(Taxonomy.NOISE)
        runCurrent()
        assertEquals(mapOf(Taxonomy.PHONE to 2, Taxonomy.NOISE to 1), vm.ui.value.counts)
        assertNull(h.entries(session).filterIsInstance<Entry.Distraction>().last().at)
        vm.toggleReason(Taxonomy.NOISE) // taken back
        runCurrent()
        assertEquals(mapOf(Taxonomy.PHONE to 2), vm.ui.value.counts)
        vm.openInput(WrapInput.NotePad)
        vm.saveInk("n0")
        vm.openInput(WrapInput.ResumePad)
        vm.saveInk("r0")
        runCurrent()
        assertEquals(1, vm.ui.value.notes)
        assertEquals("r0", vm.ui.value.noteInk)
        vm.outcome(Outcome.Done)
        vm.startBreak()
        assertEquals(Input.WrapUp(Outcome.Done, null, null, "r0"), port.sent.last())
        assertTrue(port.state.value.phase is Phase.Break)
    }

    @Test
    fun `the classic break card logs recalled reasons for the block`() = runTest(main) {
        val port = FakePort()
        port.send(Input.Begin())
        port.pass(25.minutes)
        val h = FakeHistory()
        val vm = BreakViewModel(port, h)
        watch(vm.ui)
        assertTrue(vm.ui.value.pendingWrapUp)
        vm.toggleReason(Taxonomy.HUNGER)
        runCurrent()
        assertEquals(mapOf(Taxonomy.HUNGER to 1), vm.ui.value.counts)
        vm.toggleReason(Taxonomy.HUNGER)
        runCurrent()
        assertTrue(vm.ui.value.counts.isEmpty())
        vm.toggleReason(Taxonomy.TIRED)
        vm.wrapUp(Outcome.Partly)
        runCurrent()
        assertEquals(1, h.journal.liveEntries.size)
    }
}
