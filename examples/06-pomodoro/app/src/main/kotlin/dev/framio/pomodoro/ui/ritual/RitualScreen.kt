package dev.framio.pomodoro.ui.ritual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.framio.pomodoro.R
import dev.framio.pomodoro.core.model.IntentionRule
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.di.EnginePort
import dev.framio.pomodoro.ui.LocalLandscape
import dev.framio.pomodoro.ui.components.CheckToggle
import dev.framio.pomodoro.ui.components.Chip
import dev.framio.pomodoro.ui.components.INK_ASPECT
import dev.framio.pomodoro.ui.components.InkThumb
import dev.framio.pomodoro.ui.components.QuietButton
import dev.framio.pomodoro.ui.components.KeyboardSheet
import dev.framio.pomodoro.ui.components.TapField
import dev.framio.pomodoro.ui.components.QuietText
import dev.framio.pomodoro.ui.components.QuietTextButton
import dev.framio.pomodoro.ui.components.ResumeLine
import dev.framio.pomodoro.ui.components.TaskDraft
import dev.framio.pomodoro.ui.theme.QuietTheme
import dev.framio.pomodoro.ui.why.InfoButton
import dev.framio.pomodoro.ui.why.Why
import dev.framio.pomodoro.ui.why.WhySheet

@Composable
fun RitualRoute(
    port: EnginePort,
    modifier: Modifier = Modifier,
    vm: RitualViewModel = viewModel(factory = RitualViewModel.factory(port)),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    RitualScreen(
        ui = ui,
        actions = RitualActions(
            chooseTask = vm::chooseTask, openDraft = vm::openDraft, editDraft = vm::editDraft, addDraft = vm::addDraft,
            cancelDraft = vm::cancelDraft, editIfThen = vm::editIfThen, toggle = vm::toggle, setBlackout = vm::setBlackout,
            clearParked = vm::clearParked, begin = vm::begin, back = vm::back,
            openIfThen = vm::openIfThen, doneIfThen = vm::doneIfThen, cancelIfThen = vm::cancelIfThen,
        ),
        modifier = modifier,
    )
}

class RitualActions(
    val chooseTask: (String) -> Unit = {},
    val openDraft: () -> Unit = {},
    val editDraft: (String) -> Unit = {},
    val addDraft: () -> Unit = {},
    val cancelDraft: () -> Unit = {},
    val editIfThen: (String) -> Unit = {},
    val toggle: (String, Boolean) -> Unit = { _, _ -> },
    val setBlackout: (Boolean) -> Unit = {},
    val openIfThen: () -> Unit = {},
    val doneIfThen: () -> Unit = {},
    val cancelIfThen: () -> Unit = {},
    val clearParked: () -> Unit = {},
    val begin: () -> Unit = {},
    val back: () -> Unit = {},
)

/**
 * The ritual before a Deep block (PLAN §4.1): intention, "If I get distracted, I'll …", the checklist as big toggles
 * (a reminder, never a gate: Begin works with nothing ticked), the last resume note, and the screen for this run (Calm
 * or off). Begin sits bottom-centre; the bottom-left 88×88 px stay empty.
 */
@Composable
fun RitualScreen(ui: RitualUi, actions: RitualActions, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val accent = colors.accent(ui.accent)
    val landscape = LocalLandscape.current
    val gap = if (landscape) 16.dp else 28.dp
    var why by rememberSaveable { mutableStateOf<Why?>(null) }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(start = QuietTheme.spacing.gutter, end = QuietTheme.spacing.gutter, top = 28.dp, bottom = 40.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                QuietText(stringResource(R.string.ritual_title, ui.modeName), QuietTheme.type.title, maxLines = 1, modifier = Modifier.weight(1f))
                QuietTextButton(stringResource(R.string.back), actions.back)
            }
            Spacer(Modifier.height(gap))
            if (ui.intention != IntentionRule.Off) {
                QuietText(
                    stringResource(if (ui.intention == IntentionRule.Required) R.string.what_work_on_required else R.string.what_work_on),
                    QuietTheme.type.body,
                    color = colors.inkDim,
                    maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(ui.tasks, key = { it }) { title ->
                        Chip(title, selected = title.equals(ui.chosen, ignoreCase = true), accent = accent, onClick = { actions.chooseTask(title) })
                    }
                    item(key = "+new") { Chip(stringResource(R.string.new_task), selected = false, accent = accent, onClick = actions.openDraft) }
                }
                Spacer(Modifier.height(gap))
            }
            if (ui.ifThenOn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QuietText(stringResource(R.string.if_then_prefix), QuietTheme.type.body, color = colors.inkDim, maxLines = 1)
                    Spacer(Modifier.width(QuietTheme.spacing.s))
                    TapField(ui.ifThen, stringResource(R.string.if_then_hint), actions.openIfThen, Modifier.weight(1f), style = QuietTheme.type.body)
                }
                Spacer(Modifier.height(gap))
            }
            if (ui.checklist.isNotEmpty()) {
                Checklist(ui, accent, actions.toggle, onWhy = { why = Why.Ritual })
                Spacer(Modifier.height(gap))
            }
            Memory(ui, actions.clearParked)
            Spacer(Modifier.weight(1f))
            BeginRow(ui, accent, actions, onWhy = { why = Why.Display })
        }
        if (ui.draft != null) TaskDraft(ui.draft, actions.editDraft, actions.addDraft, actions.cancelDraft)
        if (ui.typingIfThen) {
            KeyboardSheet(
                stringResource(R.string.if_then_title), ui.ifThen, actions.editIfThen, actions.doneIfThen, actions.cancelIfThen,
                maxLength = RitualViewModel.MAX_IF_THEN, hint = stringResource(R.string.if_then_hint),
            )
        }
        why?.let { WhySheet(it, onClose = { why = null }) }
    }
}

