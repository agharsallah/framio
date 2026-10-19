package dev.framio.pomodoro.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.framio.pomodoro.R
import dev.framio.pomodoro.core.history.DayGroup
import dev.framio.pomodoro.core.history.SessionView
import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.SessionId
import dev.framio.pomodoro.core.model.TaskId
import dev.framio.pomodoro.core.notes.Entry
import dev.framio.pomodoro.core.notes.TagId
import dev.framio.pomodoro.core.notes.TagScope
import dev.framio.pomodoro.core.notes.Taxonomy
import dev.framio.pomodoro.core.notes.body
import dev.framio.pomodoro.core.notes.tags
import dev.framio.pomodoro.data.HistoryPort
import dev.framio.pomodoro.ui.LocalLandscape
import dev.framio.pomodoro.ui.components.Chip
import dev.framio.pomodoro.ui.components.INK_ASPECT
import dev.framio.pomodoro.ui.components.InkThumb
import dev.framio.pomodoro.ui.components.QuietButton
import dev.framio.pomodoro.ui.components.QuietText
import dev.framio.pomodoro.ui.components.SkeletonRows
import dev.framio.pomodoro.ui.components.QuietTextButton
import dev.framio.pomodoro.ui.deep.ParkPad
import dev.framio.pomodoro.ui.notes.ChooserSheet
import dev.framio.pomodoro.ui.notes.ReasonChips
import dev.framio.pomodoro.ui.notes.TagGlyph
import dev.framio.pomodoro.ui.notes.UndoBar
import dev.framio.pomodoro.ui.notes.dayText
import dev.framio.pomodoro.ui.notes.durationText
import dev.framio.pomodoro.ui.notes.outcomeText
import dev.framio.pomodoro.ui.theme.ChartColors
import dev.framio.pomodoro.ui.theme.QuietTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun HistoryRoute(
    history: HistoryPort,
    today: () -> LocalDate,
    onClose: () -> Unit,
    onInsights: () -> Unit,
    modifier: Modifier = Modifier,
    vm: HistoryViewModel = viewModel(factory = HistoryViewModel.factory(history, today)),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    HistoryScreen(
        ui = ui,
        today = remember(ui.groups) { today() },
        actions = HistoryActions(
            close = onClose, insights = onInsights, setRange = vm::setRange, toggleTask = vm::toggleTask, toggleMode = vm::toggleMode,
            toggleOutcome = vm::toggleOutcome, toggleTag = vm::toggleTag, toggleNotes = vm::toggleNotes, clearFilters = vm::clearFilters,
            openSheet = vm::openSheet, closeSheet = vm::closeSheet, select = vm::select, closeDetail = vm::closeDetail,
            setOutcome = vm::setOutcome, setRating = vm::setRating, toggleEntryTag = vm::toggleEntryTag, addDistraction = vm::addDistraction,
            saveNote = vm::saveNote, toggleResolved = vm::toggleResolved, deleteEntry = vm::deleteEntry, deleteSession = vm::deleteSession,
            undo = vm::undo,
        ),
        modifier = modifier,
    )
}

class HistoryActions(
    val close: () -> Unit = {},
    val insights: () -> Unit = {},
    val setRange: (DateRange) -> Unit = {},
    val toggleTask: (TaskId) -> Unit = {},
    val toggleMode: (ModeId) -> Unit = {},
    val toggleOutcome: (Outcome) -> Unit = {},
    val toggleTag: (TagId) -> Unit = {},
    val toggleNotes: () -> Unit = {},
    val clearFilters: () -> Unit = {},
    val openSheet: (HistorySheet) -> Unit = {},
    val closeSheet: () -> Unit = {},
    val select: (SessionId) -> Unit = {},
    val closeDetail: () -> Unit = {},
    val setOutcome: (Outcome) -> Unit = {},
    val setRating: (Int) -> Unit = {},
    val toggleEntryTag: (TagId) -> Unit = {},
    val addDistraction: (TagId) -> Unit = {},
    val saveNote: (String?) -> Unit = {},
    val toggleResolved: (Entry.Parked) -> Unit = {},
    val deleteEntry: (Entry) -> Unit = {},
    val deleteSession: () -> Unit = {},
    val undo: () -> Unit = {},
)

