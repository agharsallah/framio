@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.framio.pomodoro.ui

import dev.framio.pomodoro.FakeHistory
import dev.framio.pomodoro.FakePort
import dev.framio.pomodoro.core.history.StatsRange
import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.TaskId
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.notes.Entry
import dev.framio.pomodoro.core.notes.EntryId
import dev.framio.pomodoro.core.notes.NoteBody
import dev.framio.pomodoro.core.notes.TagId
import dev.framio.pomodoro.core.notes.TagIcon
import dev.framio.pomodoro.core.notes.Taxonomy
import dev.framio.pomodoro.core.record
import dev.framio.pomodoro.ui.history.DateRange
import dev.framio.pomodoro.ui.history.HistorySheet
import dev.framio.pomodoro.ui.history.HistoryViewModel
import dev.framio.pomodoro.ui.insights.BarUnit
import dev.framio.pomodoro.ui.insights.InsightsViewModel
import dev.framio.pomodoro.ui.settings.TagEditorViewModel
import dev.framio.pomodoro.ui.settings.TagTyping
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** History, Insights and the tag editor against an in-memory history. */
class HistoryViewModelTest {
    private val main = StandardTestDispatcher()
    private val today = LocalDate.parse("2026-10-18")

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun <T> TestScope.watch(flow: StateFlow<T>) {
        backgroundScope.launch { flow.collect {} }
        runCurrent()
    }

    private val thesis = TaskId("thesis")
    private val a = record("2026-10-18T09:00", task = thesis, rating = 4).copy(taskTitle = "Thesis", mode = ModeId("deep"), modeName = "Deep")
    private val b = record("2026-10-17T14:00", outcome = Outcome.EndedEarly, focused = 12.minutes).copy(modeName = "Classic")
    private val c = record("2026-09-01T10:00").copy(modeName = "Classic")

    private fun history() = FakeHistory(listOf(a, b, c)).also { h ->
        h.put(Entry.Distraction(EntryId("d1"), a.id, 5.minutes, null, listOf(Taxonomy.PHONE)))
        h.put(Entry.Note(EntryId("n1"), b.id, null, null, NoteBody.ink("a0")))
    }

    private fun TestScope.vm(h: FakeHistory = history()) = HistoryViewModel(h, { today }, main).also { watch(it.ui) }

    @Test
    fun `history loads on open, groups by day and lets go when closed`() = runTest(main) {
        val h = history()
        val vm = vm(h)
        val ui = vm.ui.value
        assertFalse(ui.loading)
        assertEquals(1, h.needs)
        assertEquals(listOf(today, today.minusDays(1), LocalDate.parse("2026-09-01")), ui.groups.map { it.date })
        assertEquals(3, ui.total)
        assertEquals(listOf(thesis to "Thesis"), ui.tasks)
    }

    @Test
    fun `filters by date range, task, mode, outcome, reason and notes, and clear`() = runTest(main) {
        val vm = vm()
        fun ids() = vm.ui.value.groups.flatMap { g -> g.sessions.map { it.id } }
        vm.setRange(DateRange.Week)
        runCurrent()
        assertEquals(listOf(a.id, b.id), ids())
        assertEquals(3, vm.ui.value.total)
        assertEquals(2, vm.ui.value.shown)
        vm.setRange(DateRange.Today)
        runCurrent()
        assertEquals(listOf(a.id), ids())
        vm.clearFilters()
        vm.toggleOutcome(Outcome.EndedEarly)
        runCurrent()
        assertEquals(listOf(b.id), ids())
        vm.toggleOutcome(Outcome.EndedEarly)
        vm.toggleTag(Taxonomy.PHONE)
        runCurrent()
        assertEquals(listOf(a.id), ids())
        vm.toggleTag(Taxonomy.PHONE)
        vm.toggleNotes()
        runCurrent()
        assertEquals(listOf(b.id), ids())
        vm.clearFilters()
        vm.toggleTask(thesis)
        vm.toggleMode(ModeId("deep"))
        runCurrent()
        assertEquals(listOf(a.id), ids())
        assertTrue(vm.ui.value.filtered)
        vm.clearFilters()
        runCurrent()
        assertEquals(3, ids().size)
        assertFalse(vm.ui.value.filtered)
    }

    @Test
    fun `the detail corrects outcome and rating, the same rating again clears it`() = runTest(main) {
        val vm = vm()
        vm.select(a.id)
        runCurrent()
        assertEquals(a.id, vm.ui.value.detail?.id)
        vm.setOutcome(Outcome.Partly)
        runCurrent()
        assertEquals(Outcome.Partly, vm.ui.value.detail?.record?.outcome)
        vm.setRating(4)
        runCurrent()
        assertNull(vm.ui.value.detail?.record?.rating)
        vm.setRating(2)
        runCurrent()
        assertEquals(2, vm.ui.value.detail?.record?.rating)
    }

    @Test
    fun `deleting an entry is instant with Undo for 6 s, never a dialog`() = runTest(main) {
        val vm = vm()
        vm.select(a.id)
        runCurrent()
        val d = vm.ui.value.detail!!.entries.single()
        vm.deleteEntry(d)
        runCurrent()
        assertTrue(vm.ui.value.detail!!.entries.isEmpty())
        assertNotNull(vm.ui.value.undo)
        vm.undo()
        runCurrent()
        assertEquals(listOf(d), vm.ui.value.detail!!.entries)
        assertNull(vm.ui.value.undo)
        vm.deleteEntry(d)
        advanceTimeBy(HistoryViewModel.UNDO_FOR + 0.1.seconds)
        runCurrent()
        assertNull(vm.ui.value.undo)
        vm.undo() // too late: nothing to undo
        runCurrent()
        assertTrue(vm.ui.value.detail!!.entries.isEmpty())
    }

