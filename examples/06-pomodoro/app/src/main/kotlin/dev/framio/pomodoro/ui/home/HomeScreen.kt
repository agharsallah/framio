package dev.framio.pomodoro.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.framio.pomodoro.R
import dev.framio.pomodoro.core.model.IntentionRule
import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.di.EnginePort
import dev.framio.pomodoro.ui.LocalLandscape
import dev.framio.pomodoro.ui.components.Chip
import dev.framio.pomodoro.ui.components.QuietButton
import dev.framio.pomodoro.ui.components.QuietText
import dev.framio.pomodoro.ui.components.QuietTextButton
import dev.framio.pomodoro.ui.components.ResumeLine
import dev.framio.pomodoro.ui.components.TaskDraft
import dev.framio.pomodoro.ui.components.TodayProgress
import dev.framio.pomodoro.ui.theme.QuietTheme

@Composable
fun HomeRoute(
    port: EnginePort,
    onSettings: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    onHistory: () -> Unit = {},
    onInsights: () -> Unit = {},
    vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(port)),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    HomeScreen(
        ui = ui,
        actions = HomeActions(
            selectMode = vm::selectMode,
            chooseTask = vm::chooseTask,
            openDraft = vm::openDraft,
            editDraft = vm::editDraft,
            addDraft = vm::addDraft,
            cancelDraft = vm::cancelDraft,
            begin = vm::begin,
            openRitual = vm::openRitual,
            dismissNotice = vm::dismissNotice,
            settings = onSettings,
            exit = onExit,
            history = onHistory,
            insights = onInsights,
        ),
        modifier = modifier,
    )
}

/** Everything Home can do, in one stable holder (keeps the screen's parameter list short). */
class HomeActions(
    val selectMode: (ModeId) -> Unit = {},
    val chooseTask: (String) -> Unit = {},
    val openDraft: () -> Unit = {},
    val editDraft: (String) -> Unit = {},
    val addDraft: () -> Unit = {},
    val cancelDraft: () -> Unit = {},
    val begin: () -> Unit = {},
    val openRitual: () -> Unit = {},
    val dismissNotice: () -> Unit = {},
    val settings: () -> Unit = {},
    val exit: () -> Unit = {},
    val history: () -> Unit = {},
    val insights: () -> Unit = {},
)

/**
 * HOME (PLAN §5.1): mode carousel, task chips, resume note, Begin. The bottom-left 88×88 px stay empty (the
 * launcher's hot-corner dot); Begin sits bottom-centre.
 */
@Composable
fun HomeScreen(ui: HomeUi, actions: HomeActions, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val spacing = QuietTheme.spacing
    val accent = colors.accent(ui.accent)
    val landscape = LocalLandscape.current
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = spacing.gutter, end = spacing.gutter, top = 28.dp, bottom = 56.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                QuietText(stringResource(R.string.app_name), QuietTheme.type.title, maxLines = 1)
                Spacer(Modifier.weight(1f))
                TodayProgress(ui.done, ui.target, accent)
                Spacer(Modifier.width(spacing.l))
                QuietTextButton(stringResource(R.string.history), actions.history)
                QuietTextButton(stringResource(R.string.insights), actions.insights)
                QuietTextButton(stringResource(R.string.settings), actions.settings)
                QuietTextButton(stringResource(R.string.exit), actions.exit)
            }
            Spacer(Modifier.height(if (landscape) spacing.l else spacing.xl))
            ModeCarousel(ui, actions.selectMode)
            if (ui.interrupted) {
                Spacer(Modifier.height(spacing.m))
                Notice(stringResource(R.string.notice_interrupted), actions.dismissNotice)
            }
            Spacer(Modifier.height(if (landscape) spacing.l else spacing.xl))
            if (ui.intention != IntentionRule.Off) {
                TaskPicker(ui, accent, actions)
            }
            Spacer(Modifier.weight(1f))
            BeginRow(ui, accent, actions)
        }
        if (ui.draft != null) {
            TaskDraft(ui.draft, actions.editDraft, actions.addDraft, actions.cancelDraft)
        }
    }
}