/**
 * History (DESIGN-history §7): one row of filters over a day-grouped list; a session's detail on the right (full
 * screen standing) with its notes on a timeline. Edits are written at once; deletes offer Undo, never a dialog.
 */
@Composable
fun HistoryScreen(ui: HistoryUi, today: LocalDate, actions: HistoryActions, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val landscape = LocalLandscape.current
    Box(modifier.fillMaxSize().background(colors.background)) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = QuietTheme.spacing.gutter, end = if (ui.detail != null && landscape) 16.dp else QuietTheme.spacing.gutter, top = 28.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QuietText(stringResource(R.string.history), QuietTheme.type.title, maxLines = 1)
                    Spacer(Modifier.width(24.dp))
                    if (!ui.loading) {
                        QuietText(stringResource(R.string.shown_of, ui.shown, ui.total), QuietTheme.type.caption, color = colors.inkDim, maxLines = 1)
                    }
                    Spacer(Modifier.weight(1f))
                    if (ui.detail == null || !landscape) QuietTextButton(stringResource(R.string.insights_open), actions.insights)
                    QuietTextButton(stringResource(R.string.close), actions.close, color = colors.ink)
                }
                Spacer(Modifier.height(12.dp))
                Filters(ui, actions)
                Spacer(Modifier.height(12.dp))
                when {
                    ui.loading -> SkeletonRows(stringResource(R.string.loading))
                    ui.total == 0 -> Message(stringResource(R.string.history_empty))
                    ui.groups.isEmpty() -> Message(stringResource(R.string.history_none_match))
                    else -> SessionList(ui, today, actions)
                }
            }
            if (ui.detail != null && landscape) {
                Detail(ui.detail, ui, today, actions, Modifier.width(600.dp).fillMaxHeight())
            }
        }
        if (ui.detail != null && !landscape) {
            Detail(ui.detail, ui, today, actions, Modifier.fillMaxSize())
        }
        Sheet(ui, actions)
        ui.undo?.let { UndoBar(stringResource(it), actions.undo, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) }
    }
}

@Composable
private fun Message(text: String, modifier: Modifier = Modifier) {
    QuietText(text, QuietTheme.type.body, color = QuietTheme.colors.inkDim, modifier = modifier.padding(top = 40.dp))
}

@Composable
private fun Filters(ui: HistoryUi, actions: HistoryActions, modifier: Modifier = Modifier) {
    val ink = QuietTheme.colors.ink
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Chip(stringResource(ui.range.label) + " ▾", selected = ui.range != DateRange.All, accent = ink, onClick = { actions.openSheet(HistorySheet.Range) })
        FilterChip(R.string.filter_task, ui.filter.tasks.size) { actions.openSheet(HistorySheet.Tasks) }
        FilterChip(R.string.filter_mode, ui.filter.modes.size) { actions.openSheet(HistorySheet.Modes) }
        FilterChip(R.string.filter_outcome, ui.filter.outcomes.size) { actions.openSheet(HistorySheet.Outcomes) }
        FilterChip(R.string.filter_reason, ui.filter.tags.size) { actions.openSheet(HistorySheet.Reasons) }
        Chip(stringResource(R.string.filter_notes), selected = ui.filter.withNotes, accent = ink, onClick = actions.toggleNotes)
        if (ui.filtered) QuietTextButton(stringResource(R.string.filter_clear), actions.clearFilters)
    }
}

@Composable
private fun FilterChip(label: Int, count: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val text = if (count > 0) stringResource(R.string.filter_count, stringResource(label), count) else stringResource(label)
    Chip("$text ▾", selected = count > 0, accent = QuietTheme.colors.ink, onClick = onClick, modifier = modifier)
}

