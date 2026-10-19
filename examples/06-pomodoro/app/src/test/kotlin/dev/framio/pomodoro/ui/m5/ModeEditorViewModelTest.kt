package dev.framio.pomodoro.ui.m5

import dev.framio.pomodoro.FakePort
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.model.CueStyle
import dev.framio.pomodoro.core.model.Display
import dev.framio.pomodoro.core.model.Face
import dev.framio.pomodoro.core.model.IntentionRule
import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.ModeRules
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Rhythm
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.core.model.Soundscape
import dev.framio.pomodoro.core.model.TouchPolicy
import dev.framio.pomodoro.ui.editor.EditorTab
import dev.framio.pomodoro.ui.editor.ModeEditorViewModel
import dev.framio.pomodoro.ui.editor.TextTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/** The mode editor on the real reducer: presets stay read-only, a copy changes as you go, and every value stays legal. */
@OptIn(ExperimentalCoroutinesApi::class)
class ModeEditorViewModelTest {
    private val main = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun <T> TestScope.watch(flow: StateFlow<T>) {
        backgroundScope.launch { flow.collect {} }
        runCurrent()
    }

    private fun TestScope.ownDeep(port: FakePort = FakePort()): Pair<ModeEditorViewModel, ModeId> {
        val preset = ModeEditorViewModel(port, Presets.DEEP_ID)
        val id = preset.duplicate() ?: error("no room")
        val vm = ModeEditorViewModel(port, id)
        watch(vm.ui)
        return vm to id
    }

    @Test
    fun `a preset is read-only - edits do nothing - and Duplicate makes an own copy`() = runTest(main) {
        val port = FakePort()
        val vm = ModeEditorViewModel(port, Presets.DEEP_ID)
        watch(vm.ui)
        assertTrue(vm.ui.value.readOnly)
        vm.focus(20)
        vm.display(Display.Full)
        vm.startName()
        assertNull(vm.ui.value.typing)
        assertTrue(port.sent.none { it is Input.SaveMode })
        val id = vm.duplicate()
        assertEquals(ModeId("own-1"), id)
        val copy = port.state.value.settings.mode(ModeId("own-1")) ?: error("not saved")
        assertEquals("Deep 2", copy.name)
        assertFalse(copy.builtIn)
        assertEquals(Presets.deep.rhythm, copy.rhythm)
        assertEquals(Presets.deep, port.state.value.settings.mode(Presets.DEEP_ID)) // the preset is untouched
    }

    @Test
    fun `dial edits are saved as you go`() = runTest(main) {
        val port = FakePort()
        val (vm, id) = ownDeep(port)
        assertFalse(vm.ui.value.readOnly)
        vm.focus(35)
        vm.shortBreak(7)
        vm.longBreak(25)
        vm.longEvery(3)
        vm.autoBreak(true)
        vm.autoFocus(true)
        vm.pauseCap(null)
        runCurrent()
        val r = port.state.value.settings.mode(id)?.rhythm as Rhythm.Fixed
        assertEquals(Rhythm.Fixed(35.minutes, 7.minutes, 25.minutes, 3, autoBreak = true, autoFocus = true, pauseCap = null), r)
        assertEquals(r, (vm.ui.value.mode?.rhythm as Rhythm.Fixed))
        // Fast edits never undo each other: the editor applies them to its own copy first
        vm.focus(40)
        vm.shortBreak(9)
        assertEquals(40.minutes, (port.state.value.settings.mode(id)?.rhythm as Rhythm.Fixed).focus)
        assertEquals(9.minutes, (port.state.value.settings.mode(id)?.rhythm as Rhythm.Fixed).short)
    }

    @Test
    fun `out-of-range values are refused, not saved`() = runTest(main) {
        val port = FakePort()
        val (vm, id) = ownDeep(port)
        val before = port.state.value.settings.mode(id)
        vm.focus(0) // the dial never sends this, but the rules hold anyway
        runCurrent()
        assertNotNull(vm.ui.value.problem)
        assertEquals(before, port.state.value.settings.mode(id))
        vm.dismissProblem()
        runCurrent()
        assertNull(vm.ui.value.problem)
        vm.longEvery(99) // clamped to the most the rules allow
        assertEquals(ModeRules.MAX_LONG_EVERY, (port.state.value.settings.mode(id)?.rhythm as Rhythm.Fixed).longEvery)
    }

    @Test
    fun `switching to Flow and back keeps the shared choices`() = runTest(main) {
        val port = FakePort()
        val (vm, id) = ownDeep(port)
        vm.fixed(false)
        val flow = port.state.value.settings.mode(id)?.rhythm as Rhythm.Flow
        assertFalse(flow.autoBreak) // Deep's
        assertEquals(10.minutes, flow.pauseCap)
        vm.nudgeAfter(60)
        vm.nudge(false)
        assertNull((port.state.value.settings.mode(id)?.rhythm as Rhythm.Flow).nudgeAfter)
        vm.nudge(true)
        assertEquals(90.minutes, (port.state.value.settings.mode(id)?.rhythm as Rhythm.Flow).nudgeAfter)
        vm.fixed(true)
        assertTrue(port.state.value.settings.mode(id)?.rhythm is Rhythm.Fixed)
    }