@Composable
private fun ModeCarousel(ui: HomeUi, onSelect: (ModeId) -> Unit, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    val gap = 20.dp
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (!LocalLandscape.current && ui.modes.size <= 4) {
            // Standing, four cards in a row get too narrow for their names: a 2 × 2 grid
            val width = (maxWidth - gap) / 2
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                ui.modes.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { card ->
                            ModeCardView(card, selected = card.id == ui.selected, onClick = { onSelect(card.id) }, modifier = Modifier.width(width))
                        }
                    }
                }
            }
            return@BoxWithConstraints
        }
        // Four cards fill the row; more than four (your own modes, M5) scroll and snap
        val width = (maxWidth - gap * 3) / 4
        LazyRow(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            items(ui.modes, key = { it.id.value }) { card ->
                ModeCardView(card, selected = card.id == ui.selected, onClick = { onSelect(card.id) }, modifier = Modifier.width(width))
            }
        }
    }
}

@Composable
private fun ModeCardView(card: ModeCard, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val accent = colors.accent(card.accent)
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier
            .height(156.dp)
            .clip(shape)
            .background(if (selected) colors.surfaceHigh else colors.surface)
            .border(if (selected) 3.dp else 2.dp, if (selected) accent else colors.line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(12.dp))
            QuietText(card.name, QuietTheme.type.label, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        val rhythm = if (card.focusMinutes != null && card.breakMinutes != null) {
            stringResource(R.string.rhythm_fixed, card.focusMinutes, card.breakMinutes)
        } else {
            stringResource(R.string.rhythm_flow)
        }
        QuietText(rhythm, QuietTheme.type.title, maxLines = 1)
        QuietText(stringResource(hintOf(card)), QuietTheme.type.caption, color = colors.inkDim, maxLines = 1)
    }
}

private fun hintOf(card: ModeCard): Int = when (card.id) {
    Presets.CLASSIC_ID -> R.string.mode_hint_classic
    Presets.DEEP_ID -> R.string.mode_hint_deep
    Presets.FLOW_ID -> R.string.mode_hint_flow
    Presets.GENTLE_ID -> R.string.mode_hint_gentle
    else -> R.string.mode_hint_own
}

@Composable
private fun TaskPicker(ui: HomeUi, accent: androidx.compose.ui.graphics.Color, actions: HomeActions, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    Column(modifier) {
        QuietText(
            stringResource(if (ui.intention == IntentionRule.Required) R.string.what_work_on_required else R.string.what_work_on),
            QuietTheme.type.body,
            color = colors.inkDim,
            maxLines = 1,
        )
        Spacer(Modifier.height(QuietTheme.spacing.s))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(ui.tasks, key = { it }) { title ->
                Chip(title, selected = title.equals(ui.chosenTask, ignoreCase = true), accent = accent, onClick = { actions.chooseTask(title) })
            }
            item(key = "+new") {
                Chip(stringResource(R.string.new_task), selected = false, accent = accent, onClick = actions.openDraft)
            }
        }
        if (ui.resumeNote != null || ui.resumeInk != null) {
            Spacer(Modifier.height(QuietTheme.spacing.m))
            ResumeLine(ui.resumeNote, ui.resumeInk)
        }
    }
}

@Composable
private fun BeginRow(ui: HomeUi, accent: androidx.compose.ui.graphics.Color, actions: HomeActions, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth()) {
        if (ui.ritualSkipped) {
            // Left of Begin, clear of the launcher's hot corner (x < 88)
            QuietTextButton(stringResource(R.string.open_ritual), actions.openRitual, Modifier.align(Alignment.CenterStart).padding(start = 72.dp))
        }
        QuietButton(
            label = stringResource(R.string.begin),
            onClick = actions.begin,
            fill = accent,
            big = true,
            enabled = ui.canBegin,
            modifier = Modifier.align(Alignment.Center).width(380.dp),
        )
        val focus = ui.focusMinutes
        val pause = ui.breakMinutes
        val summary = if (focus != null && pause != null) {
            stringResource(R.string.summary_fixed, focus, pause)
        } else {
            stringResource(R.string.summary_flow)
        }
        QuietText(
            summary,
            QuietTheme.type.body,
            color = QuietTheme.colors.inkDim,
            maxLines = 2,
            modifier = Modifier.align(if (LocalLandscape.current) Alignment.CenterEnd else Alignment.BottomCenter)
                .then(if (LocalLandscape.current) Modifier.width(250.dp) else Modifier.padding(top = 200.dp)),
        )
    }
}

@Composable
private fun Notice(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(colors.surface).padding(start = 28.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuietText(text, QuietTheme.type.body, modifier = Modifier.weight(1f), maxLines = 2)
        QuietTextButton(stringResource(R.string.ok), onDismiss, color = colors.ink)
    }
}