@Composable
private fun SessionList(ui: HistoryUi, today: LocalDate, actions: HistoryActions, modifier: Modifier = Modifier) {
    val compact = ui.detail != null && LocalLandscape.current
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        ui.groups.forEach { g ->
            item(key = "d-${g.date}") { DayHeader(g, today) }
            items(g.sessions, key = { it.id.value }) { s ->
                SessionRow(s, ui, selected = ui.detail?.id == s.id, compact = compact, onClick = { actions.select(s.id) })
            }
        }
    }
}

@Composable
private fun DayHeader(g: DayGroup, today: LocalDate, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        QuietText(dayText(g.date, today), QuietTheme.type.label, maxLines = 1, modifier = Modifier.weight(1f))
        QuietText(pluralStringResource(R.plurals.day_summary, g.sessions.size, g.sessions.size, durationText(g.focused)), QuietTheme.type.caption, color = colors.inkDim, maxLines = 1)
    }
}

private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** One session in 72 px: time · mode · task · minutes · outcome · rating · reason glyphs · notes · ↳. */
@Composable
private fun SessionRow(s: SessionView, ui: HistoryUi, selected: Boolean, compact: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val r = s.record
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = QuietTheme.Target)
            .clip(shape)
            .background(if (selected) colors.surfaceHigh else colors.background)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuietText(s.startLocal(ui.zone)?.format(TimeFormat) ?: "–", QuietTheme.type.caption, color = colors.inkDim, maxLines = 1, modifier = Modifier.width(72.dp))
        if (!compact) QuietText(r.modeName ?: r.mode.value, QuietTheme.type.caption, color = colors.inkDim, maxLines = 1, modifier = Modifier.width(110.dp))
        QuietText(r.taskTitle ?: r.intention ?: "", QuietTheme.type.body, maxLines = 1, modifier = Modifier.weight(1f))
        QuietText(durationText(r.focused), QuietTheme.type.caption, maxLines = 1, modifier = Modifier.width(96.dp), align = TextAlign.End)
        Spacer(Modifier.width(16.dp))
        if (!compact) {
            QuietText(outcomeText(r.outcome), QuietTheme.type.caption, color = colors.inkDim, maxLines = 1, modifier = Modifier.width(120.dp))
            QuietText(r.rating?.let { stringResource(R.string.rating_stars, it) } ?: "", QuietTheme.type.caption, color = colors.inkDim, maxLines = 1, modifier = Modifier.width(48.dp))
        }
        ReasonGlyphs(s.reasonTags, ui.tags, Modifier.width(if (compact) 120.dp else 168.dp), max = if (compact) 3 else 4)
        val notes = s.entries.count { it !is Entry.Distraction && it !is Entry.EndReason && it !is Entry.ResumeNote } + s.legacyParked
        if (!compact) {
            QuietText(if (notes > 0) "✎$notes" else "", QuietTheme.type.caption, color = colors.inkDim, maxLines = 1, modifier = Modifier.width(44.dp))
            QuietText(if (s.entries.any { it is Entry.ResumeNote }) "↳" else "", QuietTheme.type.caption, color = colors.inkDim, maxLines = 1, modifier = Modifier.width(24.dp))
        }
    }
}

/** Up to four reason glyphs with their colour dots, then "+n". */
@Composable
private fun ReasonGlyphs(tags: List<TagId>, taxonomy: Taxonomy, modifier: Modifier = Modifier, max: Int = 4) {
    val colors = QuietTheme.colors
    val chart = ChartColors.of(colors)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        tags.take(max).forEach { id ->
            val t = taxonomy.tag(id)
            Box(Modifier.size(8.dp).clip(CircleShape).background(chart.tag(t?.hue ?: -1)))
            TagGlyph(t?.icon ?: dev.framio.pomodoro.core.notes.TagIcon.Dots, colors.inkDim, colors.background, Modifier.size(20.dp))
        }
        if (tags.size > max) QuietText("+${tags.size - max}", QuietTheme.type.caption, color = colors.inkDim, maxLines = 1)
    }
}