    @Test
    fun `deleting a session hides it, and Undo brings it back open`() = runTest(main) {
        val vm = vm()
        vm.select(b.id)
        vm.deleteSession()
        runCurrent()
        assertEquals(2, vm.ui.value.total)
        assertNull(vm.ui.value.detail)
        vm.undo()
        runCurrent()
        assertEquals(3, vm.ui.value.total)
        assertEquals(b.id, vm.ui.value.detail?.id)
    }

    @Test
    fun `reasons are edited in place, a recalled distraction and a note can be added`() = runTest(main) {
        val vm = vm()
        vm.select(a.id)
        runCurrent()
        val d = vm.ui.value.detail!!.entries.single()
        vm.openSheet(HistorySheet.EditReasons(d.id))
        runCurrent()
        vm.toggleEntryTag(Taxonomy.NOISE)
        runCurrent()
        assertEquals(listOf(Taxonomy.PHONE, Taxonomy.NOISE), (vm.ui.value.editing as Entry.Distraction).tags)
        vm.closeSheet()
        vm.openSheet(HistorySheet.AddDistraction)
        vm.addDistraction(Taxonomy.TIRED)
        vm.openSheet(HistorySheet.AddNote)
        vm.saveNote("x1")
        runCurrent()
        val entries = vm.ui.value.detail!!.entries
        assertEquals(3, entries.size)
        assertNull(entries[1].at) // recalled: no time in the block
        assertTrue(entries.last() is Entry.Note)
        assertNull(vm.ui.value.sheet)
    }

    @Test
    fun `insights builds the report from the buckets for the chosen range`() = runTest(main) {
        val h = history()
        val port = FakePort()
        port.send(Input.AddTask("Thesis", estimateSessions = 3))
        val vm = InsightsViewModel(h, port, { today }, main) { 0L }
        watch(vm.ui)
        val ui = vm.ui.value
        assertFalse(ui.loading)
        assertEquals(StatsRange.Month, ui.range)
        assertEquals(BarUnit.Day, ui.unit)
        assertEquals(28, ui.bars.size)
        assertEquals(6, ui.barTarget)
        val r = ui.report!!
        assertEquals(1, r.today.sessions)
        assertEquals(2, r.started)
        vm.setRange(StatsRange.All)
        runCurrent()
        assertEquals(3, vm.ui.value.report!!.started)
        assertEquals(BarUnit.Month, vm.ui.value.unit)
        assertNull(vm.ui.value.barTarget)
        vm.setRange(StatsRange.Quarter)
        runCurrent()
        assertEquals(12, vm.ui.value.bars.size)
        assertEquals(42, vm.ui.value.barTarget)
        assertEquals(listOf("Thesis"), vm.ui.value.report!!.estimates.map { it.title })
        assertEquals(0, vm.ui.value.report!!.estimates.single().actual) // the task id is new; the log used "thesis"
    }

    @Test
    fun `insights keep up when a session is logged while open`() = runTest(main) {
        val h = history()
        val vm = InsightsViewModel(h, FakePort(), { today }, main) { 0L }
        watch(vm.ui)
        assertEquals(1, vm.ui.value.report!!.today.sessions)
        h.log(record("2026-10-18T15:00", id = "late"))
        runCurrent()
        assertEquals(2, vm.ui.value.report!!.today.sessions)
    }

    @Test
    fun `the tag editor renames and adds on the keyboard, hides, restyles, reorders, and sorts with Undo`() = runTest(main) {
        val h = history()
        val vm = TagEditorViewModel(h)
        watch(vm.ui)
        assertEquals(1, vm.ui.value.used[Taxonomy.PHONE])
        vm.startRename(Taxonomy.TECH)
        runCurrent()
        assertEquals(TagTyping.Rename(Taxonomy.TECH, "Tool/tech"), vm.ui.value.typing)
        vm.type("Slack")
        vm.finishTyping()
        runCurrent()
        assertEquals("Slack", h.tags.value.label(Taxonomy.TECH))
        assertNull(vm.ui.value.typing)
        vm.startAdd()
        vm.type("Email")
        vm.finishTyping()
        runCurrent()
        val email = h.tags.value.tags.first { it.label == "Email" }
        assertEquals(TagId("u-email"), email.id)
        vm.setHidden(Taxonomy.NOISE, true)
        vm.nextIcon(Taxonomy.PERSON)
        vm.nextHue(Taxonomy.TECH) // slot 7 → gray
        vm.down(Taxonomy.PHONE)
        runCurrent()
        assertTrue(h.tags.value.tag(Taxonomy.NOISE)!!.hidden)
        assertEquals(TagIcon.Noise, h.tags.value.tag(Taxonomy.PERSON)!!.icon)
        assertEquals(-1, h.tags.value.tag(Taxonomy.TECH)!!.hue)
        assertEquals(Taxonomy.PERSON, h.tags.value.tags.first().id)
        val before = h.tags.value
        vm.restoreDefaults()
        runCurrent()
        assertEquals("Tool/tech", h.tags.value.label(Taxonomy.TECH))
        assertTrue(h.tags.value.tag(email.id)!!.hidden) // your own tags stay, hidden
        vm.undo()
        runCurrent()
        assertEquals(before, h.tags.value)
    }
}
