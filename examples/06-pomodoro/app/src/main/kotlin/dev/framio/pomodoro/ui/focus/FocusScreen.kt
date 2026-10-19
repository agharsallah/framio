package dev.framio.pomodoro.ui.focus

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.framio.pomodoro.R
import dev.framio.pomodoro.di.EnginePort
import dev.framio.pomodoro.ui.Format
import dev.framio.pomodoro.ui.LocalLandscape
import dev.framio.pomodoro.ui.components.QuietButton
import dev.framio.pomodoro.ui.components.QuietText
import dev.framio.pomodoro.core.notes.TagId
import dev.framio.pomodoro.data.HistoryPort
import dev.framio.pomodoro.data.NoHistory
import dev.framio.pomodoro.ui.notes.EndActions
import dev.framio.pomodoro.ui.notes.EndSheet
import dev.framio.pomodoro.ui.notes.ReasonChips
import dev.framio.pomodoro.ui.notes.actions
import dev.framio.pomodoro.core.model.Face
import dev.framio.pomodoro.ui.faces.TimerFace
import dev.framio.pomodoro.ui.faces.digitsInside
import dev.framio.pomodoro.ui.rememberNow
import dev.framio.pomodoro.ui.theme.LocalTimerScale
import dev.framio.pomodoro.ui.theme.QuietTheme
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Composable
fun FocusRoute(
    port: EnginePort,
    modifier: Modifier = Modifier,
    breath: () -> Float = { 1f },
    history: HistoryPort = NoHistory,
    vm: FocusViewModel = viewModel(factory = FocusViewModel.factory(port, history)),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    DisposableEffect(vm) { onDispose { vm.leave() } }
    // One ticker for the screen: 1 Hz with seconds, once a minute without (PLAN §6.1)
    val now by rememberNow(vm::now, if (ui.showSeconds) 1.seconds else 1.minutes, ui.timing.anchorMs)
    FocusScreen(
        ui = ui,
        left = { ui.timing.left(now) },
        progress = { ui.timing.progress(now) },
        actions = FocusActions(
            vm::toggleControls, vm::pause, vm::resume, vm::end, { vm.extend() }, vm::editNote, vm::confirmEnd, vm::cancelEnd,
            pulledAway = vm::pulledAway, logReason = vm::logReason, closeReasons = vm::closeReasons,
            endActions = vm.end.actions(keepGoing = vm::cancelEnd, end = vm::confirmEnd),
        ),
        modifier = modifier,
        breath = breath,
    )
}

class FocusActions(
    val tap: () -> Unit = {},
    val pause: () -> Unit = {},
    val resume: () -> Unit = {},
    val end: () -> Unit = {},
    val extend: () -> Unit = {},
    val editNote: (String) -> Unit = {},
    val confirmEnd: () -> Unit = {},
    val cancelEnd: () -> Unit = {},
    val pulledAway: () -> Unit = {},
    val logReason: (TagId) -> Unit = {},
    val closeReasons: () -> Unit = {},
    val endActions: EndActions = EndActions(keepGoing = cancelEnd, end = confirmEnd),
)

/**
 * Focus, Full display, with the mode's face (Ring with the digits inside; Disc and Horizon beside the digits; Digits
 * alone and large). [left] and [progress] are read in small scopes only: the ring reads
 * progress while drawing, the digits read the time in their own text, so a tick never recomposes the screen.
 * A tap anywhere shows Pause · +5 min · End for a few seconds; End asks for a resume note first. [breath] (Begin's
 * breathe-in, 0 → 1) scales the timer's own layer 1.0 → 0.92 → 1.0: nothing else is redrawn for it.
 */
