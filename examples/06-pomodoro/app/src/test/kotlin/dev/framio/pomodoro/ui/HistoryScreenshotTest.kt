package dev.framio.pomodoro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToKey
import com.github.takahirom.roborazzi.captureRoboImage
import dev.framio.pomodoro.FakePort
import dev.framio.pomodoro.core.AMSTERDAM
import dev.framio.pomodoro.core.engine.BreakPrompt
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.history.HistoryIndex
import dev.framio.pomodoro.core.history.HistorySeed
import dev.framio.pomodoro.core.history.InsightsReport
import dev.framio.pomodoro.core.history.StatsIndex
import dev.framio.pomodoro.core.history.StatsRange
import dev.framio.pomodoro.core.model.InkCodec
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.core.model.Stroke
import dev.framio.pomodoro.core.model.Task
import dev.framio.pomodoro.core.model.TaskId
import dev.framio.pomodoro.core.model.Theme
import dev.framio.pomodoro.core.notes.Entry
import dev.framio.pomodoro.core.notes.EntryId
import dev.framio.pomodoro.core.notes.Journal
import dev.framio.pomodoro.core.notes.JournalOp
import dev.framio.pomodoro.core.notes.NoteBody
import dev.framio.pomodoro.core.notes.TagScope
import dev.framio.pomodoro.core.notes.Taxonomy
import dev.framio.pomodoro.core.record
import dev.framio.pomodoro.ui.breaks.BreakActions
import dev.framio.pomodoro.ui.breaks.BreakScreen
import dev.framio.pomodoro.ui.breaks.BreakUi
import dev.framio.pomodoro.ui.components.KeyboardSheet
import dev.framio.pomodoro.ui.deep.DeepActions
import dev.framio.pomodoro.ui.deep.DeepBackdrop
import dev.framio.pomodoro.ui.deep.DeepFocusScreen
import dev.framio.pomodoro.ui.deep.DeepUi
import dev.framio.pomodoro.ui.deep.suggestionsFor
import dev.framio.pomodoro.ui.focus.FocusActions
import dev.framio.pomodoro.ui.focus.FocusScreen
import dev.framio.pomodoro.ui.focus.FocusUi
import dev.framio.pomodoro.ui.history.HistoryActions
import dev.framio.pomodoro.ui.history.HistoryScreen
import dev.framio.pomodoro.ui.history.HistoryUi
import dev.framio.pomodoro.ui.insights.InsightsScreen
import dev.framio.pomodoro.ui.insights.InsightsUi
import dev.framio.pomodoro.ui.insights.InsightsViewModel
import dev.framio.pomodoro.ui.notes.EndActions
import dev.framio.pomodoro.ui.notes.EndDraft
import dev.framio.pomodoro.ui.notes.EndSheet
import dev.framio.pomodoro.ui.settings.TagEditorActions
import dev.framio.pomodoro.ui.settings.TagEditorScreen
import dev.framio.pomodoro.ui.settings.TagEditorUi
import dev.framio.pomodoro.ui.settings.TagEditorViewModel
import dev.framio.pomodoro.ui.theme.QuietTheme
import dev.framio.pomodoro.ui.wrapup.WrapUpActions
import dev.framio.pomodoro.ui.wrapup.WrapUpScreen
import dev.framio.pomodoro.ui.wrapup.WrapUpUi
import dev.framio.pomodoro.data.StatsView
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Golden images of the history and notes work (DESIGN-history §5, §7, §8): the capture surfaces (hold panel, Full
 * focus's chips, end sheet, Deep wrap-up, break card, keyboard), History (list, detail, empty), Insights (empty,
 * sparse, rich, scrolled) and the tag editor, in Ink and Paper, landscape and standing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [SCREENSHOT_SDK], qualifiers = LANDSCAPE)
class HistoryScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.parse("2026-10-18")
    private val tags = Taxonomy()

    private fun shot(theme: Theme = Theme.Ink, calm: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            QuietTheme(theme = theme) {
                if (calm) {
                    DeepBackdrop(false) { FitToDesign { content() } }
                } else {
                    Box(Modifier.fillMaxSize().background(QuietTheme.colors.background)) { FitToDesign { content() } }
                }
            }
        }
        compose.onRoot().captureRoboImage()
    }

    private val ink: String = InkCodec.encode(
        listOf(
            Stroke(FloatArray(40) { i -> val t = (i / 2) / 19f * 2 * PI.toFloat(); if (i % 2 == 0) 0.3f + 0.15f * cos(t) else 0.5f + 0.3f * sin(t) }),
            Stroke(floatArrayOf(0.55f, 0.2f, 0.62f, 0.8f, 0.7f, 0.3f, 0.82f, 0.75f)),
        ),
    )

    // Capture

    private fun deepUi(): Pair<FakePort, DeepUi> {
        val port = FakePort(settings = Settings(selectedMode = Presets.DEEP_ID))
        port.send(Input.Begin("Thesis ch.3"))
        port.clock.advance(12.minutes)
        return port to DeepUi(
            modeName = "Deep", accent = 1, intention = "Thesis ch.3", knock = true, timing = port.timing(port.state.value),
            panel = true, reasons = tags.offered(TagScope.Any), logging = setOf(Taxonomy.PHONE), logged = 2,
        )
    }

    @Test fun holdPanelReasonsInk() = deepUi().let { (p, ui) -> shot(Theme.Ink, calm = true) { DeepFocusScreen(ui, p::now, { null }, DeepActions()) } }

    @Test fun holdPanelReasonsPaper() = deepUi().let { (p, ui) -> shot(Theme.Paper, calm = true) { DeepFocusScreen(ui.copy(logging = emptySet(), logged = 0), p::now, { null }, DeepActions()) } }

    @Test fun holdPanelMore() = deepUi().let { (p, ui) -> shot(Theme.Ink, calm = true) { DeepFocusScreen(ui.copy(moreReasons = true, logging = emptySet()), p::now, { null }, DeepActions()) } }

    @Test @Config(qualifiers = PORTRAIT)
    fun holdPanelPortrait() = deepUi().let { (p, ui) -> shot(Theme.Ink, calm = true) { DeepFocusScreen(ui, p::now, { null }, DeepActions()) } }

    private val endOnly = tags.offered(TagScope.EndOnly).let { l -> l.filter { it.scope == TagScope.EndOnly } + l.filter { it.scope != TagScope.EndOnly } }
    private val draft = EndDraft(tags = setOf(Taxonomy.LEAVE, Taxonomy.PERSON), text = "Continue Thesis ch.3", ink = ink)

    @Composable
    private fun End(d: EndDraft = draft) = EndSheet(d, 23, 50, 2, endOnly, suggestionsFor("Thesis ch.3"), QuietTheme.colors.accent(1), EndActions())

    @Test fun endSheetInk() = shot(Theme.Ink) { End() }

    @Test fun endSheetPaper() = shot(Theme.Paper) { End(EndDraft()) }

    @Test @Config(qualifiers = PORTRAIT)
    fun endSheetPortrait() = shot(Theme.Ink) { End() }

    @Test fun endSheetPad() = shot(Theme.Ink) { End(draft.copy(pad = true)) }

    @Test fun keyboardInk() = shot(Theme.Ink) { KeyboardSheet("Next step when you’re back?", "rewrite the intro", {}, {}, {}) }

    @Test @Config(qualifiers = PORTRAIT)
    fun keyboardPortrait() = shot(Theme.Paper) { KeyboardSheet("A new reason", "Ema", {}, {}, {}) }

    @Test fun focusReasonSheet() = shot(Theme.Ink) {
        FocusScreen(
            FocusUi(modeName = "Classic", accent = 0, intention = "Review PR", reasonSheet = true, reasons = tags.offered(TagScope.Any)),
            left = { 14.minutes + 20.seconds }, progress = { 0.43f }, actions = FocusActions(),
        )
    }

    private val wrap = WrapUpUi(
        accent = 1, breakMinutes = 10, intention = "Thesis ch.3", outcome = Outcome.Partly, rating = 4,
        parked = listOf(ink, ink), reasons = tags.offered(TagScope.Any), counts = mapOf(Taxonomy.PHONE to 2, Taxonomy.NOISE to 1),
        suggestions = suggestionsFor("Thesis ch.3"), note = "Continue Thesis ch.3", noteInk = ink, notes = 1,
    )

    @Test fun wrapUpReasonsInk() = shot(Theme.Ink) { WrapUpScreen(wrap, WrapUpActions()) }

    @Test fun wrapUpReasonsPaper() = shot(Theme.Paper) { WrapUpScreen(wrap.copy(parked = emptyList(), noteInk = null), WrapUpActions()) }

    @Test @Config(qualifiers = PORTRAIT)
    fun wrapUpReasonsPortrait() = shot(Theme.Ink) { WrapUpScreen(wrap, WrapUpActions()) }

    @Test fun breakCardReasons() = shot(Theme.Ink) {
        BreakScreen(
            BreakUi(prompt = BreakPrompt.Window, pendingWrapUp = true, accent = 0, done = 4, target = 6, reasons = tags.offered(TagScope.Any), counts = mapOf(Taxonomy.PERSON to 1)),
            left = { 3.minutes + 12.seconds }, progress = { 0.36f }, actions = BreakActions(),
        )
    }

    // History

    private val thesis = TaskId("thesis")
    private val sessions = listOf(
        record("2026-10-18T14:00", task = thesis, rating = 4, focused = 50.minutes).copy(taskTitle = "Thesis ch.3", modeName = "Deep", planned = 50.minutes, resumeNote = "figures, then §3.2 intro"),
        record("2026-10-18T10:10", outcome = Outcome.Partly).copy(taskTitle = "Review PR", modeName = "Classic"),
        record("2026-10-18T09:00", outcome = Outcome.EndedEarly, focused = 23.minutes, task = thesis).copy(taskTitle = "Thesis ch.3", modeName = "Deep", planned = 50.minutes),
        record("2026-10-17T16:30", rating = 3, focused = 38.minutes).copy(taskTitle = "Taxes", modeName = "Flow", planned = null),
        record("2026-10-15T11:00").copy(taskTitle = "Reading", modeName = "Classic"),
    )
    private val journal = Journal().also { j ->
        val s = sessions[0].id
        listOf(
            Entry.Parked(EntryId("p"), s, 3.minutes, null, NoteBody.ink(ink), resolved = true),
            Entry.Distraction(EntryId("d1"), s, 12.minutes, null, listOf(Taxonomy.PHONE)),
            Entry.Distraction(EntryId("d2"), s, 31.minutes, null, listOf(Taxonomy.PHONE, Taxonomy.NOISE)),
            Entry.Distraction(EntryId("d3"), s, null, 5L, listOf(Taxonomy.PERSON)),
            Entry.Note(EntryId("n"), s, null, 6L, NoteBody.ink(ink)),
            Entry.EndReason(EntryId("x"), sessions[2].id, 23.minutes, null, listOf(Taxonomy.LEAVE)),
            Entry.Distraction(EntryId("d4"), sessions[1].id, 9.minutes, null, listOf(Taxonomy.PERSON)),
        ).forEach { j.apply(JournalOp.Put(it)) }
    }
    private val index = HistoryIndex.build(sessions, journal, AMSTERDAM)
    private val historyUi = HistoryUi(
        loading = false, groups = index.query(), total = index.size, shown = index.size, tags = tags, zone = AMSTERDAM,
        tasks = index.taskChoices(),
    )

    @Test fun historyListInk() = shot(Theme.Ink) { HistoryScreen(historyUi, today, HistoryActions()) }

    @Test fun historyListPaper() = shot(Theme.Paper) { HistoryScreen(historyUi, today, HistoryActions()) }

    @Test fun historyDetailInk() = shot(Theme.Ink) { HistoryScreen(historyUi.copy(detail = index.session(sessions[0].id)), today, HistoryActions()) }

    @Test fun historyDetailPaperUndo() = shot(Theme.Paper) {
        HistoryScreen(historyUi.copy(detail = index.session(sessions[0].id), undo = dev.framio.pomodoro.R.string.deleted_entry), today, HistoryActions())
    }

    @Test @Config(qualifiers = PORTRAIT)
    fun historyDetailPortrait() = shot(Theme.Ink) { HistoryScreen(historyUi.copy(detail = index.session(sessions[0].id)), today, HistoryActions()) }

    @Test @Config(qualifiers = PORTRAIT)
    fun historyListPortrait() = shot(Theme.Ink) { HistoryScreen(historyUi, today, HistoryActions()) }

    @Test fun historyEmpty() = shot(Theme.Ink) { HistoryScreen(HistoryUi(loading = false), today, HistoryActions()) }

    @Test fun historyReasonFilter() = shot(Theme.Ink) {
        HistoryScreen(historyUi.copy(sheet = dev.framio.pomodoro.ui.history.HistorySheet.Reasons, filter = dev.framio.pomodoro.core.history.HistoryFilter(tags = setOf(Taxonomy.PHONE))), today, HistoryActions())
    }

    // Insights

    private fun insights(records: List<dev.framio.pomodoro.core.model.SessionRecord>, ops: List<JournalOp>, range: StatsRange = StatsRange.Month): InsightsUi {
        val j = Journal().also { jj -> ops.forEach { jj.apply(it) } }
        val buckets = StatsIndex(AMSTERDAM).also { it.rebuild(HistoryIndex.build(records, j, AMSTERDAM)) }.buckets
        val tasks = listOf(Task(TaskId("seed-t0"), "Thesis ch.3", estimateSessions = 60), Task(TaskId("seed-t1"), "Review PR", estimateSessions = 20))
        val (unit, bars) = InsightsViewModel.bars(buckets, today, range)
        return InsightsUi(
            loading = false, range = range, report = InsightsReport.build(buckets, today, range, 6, tasks), bars = bars, unit = unit,
            barTarget = if (unit == dev.framio.pomodoro.ui.insights.BarUnit.Day) 6 else 42, tags = tags,
        )
    }

    private val rich by lazy { HistorySeed.generate(200, today, AMSTERDAM).let { s -> insights(s.records + sessions, s.ops) } }

    @Test fun insightsEmpty() = shot(Theme.Ink) { InsightsScreen(insights(emptyList(), emptyList()), {}, {}, {}) }

    @Test fun insightsSparse() = shot(Theme.Ink) { InsightsScreen(insights(sessions, journal.liveEntries.map { JournalOp.Put(it) }), {}, {}, {}) }

    @Test fun insightsRichInk() = shot(Theme.Ink) { InsightsScreen(rich, {}, {}, {}) }

    @Test fun insightsRichPaper() = shot(Theme.Paper) { InsightsScreen(rich, {}, {}, {}) }

    @Test fun insightsRichBottom() {
        shotScrolled(Theme.Ink, "row4") { InsightsScreen(rich, {}, {}, {}) }
    }

    @Test fun insightsRichMiddle() {
        shotScrolled(Theme.Ink, "row2") { InsightsScreen(rich, {}, {}, {}) }
    }

    @Test @Config(qualifiers = PORTRAIT)
    fun insightsRichPortrait() = shot(Theme.Ink) { InsightsScreen(rich, {}, {}, {}) }

    @Test fun insightsQuarterForest() = shot(Theme.Forest) {
        InsightsScreen(HistorySeed.generate(200, today, AMSTERDAM).let { s -> insights(s.records, s.ops, StatsRange.Quarter) }, {}, {}, {})
    }

    private fun shotScrolled(theme: Theme, key: String, content: @Composable () -> Unit) {
        compose.setContent {
            QuietTheme(theme = theme) { Box(Modifier.fillMaxSize().background(QuietTheme.colors.background)) { FitToDesign { content() } } }
        }
        compose.onNode(hasScrollToKeyAction()).performScrollToKey(key)
        compose.onRoot().captureRoboImage()
    }

    // Tag editor

    private val editorUi = TagEditorUi(
        tags.setHidden(Taxonomy.NOISE, true).add("Email", dev.framio.pomodoro.core.notes.TagIcon.Book)!!.first.tags,
        TagEditorViewModel.usage(StatsView(StatsIndex(AMSTERDAM).also { it.rebuild(index) }.buckets.toList(), false, 1)),
    )

    @Test fun tagEditorInk() = shot(Theme.Ink) { TagEditorScreen(editorUi, TagEditorActions()) }

    @Test fun tagEditorPaper() = shot(Theme.Paper) { TagEditorScreen(editorUi.copy(undo = dev.framio.pomodoro.R.string.reasons_sorted), TagEditorActions()) }
}