    @Test
    fun `rename and checklist items go through the keyboard`() = runTest(main) {
        val port = FakePort()
        val (vm, id) = ownDeep(port)
        vm.startName()
        runCurrent()
        assertEquals(TextTarget.Name, vm.ui.value.typing)
        assertEquals("Deep 2", vm.ui.value.text)
        vm.type("Late night writing, long name here")
        runCurrent()
        assertEquals(ModeRules.MAX_NAME, vm.ui.value.text.length)
        vm.type("Late night")
        vm.finishTyping()
        runCurrent()
        assertNull(vm.ui.value.typing)
        assertEquals("Late night", port.state.value.settings.mode(id)?.name)
        // A blank name is ignored, Cancel changes nothing
        vm.startName()
        vm.type("   ")
        vm.finishTyping()
        vm.startName()
        vm.type("Other")
        vm.cancelTyping()
        assertEquals("Late night", port.state.value.settings.mode(id)?.name)
        // Items: built-ins by toggle, your own by keyboard and Remove
        vm.builtInItem("water", false)
        vm.startItem()
        vm.type("Desk clear")
        vm.finishTyping()
        val items = port.state.value.settings.mode(id)?.ritual?.checklist.orEmpty()
        assertEquals(listOf("phone", "one_window", "own-1"), items.map { it.id })
        assertEquals("Desk clear", items.last().label)
        vm.builtInItem("water", true) // back in its place, before your own
        assertEquals(listOf("phone", "water", "one_window", "own-1"), port.state.value.settings.mode(id)?.ritual?.checklist?.map { it.id })
        vm.removeItem("own-1")
        assertEquals(3, port.state.value.settings.mode(id)?.ritual?.checklist?.size)
        vm.intention(IntentionRule.Off)
        vm.ifThen(false)
        assertEquals(IntentionRule.Off, port.state.value.settings.mode(id)?.ritual?.intention)
    }

    @Test
    fun `the screen page - a dark display drops seconds and locks taps, sound choices stick`() = runTest(main) {
        val port = FakePort()
        val (vm, id) = ownDeep(port)
        vm.tab(EditorTab.Screen)
        vm.display(Display.Full)
        vm.seconds(true)
        vm.holdToOpen(false)
        vm.face(Face.Horizon)
        var e = port.state.value.settings.mode(id)?.environment ?: error("gone")
        assertTrue(e.showSeconds)
        assertEquals(TouchPolicy.Open, e.touch)
        vm.display(Display.Blackout)
        e = port.state.value.settings.mode(id)?.environment ?: error("gone")
        assertFalse(e.showSeconds)
        assertTrue(e.touch is TouchPolicy.HoldToReveal)
        vm.soundscape(Soundscape.Wind)
        vm.cues(CueStyle.Soft)
        vm.checkIn(15)
        vm.knock(false)
        vm.warm(true)
        runCurrent()
        e = port.state.value.settings.mode(id)?.environment ?: error("gone")
        assertEquals(Soundscape.Wind, e.soundscape)
        assertEquals(CueStyle.Soft, e.cues)
        assertEquals(15.minutes, e.checkIn)
        assertFalse(e.knockToPeek)
        assertEquals(EditorTab.Screen, vm.ui.value.tab)
    }

    @Test
    fun `skipping the ritual screen is offered once it has run, and a deleted mode closes the editor`() = runTest(main) {
        val port = FakePort()
        val (vm, id) = ownDeep(port)
        assertFalse(vm.ui.value.canSkip)
        port.send(Input.SelectMode(id))
        port.send(Input.OpenRitual("Write"))
        port.send(Input.Begin("Write", checked = emptySet()))
        port.send(Input.EndEarly())
        runCurrent()
        assertTrue(vm.ui.value.canSkip)
        vm.skipRitual(true)
        runCurrent()
        assertTrue(vm.ui.value.skipRitual)
        assertFalse(vm.ui.value.gone)
        port.send(Input.DeleteMode(id))
        runCurrent()
        assertTrue(vm.ui.value.gone)
    }

    @Test
    fun `Duplicate stops at the carousel's limit`() = runTest(main) {
        val port = FakePort()
        val vm = ModeEditorViewModel(port, Presets.CLASSIC_ID)
        watch(vm.ui)
        repeat(Settings.MAX_MODES - Presets.all.size) { assertNotNull(vm.duplicate()) }
        runCurrent()
        assertFalse(vm.ui.value.canDuplicate)
        assertNull(vm.duplicate())
        runCurrent()
        assertEquals(ModeEditorViewModel.FULL, vm.ui.value.problem)
    }
}