@Composable
private fun Checklist(ui: RitualUi, accent: Color, onToggle: (String, Boolean) -> Unit, onWhy: () -> Unit, modifier: Modifier = Modifier) {
    if (LocalLandscape.current) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ui.checklist.forEach { item ->
                CheckToggle(labelOf(item), item.checked, accent, { onToggle(item.id, it) }, Modifier.weight(1f))
            }
            InfoButton(onWhy)
        }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ui.checklist.forEach { item ->
                CheckToggle(labelOf(item), item.checked, accent, { onToggle(item.id, it) }, Modifier.fillMaxWidth())
            }
            InfoButton(onWhy, Modifier.align(Alignment.End))
        }
    }
}

@Composable
@ReadOnlyComposable
private fun labelOf(item: CheckItem): String = item.label ?: when (item.id) {
    Presets.CHECK_PHONE -> stringResource(R.string.check_phone)
    Presets.CHECK_WATER -> stringResource(R.string.check_water)
    Presets.CHECK_ONE_WINDOW -> stringResource(R.string.check_one_window)
    else -> item.id
}

/** The last resume note, and the thoughts parked in earlier blocks (with Clear). */
@Composable
private fun Memory(ui: RitualUi, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    if (ui.resumeNote == null && ui.resumeInk == null && ui.parked.isEmpty()) return
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (ui.resumeNote != null || ui.resumeInk != null) {
            ResumeLine(ui.resumeNote, ui.resumeInk, Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (ui.parked.isNotEmpty()) {
            Spacer(Modifier.width(QuietTheme.spacing.m))
            QuietText(stringResource(R.string.parked_earlier), QuietTheme.type.caption, color = colors.inkDim, maxLines = 1)
            Spacer(Modifier.width(12.dp))
            val description = stringResource(R.string.parked_thoughts)
            ui.parked.takeLast(MAX_THUMBS).forEach { ink ->
                InkThumb(ink, colors.inkDim, description, Modifier.padding(end = 8.dp).size(96.dp, (96 / INK_ASPECT).dp))
            }
            QuietTextButton(stringResource(R.string.clear), onClear)
        }
    }
}

@Composable
private fun BeginRow(ui: RitualUi, accent: Color, actions: RitualActions, onWhy: () -> Unit, modifier: Modifier = Modifier) {
    val landscape = LocalLandscape.current
    Box(modifier.fillMaxWidth().height(if (landscape) 160.dp else 400.dp)) {
        // Left column, clear of the launcher's hot corner (x < 88): this run's screen (the mode's default is set in its
        // editor; "skip this screen" moved to the mode editor and Settings → Rituals in M5)
        Column(
            Modifier.align(if (landscape) Alignment.CenterStart else Alignment.TopCenter).padding(start = if (landscape) 72.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = if (landscape) Alignment.Start else Alignment.CenterHorizontally,
        ) {
            if (ui.darkChoice) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(stringResource(R.string.screen_calm), selected = !ui.blackout, accent = accent, onClick = { actions.setBlackout(false) })
                    Chip(stringResource(R.string.screen_blackout), selected = ui.blackout, accent = accent, onClick = { actions.setBlackout(true) })
                    InfoButton(onWhy)
                }
            }
        }
        QuietButton(
            label = stringResource(R.string.begin),
            onClick = actions.begin,
            fill = accent,
            big = true,
            enabled = ui.canBegin,
            // Landscape: right of centre, so the screen choice and its (i) on the left have room
            modifier = Modifier
                .align(if (landscape) Alignment.Center else Alignment.BottomCenter)
                .offset(x = if (landscape) 110.dp else 0.dp)
                .width(if (landscape) 300.dp else 380.dp),
        )
        val focus = ui.focusMinutes
        val pause = ui.breakMinutes
        if (focus != null && pause != null && landscape) {
            QuietText(
                stringResource(R.string.summary_fixed, focus, pause),
                QuietTheme.type.body,
                color = QuietTheme.colors.inkDim,
                maxLines = 2,
                modifier = Modifier.align(Alignment.CenterEnd).width(200.dp),
            )
        }
    }
}

private const val MAX_THUMBS = 4