/** A session in full: corrections at the top, the notes timeline, and what can be added. */
@Composable
private fun Detail(s: SessionView, ui: HistoryUi, today: LocalDate, actions: HistoryActions, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val r = s.record
    val ink = colors.ink
    Column(modifier.background(colors.surface).padding(horizontal = 32.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                QuietText("${r.modeName ?: r.mode.value} · ${r.taskTitle ?: r.intention ?: ""}", QuietTheme.type.title, maxLines = 1)
                val start = s.startLocal(ui.zone)?.format(TimeFormat).orEmpty()
                QuietText("${dayText(s.date, today)} $start", QuietTheme.type.caption, color = colors.inkDim, maxLines = 1)
            }
            QuietTextButton(stringResource(R.string.close), actions.closeDetail, color = ink)
        }
        Spacer(Modifier.height(12.dp))
        QuietText(
            r.planned?.let { stringResource(R.string.focused_of, durationText(r.focused), durationText(it)) } ?: durationText(r.focused),
            QuietTheme.type.body,
            maxLines = 1,
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val outcomes = if (r.outcome.completed) listOf(Outcome.Done, Outcome.Partly, Outcome.NotYet, Outcome.Unrated) else listOf(r.outcome)
            outcomes.forEach { o -> Chip(outcomeText(o), selected = r.outcome == o, accent = ink, onClick = { if (r.outcome.completed) actions.setOutcome(o) }) }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuietText(stringResource(R.string.rating_label), QuietTheme.type.caption, color = colors.inkDim, maxLines = 1, modifier = Modifier.width(64.dp))
            for (n in 1..5) Chip("$n", selected = r.rating == n, accent = ink, onClick = { actions.setRating(n) })
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (s.legacyParked > 0) {
                QuietText(stringResource(R.string.legacy_parked, s.legacyParked), QuietTheme.type.caption, color = colors.inkDim, maxLines = 2)
            }
            s.entries.forEach { e -> EntryRow(e, ui.tags, actions) }
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            QuietButton(stringResource(R.string.add_distraction), { actions.openSheet(HistorySheet.AddDistraction) })
            QuietButton(stringResource(R.string.add_note), { actions.openSheet(HistorySheet.AddNote) })
            QuietTextButton(stringResource(R.string.delete_session), actions.deleteSession)
        }
    }
}

