package dev.framio.pomodoro.ui.breaks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.framio.pomodoro.R
import dev.framio.pomodoro.core.engine.BreakPrompt
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.di.EnginePort
import dev.framio.pomodoro.ui.Format
import dev.framio.pomodoro.ui.LocalLandscape
import dev.framio.pomodoro.ui.components.INK_ASPECT
import dev.framio.pomodoro.ui.components.InkThumb
import dev.framio.pomodoro.ui.components.QuietButton
import dev.framio.pomodoro.ui.components.QuietText
import dev.framio.pomodoro.ui.components.QuietTextButton
import dev.framio.pomodoro.ui.components.TodayProgress
import dev.framio.pomodoro.ui.faces.ProgressLine
import dev.framio.pomodoro.ui.rememberNow
import dev.framio.pomodoro.ui.theme.QuietTheme
import dev.framio.pomodoro.ui.why.InfoButton
import dev.framio.pomodoro.ui.why.Why
import dev.framio.pomodoro.ui.why.WhySheet
import dev.framio.pomodoro.core.notes.TagId
import dev.framio.pomodoro.data.HistoryPort
import dev.framio.pomodoro.data.NoHistory
import dev.framio.pomodoro.ui.notes.ReasonChips
import dev.framio.pomodoro.ui.wrapup.WrapUpCard
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Composable
fun BreakRoute(
    port: EnginePort,
    modifier: Modifier = Modifier,
    history: HistoryPort = NoHistory,
    vm: BreakViewModel = viewModel(factory = BreakViewModel.factory(port, history)),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val now by rememberNow(vm::now, 1.seconds, ui.timing.anchorMs)
    BreakScreen(
        ui = ui,
        left = { ui.timing.left(now) },
        progress = { ui.timing.progress(now) },
        actions = BreakActions(
            wrapUp = vm::wrapUp, skip = vm::skip, extend = vm::extend, home = vm::home,
            toggleReason = vm::toggleReason, moreReasons = vm::moreReasons,
        ),
        modifier = modifier,
    )
}

class BreakActions(
    val wrapUp: (Outcome?) -> Unit = {},
    val skip: () -> Unit = {},
    val extend: () -> Unit = {},
    val home: () -> Unit = {},
    val toggleReason: (TagId) -> Unit = {},
    val moreReasons: () -> Unit = {},
)

/**
 * The break (PLAN §3.2 principles 6–7, §4.1): the slow landscape fills the screen, dimmed with the frame (the
 * display drops to its Dim level), and one prompt points away from the screen, to a real window. The words sit on
 * a quiet panel top-left (the time left, Flow's tier, the block's parked thoughts); the wrap-up card, while pending,
 * on the right; +5 min and Skip at the bottom right, clear of the hot corner.
 *
 * Built from sections ([BreakPanel]'s parts, [WrapUpCard]) that slot together: later steps (e.g. logging a distraction)
 * go in as one more section without touching the others.
 */
@Composable
fun BreakScreen(
    ui: BreakUi,
    left: () -> Duration?,
    progress: () -> Float,
    actions: BreakActions,
    modifier: Modifier = Modifier,
) {
    val colors = QuietTheme.colors
    val accent = colors.accent(ui.accent)
    val landscape = LocalLandscape.current
    var why by rememberSaveable { mutableStateOf<Why?>(null) }
    val palette = remember(ui.dayPart, colors) { landscapePalette(ui.dayPart, colors) }
    Box(modifier.fillMaxSize()) {
        Landscape(palette, motion = !ui.reduceMotion)
        // Each block of words is drawn once into its own layer and then moved as one texture: over a landscape that
        // redraws every frame, re-issuing every glyph and border each frame cost about 10 fps (LOG M4)
        val off = Modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        BreakPanel(
            Modifier
                // Standing, the panel starts below Home
                .padding(start = QuietTheme.spacing.gutter, top = if (landscape) 32.dp else 120.dp, end = if (landscape) 0.dp else QuietTheme.spacing.gutter)
                .then(if (landscape) Modifier.width(640.dp) else Modifier.fillMaxWidth())
                .then(off),
        ) {
            BreakHeader(ui.long, ui.done, ui.target, accent)
            Spacer(Modifier.height(if (landscape) 24.dp else 40.dp))
            BreakPromptSection(ui.prompt, onWhy = { why = if (ui.long) Why.LongBreak else Why.Break })
            if (ui.tier != null) {
                Spacer(Modifier.height(QuietTheme.spacing.m))
                FlowTierLine(ui.tier, accent, onWhy = { why = Why.Tiers })
            }
            Spacer(Modifier.height(if (landscape) 32.dp else 48.dp))
            BreakTimeSection(left, progress, accent)
            if (ui.parked.isNotEmpty()) {
                Spacer(Modifier.height(if (landscape) 28.dp else 40.dp))
                ParkedSection(ui.parked)
            }
        }
        QuietTextButton(
            stringResource(R.string.home),
            actions.home,
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 28.dp, end = 40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.background.copy(alpha = PANEL_ALPHA)).then(off),
        )
        if (ui.pendingWrapUp) {
            WrapUpCard(
                accent,
                actions.wrapUp,
                off.align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter)
                    .padding(end = if (landscape) QuietTheme.spacing.gutter else 0.dp, bottom = if (landscape) 0.dp else 240.dp),
            ) {
                if (ui.reasons.isNotEmpty()) ReasonsSection(ui, actions)
            }
        }
        BreakButtons(
            ui.canExtend,
            actions,
            off.align(Alignment.BottomEnd).padding(end = QuietTheme.spacing.gutter, bottom = 40.dp),
        )
        why?.let { WhySheet(it, onClose = { why = null }) }
    }
}