@Composable
fun FocusScreen(
    ui: FocusUi,
    left: () -> Duration?,
    progress: () -> Float,
    actions: FocusActions,
    modifier: Modifier = Modifier,
    breath: () -> Float = { 1f },
) {
    val colors = QuietTheme.colors
    val accent = colors.accent(ui.accent)
    val landscape = LocalLandscape.current
    // Settings → Appearance → Timer size scales the face and its digits together
    val scale = LocalTimerScale.current
    val ring = (if (landscape) 520.dp else 640.dp) * scale
    val timerStyle = QuietTheme.type.timer.let { if (scale == 1f) it else it.copy(fontSize = it.fontSize * scale, lineHeight = it.lineHeight * scale) }
    val hugeStyle = QuietTheme.type.timerHuge.let { if (scale == 1f) it else it.copy(fontSize = it.fontSize * scale, lineHeight = it.lineHeight * scale) }
    val tapLabel = stringResource(R.string.tap_for_controls)
    Box(
        modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = tapLabel, onClick = actions.tap),
    ) {
        if (ui.intention != null) {
            QuietText(
                ui.intention,
                QuietTheme.type.title,
                color = colors.inkDim,
                maxLines = 1,
                align = TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = if (landscape) 28.dp else 96.dp, start = 140.dp, end = 140.dp),
            )
        }
        val faceAccent = if (ui.paused) colors.inkDim else accent
        // Begin's breath: the face's own layer scales 1.0 → 0.92 → 1.0, nothing else is redrawn for it
        val breathing = Modifier.graphicsLayer {
            val s = 1f - 0.08f * sin(PI.toFloat() * breath())
            scaleX = s
            scaleY = s
        }
        val center = Modifier.align(Alignment.Center).offset(y = if (landscape) (-20).dp else (-40).dp)
        when {
            ui.face == Face.Digits -> Column(center.then(breathing), horizontalAlignment = Alignment.CenterHorizontally) {
                TimerDigits(left, ui.showSeconds, ui.paused, style = hugeStyle)
                FaceLabel(ui.paused, ui.modeName)
            }
            ui.face.digitsInside -> Box(center.size(ring).then(breathing), contentAlignment = Alignment.Center) {
                TimerFace(ui.face, progress, faceAccent, colors.inkFaint, Modifier.fillMaxSize())
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TimerDigits(left, ui.showSeconds, ui.paused, style = timerStyle)
                    FaceLabel(ui.paused, ui.modeName)
                }
            }
            landscape -> Row(center.then(breathing), verticalAlignment = Alignment.CenterVertically) {
                TimerFace(ui.face, progress, faceAccent, colors.inkFaint, Modifier.size(440.dp * scale))
                Spacer(Modifier.width(56.dp))
                Column {
                    TimerDigits(left, ui.showSeconds, ui.paused, style = timerStyle)
                    FaceLabel(ui.paused, ui.modeName)
                }
            }
            else -> Column(center.then(breathing), horizontalAlignment = Alignment.CenterHorizontally) {
                TimerFace(ui.face, progress, faceAccent, colors.inkFaint, Modifier.size(560.dp * scale))
                Spacer(Modifier.height(32.dp))
                TimerDigits(left, ui.showSeconds, ui.paused, style = timerStyle)
                FaceLabel(ui.paused, ui.modeName)
            }
        }
        AnimatedVisibility(
            visible = ui.controls,
            enter = fadeIn(tween(QuietTheme.motion.quick)),
            exit = fadeOut(tween(QuietTheme.motion.quick)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (landscape) 36.dp else 96.dp),
        ) {
            Controls(ui, accent, actions)
        }
        if (ui.reasonSheet) ReasonSheet(ui, actions, Modifier.align(Alignment.BottomCenter))
        ui.end?.let { end ->
            EndSheet(end, ui.focusedMinutes, ui.plannedMinutes, ui.logged, ui.endReasons, ui.suggestions, accent, actions.endActions)
        }
    }
}

@Composable
private fun FaceLabel(paused: Boolean, modeName: String, modifier: Modifier = Modifier) {
    QuietText(
        if (paused) stringResource(R.string.paused) else stringResource(R.string.focus_label, modeName),
        QuietTheme.type.label,
        modifier = modifier,
        color = QuietTheme.colors.inkDim,
        maxLines = 1,
    )
}

/** The digits, in their own recomposition scope; their semantics change once a minute (derivedStateOf). */
@Composable
private fun TimerDigits(
    left: () -> Duration?,
    seconds: Boolean,
    paused: Boolean,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = QuietTheme.type.timer,
) {
    val minutes by remember(left) { derivedStateOf { left()?.let(Format::minutesLeft) } }
    val description = minutes?.let {
        pluralStringResource(if (paused) R.plurals.paused_left_description else R.plurals.focus_left_description, it, it)
    }.orEmpty()
    QuietText(
        text = left()?.let { Format.clock(it, seconds) } ?: "",
        style = style,
        maxLines = 1,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        },
    )
}

@Composable
private fun Controls(ui: FocusUi, accent: androidx.compose.ui.graphics.Color, actions: FocusActions, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (ui.overCap) {
            QuietText(stringResource(R.string.pause_long), QuietTheme.type.body, maxLines = 2, modifier = Modifier.padding(bottom = 20.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            val button = Modifier.width(220.dp).height(104.dp)
            if (ui.paused) {
                QuietButton(stringResource(R.string.resume), actions.resume, fill = accent, modifier = button)
            } else {
                QuietButton(stringResource(R.string.pause), actions.pause, modifier = button)
            }
            if (ui.canExtend) QuietButton(stringResource(R.string.plus_five), actions.extend, modifier = button)
            if (!ui.paused && ui.reasons.isNotEmpty()) QuietButton(stringResource(R.string.pulled_away), actions.pulledAway, modifier = button)
            QuietButton(stringResource(R.string.end), actions.end, modifier = button)
        }
    }
}

/** "Pulled away?" (DESIGN-history §5.2): every offered reason; one tap logs it and closes. Above the hot corner. */
@Composable
private fun ReasonSheet(ui: FocusUi, actions: FocusActions, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp))
            .background(colors.surfaceHigh)
            .padding(start = QuietTheme.spacing.gutter, end = QuietTheme.spacing.gutter, top = 24.dp, bottom = 104.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            QuietText(stringResource(R.string.pulled_away_by), QuietTheme.type.title, maxLines = 1, modifier = Modifier.weight(1f))
            dev.framio.pomodoro.ui.components.QuietTextButton(stringResource(R.string.close), actions.closeReasons)
        }
        Spacer(Modifier.height(16.dp))
        ReasonChips(ui.reasons, emptySet(), actions.logReason)
    }
}