/** One timeline row: the minute it happened ("after" when recalled), what it is, and its actions. */
@Composable
private fun EntryRow(e: Entry, taxonomy: Taxonomy, actions: HistoryActions, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val chart = ChartColors.of(colors)
    Row(modifier.fillMaxWidth().heightIn(min = QuietTheme.Target), verticalAlignment = Alignment.CenterVertically) {
        QuietText(
            e.at?.let { pluralStringResource(R.plurals.minutes_short, it.inWholeMinutes.toInt(), it.inWholeMinutes.toInt()) } ?: stringResource(R.string.timeline_after),
            QuietTheme.type.caption,
            color = colors.inkDim,
            maxLines = 1,
            modifier = Modifier.width(80.dp),
        )
        Box(Modifier.width(2.dp).height(QuietTheme.Target).background(colors.line))
        Spacer(Modifier.width(16.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            when (e) {
                is Entry.Distraction, is Entry.EndReason -> {
                    e.tags.firstOrNull()?.let { Box(Modifier.size(10.dp).clip(CircleShape).background(chart.tag(taxonomy.tag(it)?.hue ?: -1))) }
                    Spacer(Modifier.width(10.dp))
                    val labels = e.tags.joinToString(" · ") { taxonomy.label(it) }
                    val text = when {
                        e is Entry.EndReason -> stringResource(R.string.entry_end, labels)
                        labels.isEmpty() -> stringResource(R.string.entry_distraction_none)
                        else -> labels
                    }
                    QuietText(text, QuietTheme.type.body, maxLines = 2)
                }
                else -> {
                    val body = e.body
                    if (e is Entry.ResumeNote) QuietText(stringResource(R.string.entry_resume, body?.text.orEmpty()), QuietTheme.type.body, maxLines = 2)
                    if (e is Entry.Parked) QuietText(stringResource(R.string.entry_parked), QuietTheme.type.caption, color = colors.inkDim, maxLines = 1)
                    if (e is Entry.Note) body?.text?.let { QuietText(it, QuietTheme.type.body, maxLines = 2) }
                    body?.ink?.let { ink ->
                        Spacer(Modifier.width(10.dp))
                        InkThumb(ink, colors.ink, stringResource(R.string.note_pad_title), Modifier.size(160.dp, (160 / INK_ASPECT).dp))
                    }
                }
            }
        }
        if (e is Entry.Distraction || e is Entry.EndReason) QuietTextButton(stringResource(R.string.edit), { actions.openSheet(HistorySheet.EditReasons(e.id)) })
        if (e is Entry.Parked) QuietTextButton(stringResource(if (e.resolved) R.string.reopen else R.string.dealt_with), { actions.toggleResolved(e) })
        QuietTextButton("×", { actions.deleteEntry(e) })
    }
}

@Composable
private fun Sheet(ui: HistoryUi, actions: HistoryActions, modifier: Modifier = Modifier) {
    val ink = QuietTheme.colors.ink
    when (val sheet = ui.sheet) {
        null -> Unit
        HistorySheet.Range -> ChooserSheet(stringResource(R.string.filter_dates), actions.closeSheet, modifier) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DateRange.entries.forEach { r -> Chip(stringResource(r.label), selected = ui.range == r, accent = ink, onClick = { actions.setRange(r) }) }
            }
        }
        HistorySheet.Tasks -> ChooserSheet(stringResource(R.string.filter_task), actions.closeSheet, modifier) {
            if (ui.tasks.isEmpty()) QuietText(stringResource(R.string.history_task_none), QuietTheme.type.body, color = QuietTheme.colors.inkDim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ui.tasks.take(MAX_TASK_CHIPS).forEach { (id, title) -> Chip(title, selected = id in ui.filter.tasks, accent = ink, onClick = { actions.toggleTask(id) }) }
            }
        }
        HistorySheet.Modes -> ChooserSheet(stringResource(R.string.filter_mode), actions.closeSheet, modifier) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ui.modes.forEach { (id, name) -> Chip(name, selected = id in ui.filter.modes, accent = ink, onClick = { actions.toggleMode(id) }) }
            }
        }
        HistorySheet.Outcomes -> ChooserSheet(stringResource(R.string.filter_outcome), actions.closeSheet, modifier) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Outcome.entries.forEach { o -> Chip(outcomeText(o), selected = o in ui.filter.outcomes, accent = ink, onClick = { actions.toggleOutcome(o) }) }
            }
        }
        HistorySheet.Reasons -> ChooserSheet(stringResource(R.string.filter_reason), actions.closeSheet, modifier) {
            ReasonChips(ui.tags.tags, ui.filter.tags, actions.toggleTag)
        }
        is HistorySheet.EditReasons -> ChooserSheet(stringResource(R.string.change_reasons), actions.closeSheet, modifier) {
            val e = ui.editing
            val scope = if (e is Entry.EndReason) TagScope.EndOnly else TagScope.Any
            ReasonChips(ui.tags.offered(scope), e?.tags?.toSet().orEmpty(), actions.toggleEntryTag)
        }
        HistorySheet.AddDistraction -> ChooserSheet(stringResource(R.string.pulled_away_by), actions.closeSheet, modifier) {
            ReasonChips(ui.tags.offered(TagScope.Any), emptySet(), actions.addDistraction)
        }
        HistorySheet.AddNote -> ParkPad(
            ink, actions.saveNote, actions.closeSheet, modifier,
            title = stringResource(R.string.note_pad_title), hint = stringResource(R.string.note_pad_detail), saveLabel = stringResource(R.string.save),
        )
    }
}

private const val MAX_TASK_CHIPS = 24