/** How opaque the panels over the landscape are: enough for 4.5:1 text, still showing the scene through. */
private const val PANEL_ALPHA = 0.86f

/** The quiet panel the break's words sit on, over the landscape. */
@Composable
fun BreakPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(QuietTheme.colors.background.copy(alpha = PANEL_ALPHA))
            .padding(horizontal = 36.dp, vertical = 28.dp),
        content = content,
    )
}

/** "Break" or "Long break", and today's sessions against the target. */
@Composable
fun BreakHeader(long: Boolean, done: Int, target: Int, accent: Color, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        QuietText(stringResource(if (long) R.string.break_long else R.string.break_short), QuietTheme.type.label, color = accent, maxLines = 1)
        Spacer(Modifier.weight(1f))
        TodayProgress(done, target, accent)
    }
}

/** The one thing to do away from the screen, with its (i). */
@Composable
fun BreakPromptSection(prompt: BreakPrompt, onWhy: () -> Unit, modifier: Modifier = Modifier) {
    val (title, detail) = promptText(prompt)
    Column(modifier) {
        QuietText(title, QuietTheme.type.display, maxLines = 2)
        Spacer(Modifier.height(QuietTheme.spacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            QuietText(detail, QuietTheme.type.body, color = QuietTheme.colors.inkDim, maxLines = 2, modifier = Modifier.weight(1f))
            InfoButton(onWhy)
        }
    }
}

/** Flow: "Worked 37 min → 8 min break", with the (i) for the tiers. */
@Composable
fun FlowTierLine(tier: FlowTier, accent: Color, onWhy: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        QuietText(stringResource(R.string.flow_tier, tier.workedMinutes, tier.breakMinutes), QuietTheme.type.title, color = accent, maxLines = 1)
        InfoButton(onWhy)
    }
}

/** The time left (1 Hz, its own scope) and a thin line of it. */
@Composable
fun BreakTimeSection(left: () -> Duration?, progress: () -> Float, accent: Color, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    Column(modifier) {
        BreakTime(left)
        Spacer(Modifier.height(QuietTheme.spacing.s))
        ProgressLine(progress, accent, colors.line, Modifier.fillMaxWidth().height(8.dp))
    }
}

@Composable
private fun BreakTime(left: () -> Duration?, modifier: Modifier = Modifier) {
    val minutes by remember(left) { derivedStateOf { left()?.let(Format::minutesLeft) } }
    val description = minutes?.let { pluralStringResource(R.plurals.break_left_description, it, it) }.orEmpty()
    QuietText(
        left()?.let { Format.clock(it, seconds = true) } ?: "",
        QuietTheme.type.timerSmall,
        maxLines = 1,
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
    )
}

/** The block's parked thoughts, back for the break (PLAN §3 principle 3). */
@Composable
fun ParkedSection(parked: List<String>, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val description = stringResource(R.string.parked_thoughts)
    Column(modifier) {
        QuietText(description, QuietTheme.type.caption, color = colors.inkDim, maxLines = 1)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            parked.takeLast(3).forEach { ink ->
                InkThumb(
                    ink,
                    colors.ink,
                    description,
                    Modifier.clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(8.dp).size(150.dp, (150 / INK_ASPECT).dp),
                )
            }
        }
    }
}

/** +5 min and Skip break, bottom right. */
@Composable
fun BreakButtons(canExtend: Boolean, actions: BreakActions, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        if (canExtend) QuietButton(stringResource(R.string.plus_five_break), actions.extend)
        QuietButton(stringResource(R.string.skip_break), actions.skip)
    }
}

@Composable
@ReadOnlyComposable
private fun promptText(prompt: BreakPrompt): Pair<String, String> = when (prompt) {
    BreakPrompt.Move -> stringResource(R.string.prompt_move) to stringResource(R.string.prompt_move_detail)
    BreakPrompt.Window -> stringResource(R.string.prompt_window) to stringResource(R.string.prompt_window_detail)
    BreakPrompt.Water -> stringResource(R.string.prompt_water) to stringResource(R.string.prompt_water_detail)
    BreakPrompt.Eyes -> stringResource(R.string.prompt_eyes) to stringResource(R.string.prompt_eyes_detail)
    BreakPrompt.Messages -> stringResource(R.string.prompt_messages) to stringResource(R.string.prompt_messages_detail)
}

/** The wrap-up card's "Pulled away?" row (DESIGN-history §5.5): optional, a few chips and More. */
@Composable
fun ReasonsSection(ui: BreakUi, actions: BreakActions, modifier: Modifier = Modifier) {
    Column(modifier.width(460.dp)) {
        Spacer(Modifier.height(20.dp))
        QuietText(stringResource(R.string.pulled_away), QuietTheme.type.body, color = QuietTheme.colors.inkDim, maxLines = 1)
        Spacer(Modifier.height(12.dp))
        ReasonChips(
            ui.reasons, emptySet(), actions.toggleReason, counts = ui.counts,
            limit = if (ui.moreReasons) Int.MAX_VALUE else CARD_REASONS, onMore = actions.moreReasons, gap = 12.dp,
        )
    }
}

/** Reasons on the break's card before "More": the card has to leave the landscape visible. */
private const val CARD_REASONS = 3
