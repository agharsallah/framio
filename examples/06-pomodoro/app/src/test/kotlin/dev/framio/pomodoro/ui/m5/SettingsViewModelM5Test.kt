package dev.framio.pomodoro.ui.m5

import dev.framio.pomodoro.FakePort
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.model.Face
import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.core.model.Soundscape
import dev.framio.pomodoro.core.model.Theme
import dev.framio.pomodoro.core.model.TimerSize
import dev.framio.pomodoro.ui.settings.ExportState
import dev.framio.pomodoro.ui.settings.SettingsHost
import dev.framio.pomodoro.ui.settings.SettingsViewModel
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
import java.time.LocalTime

/** Settings (M5): modes with Undo, appearance, evening times, sound, data. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelM5Test {
    private val main = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun <T> TestScope.watch(flow: StateFlow<T>) {
        backgroundScope.launch { flow.collect {} }
        runCurrent()
    }

    @Test
    fun `modes - copy, move, delete with an Undo that puts it back in place and selected`() = runTest(main) {
        val port = FakePort()
        val vm = SettingsViewModel(port)
        watch(vm.ui)
        val id = vm.duplicate(Presets.FLOW_ID) ?: error("no room")
        vm.move(id, -2)
        port.send(Input.SelectMode(id))
        runCurrent()
        assertEquals(listOf("classic", "deep", "own-1", "flow", "gentle"), vm.ui.value.modes.map { it.id.value })
        vm.delete(Presets.CLASSIC_ID) // presets can't be deleted
        vm.delete(id)
        runCurrent()
        assertEquals("Flow 2", vm.ui.value.deleted)
        assertEquals(Presets.all.map { it.id }, port.state.value.settings.modes.map { it.id })
        assertEquals(Presets.DEEP_ID, port.state.value.settings.selectedMode)
        vm.undoDelete()
        runCurrent()
        assertNull(vm.ui.value.deleted)
        assertEquals(listOf("classic", "deep", "own-1", "flow", "gentle"), port.state.value.settings.modes.map { it.id.value })
        assertEquals(id, port.state.value.settings.selectedMode)
        // The Undo bar goes after 6 s
        vm.delete(id)
        runCurrent()
        advanceTimeBy(SettingsViewModel.UNDO.inWholeMilliseconds + 1)
        runCurrent()
        assertNull(vm.ui.value.deleted)
        vm.undoDelete() // too late: nothing
        assertNull(port.state.value.settings.mode(id))
    }

    @Test
    fun `appearance, evening times wrapping midnight, keyboard, sound volume and preview`() = runTest(main) {
        val port = FakePort()
        val previews = mutableListOf<Pair<Soundscape, Float>>()
        val vm = SettingsViewModel(port, SettingsHost(preview = { s, v -> previews += s to v }))
        watch(vm.ui)
        vm.theme(Theme.Forest)
        vm.accent(5)
        vm.face(Face.Digits)
        vm.timerSize(TimerSize.Large)
        vm.reduceMotion(true)
        vm.systemKeyboard(true)
        repeat(9) { vm.eveningStart(SettingsViewModel.TIME_STEP) } // 20:00 → 00:30
        vm.eveningEnd(-SettingsViewModel.TIME_STEP)
        vm.evening(false)
        repeat(5) { vm.soundscapeVolume(-SettingsViewModel.STEP) }
        vm.preview(Soundscape.Rain)
        runCurrent()
        val s = port.state.value.settings
        assertEquals(Theme.Forest, s.theme)
        assertEquals(5, s.accent)
        assertEquals(Face.Digits, s.face)
        assertEquals(TimerSize.Large, s.timerSize)
        assertTrue(s.reduceMotion && s.systemKeyboard)
        assertEquals(LocalTime.of(0, 30), s.eveningStart)
        assertEquals(LocalTime.of(5, 30), s.eveningEnd)
        assertFalse(s.evening)
        assertEquals(0f, s.soundscapeVolume)
        assertEquals(listOf(Soundscape.Rain to 0f), previews)
        assertEquals(0, vm.ui.value.soundscapePercent)
        vm.accent(null)
        vm.face(null)
        assertNull(port.state.value.settings.accent)
        assertNull(port.state.value.settings.face)
    }

    @Test
    fun `reset keeps your modes, and Undo brings every setting back`() = runTest(main) {
        val port = FakePort()
        val vm = SettingsViewModel(port)
        watch(vm.ui)
        val id = vm.duplicate(Presets.DEEP_ID) ?: error("no room")
        vm.theme(Theme.Tomato)
        vm.target(3)
        vm.reset()
        runCurrent()
        assertTrue(vm.ui.value.reset)
        assertEquals(Theme.Ink, port.state.value.settings.theme)
        assertEquals(Settings.DEFAULT_TARGET, port.state.value.settings.dailyTarget)
        assertTrue(port.state.value.settings.mode(id) != null)
        vm.undoReset()
        runCurrent()
        assertFalse(vm.ui.value.reset)
        assertEquals(Theme.Tomato, port.state.value.settings.theme)
        assertEquals(Settings.DEFAULT_TARGET + 3, port.state.value.settings.dailyTarget)
    }

    @Test
    fun `export reports where it went, or that it failed`() = runTest(main) {
        val port = FakePort()
        var fail = false
        val vm = SettingsViewModel(port, SettingsHost(export = { if (fail) error("disk full") else "/sdcard/x/export" }))
        watch(vm.ui)
        vm.export()
        runCurrent()
        assertEquals(ExportState.Done("/sdcard/x/export"), vm.ui.value.export)
        fail = true
        vm.export()
        runCurrent()
        assertEquals(ExportState.Failed, vm.ui.value.export)
    }

    @Test
    fun `ritual screens are listed per mode and can be skipped after a first run`() = runTest(main) {
        val port = FakePort(settings = Settings(selectedMode = Presets.DEEP_ID))
        val vm = SettingsViewModel(port)
        watch(vm.ui)
        assertEquals(listOf(Presets.DEEP_ID), vm.ui.value.rituals.map { it.id })
        assertFalse(vm.ui.value.rituals.single().canSkip)
        vm.skipRitual(Presets.DEEP_ID, true) // refused by the engine before a first run
        assertTrue(port.rejected.isNotEmpty())
        port.send(Input.OpenRitual("Write"))
        port.send(Input.Begin("Write", checked = emptySet()))
        port.send(Input.EndEarly())
        port.send(Input.Park("00")) // refused (not focusing): harmless
        runCurrent()
        assertTrue(vm.ui.value.rituals.single().canSkip)
        vm.skipRitual(Presets.DEEP_ID, true)
        runCurrent()
        assertTrue(vm.ui.value.rituals.single().skip)
        assertEquals(ModeId("deep"), vm.ui.value.rituals.single().id)
    }
}
