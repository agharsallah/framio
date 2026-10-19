package dev.framio.pomodoro.ui.wrapup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.framio.pomodoro.R
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.di.EnginePort
import dev.framio.pomodoro.ui.LocalLandscape
import dev.framio.pomodoro.ui.components.Chip
import dev.framio.pomodoro.ui.components.INK_ASPECT
import dev.framio.pomodoro.ui.components.InkThumb
import dev.framio.pomodoro.ui.components.QuietButton
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import dev.framio.pomodoro.core.notes.TagId
import dev.framio.pomodoro.data.HistoryPort
import dev.framio.pomodoro.data.NoHistory
import dev.framio.pomodoro.ui.components.KeyboardSheet
import dev.framio.pomodoro.ui.deep.ParkPad
import dev.framio.pomodoro.ui.notes.NotePreview
import dev.framio.pomodoro.ui.notes.ReasonChips
import dev.framio.pomodoro.ui.components.QuietText
import dev.framio.pomodoro.ui.components.QuietTextButton
import dev.framio.pomodoro.ui.theme.QuietTheme

@Composable
fun WrapUpRoute(
    port: EnginePort,
    modifier: Modifier = Modifier,
    history: HistoryPort = NoHistory,
    vm: WrapUpViewModel = viewModel(factory = WrapUpViewModel.factory(port, history)),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    WrapUpScreen(
        ui,
        WrapUpActions(
            vm::outcome, vm::rating, vm::note, vm::removeParked, vm::startBreak, vm::skipBreak, vm::home,
            toggleReason = vm::toggleReason, moreReasons = vm::moreReasons, suggest = vm::suggest, openInput = vm::openInput,
            closeInput = vm::closeInput, saveInk = vm::saveInk, clearNote = vm::clearNote, clearInk = vm::clearInk,
        ),
        modifier,
    )
}

class WrapUpActions(
    val outcome: (Outcome) -> Unit = {},
    val rating: (Int) -> Unit = {},
    val note: (String) -> Unit = {},
    val removeParked: (Int) -> Unit = {},
    val startBreak: () -> Unit = {},
    val skipBreak: () -> Unit = {},
    val home: () -> Unit = {},
    val toggleReason: (TagId) -> Unit = {},
    val moreReasons: () -> Unit = {},
    val suggest: (String) -> Unit = {},
    val openInput: (WrapInput) -> Unit = {},
    val closeInput: () -> Unit = {},
    val saveInk: (String?) -> Unit = {},
    val clearNote: () -> Unit = {},
    val clearInk: () -> Unit = {},
)

/**
 * Deep's wrap-up after the sunrise: every part optional. The questions sit in the top half (the keyboard, if "Next
 * step?" is typed, covers the bottom half); Start break is the big target bottom-centre, the hot corner stays free.
 */
@Composable
fun WrapUpScreen(ui: WrapUpUi, actions: WrapUpActions, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val accent = colors.accent(ui.accent)
    val landscape = LocalLandscape.current
    val gap = if (landscape) 12.dp else 36.dp
    val label = if (landscape) 260.dp else 704.dp
    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(start = QuietTheme.spacing.gutter, end = QuietTheme.spacing.gutter, top = 20.dp, bottom = if (landscape) 24.dp else 40.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            QuietText(
                if (ui.intention != null) stringResource(R.string.block_done_task, ui.intention) else stringResource(R.string.block_done),
                QuietTheme.type.title,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            QuietTextButton(
                if (ui.notes > 0) stringResource(R.string.add_note_count, ui.notes) else stringResource(R.string.add_note),
                { actions.openInput(WrapInput.NotePad) },
            )
            QuietTextButton(stringResource(R.string.home), actions.home)
        }
        // The questions scroll if they ever outgrow the screen; Start break stays put at the bottom
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(gap))
        if (landscape) {
            // Outcome and focus share a row lying down: the screen keeps room for reasons and the next step
            Question(true, label, stringResource(R.string.wrap_question)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutcomeChips(ui.outcome, accent, actions.outcome)
                    Spacer(Modifier.width(24.dp))
                    QuietText(stringResource(R.string.rating_label), QuietTheme.type.body, color = colors.inkDim, maxLines = 1)
                    Spacer(Modifier.width(14.dp))
                    Ratings(ui.rating, accent, actions.rating)
                }
            }
        } else {
            Question(false, label, stringResource(R.string.wrap_question)) {
                OutcomeChips(ui.outcome, accent, actions.outcome)
            }
            Spacer(Modifier.height(gap))
            Question(false, label, stringResource(R.string.focus_rating)) { Ratings(ui.rating, accent, actions.rating) }
        }
        if (ui.reasons.isNotEmpty()) {
            Spacer(Modifier.height(gap))
            Question(landscape, label, stringResource(R.string.pulled_away)) {
                ReasonChips(
                    ui.reasons, emptySet(), actions.toggleReason, counts = ui.counts,
                    limit = if (ui.moreReasons) Int.MAX_VALUE else WRAP_REASONS, onMore = actions.moreReasons, gap = 12.dp,
                )
            }
        }
        Spacer(Modifier.height(gap))
        Question(landscape, label, stringResource(R.string.next_step)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ui.suggestions.forEach { t -> Chip(t, selected = ui.note == t, accent = accent, onClick = { actions.suggest(t) }) }
                // What was written replaces its own chip: the handwriting where "Write it" was, the words where "Type it" was
                if (ui.noteInk != null) {
                    NotePreview(null, ui.noteInk, actions.clearNote, actions.clearInk)
                } else {
                    Chip(stringResource(R.string.write_it), selected = false, accent = accent, onClick = { actions.openInput(WrapInput.ResumePad) })
                }
                val typed = ui.note.takeIf { it.isNotEmpty() && it !in ui.suggestions }
                if (typed != null) {
                    NotePreview(typed, null, actions.clearNote, actions.clearInk)
                } else {
                    Chip(stringResource(R.string.type_it), selected = false, accent = accent, onClick = { actions.openInput(WrapInput.Keyboard) })
                }
            }
        }
        if (ui.parked.isNotEmpty()) {
            Spacer(Modifier.height(gap))
            Question(landscape, label, stringResource(R.string.parked_thoughts)) {
                Parked(ui.parked, actions.removeParked)
            }
        }
        }
        Spacer(Modifier.height(gap))
        Box(Modifier.fillMaxWidth()) {
            QuietButton(
                pluralStringResource(R.plurals.start_break, ui.breakMinutes, ui.breakMinutes),
                actions.startBreak,
                fill = accent,
                big = true,
                modifier = Modifier.align(if (landscape) Alignment.Center else Alignment.BottomCenter).width(if (landscape) 640.dp else 704.dp),
            )
            QuietButton(
                stringResource(R.string.skip_break),
                actions.skipBreak,
                modifier = Modifier.align(if (landscape) Alignment.CenterEnd else Alignment.TopEnd).padding(bottom = if (landscape) 0.dp else 200.dp),
            )
        }
    }
    when (ui.input) {
        WrapInput.ResumePad -> ParkPad(
            accent, actions.saveInk, actions.closeInput,
            title = stringResource(R.string.note_title), hint = stringResource(R.string.note_pad_hint), saveLabel = stringResource(R.string.save),
        )
        WrapInput.NotePad -> ParkPad(
            accent, actions.saveInk, actions.closeInput,
            title = stringResource(R.string.note_pad_title), hint = stringResource(R.string.note_pad_detail), saveLabel = stringResource(R.string.save),
        )
        WrapInput.Keyboard -> KeyboardSheet(stringResource(R.string.note_title), ui.note, actions.note, actions.closeInput, actions.closeInput, maxLength = WrapUpViewModel.MAX_NOTE)
        null -> Unit
    }
    }
}

@Composable
private fun Ratings(rating: Int?, accent: androidx.compose.ui.graphics.Color, onRate: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (r in 1..5) Chip("$r", selected = rating == r, accent = accent, onClick = { onRate(r) })
    }
}

/** Reasons shown before "More" on the wrap-up. */
private const val WRAP_REASONS = 6

/** A question: label left and answers right in landscape, stacked when standing. */
@Composable
private fun Question(
    landscape: Boolean,
    labelWidth: androidx.compose.ui.unit.Dp,
    label: String,
    modifier: Modifier = Modifier,
    answers: @Composable () -> Unit,
) {
    if (landscape) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            QuietText(label, QuietTheme.type.body, color = QuietTheme.colors.inkDim, maxLines = 2, modifier = Modifier.width(labelWidth))
            answers()
        }
    } else {
        Column(modifier) {
            QuietText(label, QuietTheme.type.body, color = QuietTheme.colors.inkDim, maxLines = 2, modifier = Modifier.width(labelWidth))
            Spacer(Modifier.height(10.dp))
            answers()
        }
    }
}

/** This block's parked thoughts, each with its own ×; the ones left are kept. */
@Composable
private fun Parked(parked: List<String>, onRemove: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val description = stringResource(R.string.parked_thoughts)
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(parked) { i, ink ->
            Row(
                Modifier.clip(RoundedCornerShape(20.dp)).background(colors.surface).padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InkThumb(ink, colors.ink, description, Modifier.size(160.dp, (160 / INK_ASPECT).dp))
                QuietTextButton("×", { onRemove(i) })
            }
        }
    }
}
