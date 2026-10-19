package dev.framio.pomodoro.ui.flow

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.framio.pomodoro.R
import dev.framio.pomodoro.core.time.Now
import dev.framio.pomodoro.di.DeepHardware
import dev.framio.pomodoro.di.EnginePort
import dev.framio.pomodoro.ui.LocalLandscape
import dev.framio.pomodoro.ui.components.QuietButton
import dev.framio.pomodoro.ui.components.QuietText
import dev.framio.pomodoro.ui.deep.CALM_ALPHA
import dev.framio.pomodoro.ui.rememberNow
import dev.framio.pomodoro.ui.theme.LocalQuietColors
import dev.framio.pomodoro.ui.theme.QuietPalettes
import dev.framio.pomodoro.ui.theme.QuietTheme
import dev.framio.pomodoro.ui.why.InfoButton
import dev.framio.pomodoro.ui.why.Why
import dev.framio.pomodoro.ui.why.WhySheet
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun FlowRoute(
    port: EnginePort,
    hardware: DeepHardware,
    breath: () -> Float,
    modifier: Modifier = Modifier,
    vm: FlowViewModel = viewModel(factory = FlowViewModel.factory(port, hardware)),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    DisposableEffect(vm) { onDispose { vm.leave() } }
    FlowScreen(ui, vm::now, FlowActions(vm::tap, vm::stop, vm::pause, vm::resume), modifier, breath)
}

class FlowActions(
    val tap: () -> Unit = {},
    val stop: () -> Unit = {},
    val pause: () -> Unit = {},
    val resume: () -> Unit = {},
)

/**
 * Flow focus: the minutes worked so far, nothing else (no seconds, no arc: there is no end to count down to). Calm
 * draws it near-black at Calm's low luminance and redraws once a minute; Blackout draws nothing until a tap. After the
 * mode's nudge (90 min) one glow, and "A real break helps" stays under the time: no sound, nothing to dismiss.
 * A tap shows what stopping now earns ("8 minute break", with the (i) for the tiers), Stop here and Pause.
 */
@Composable
fun FlowScreen(
    ui: FlowUi,
    now: () -> Now,
    actions: FlowActions,
    modifier: Modifier = Modifier,
    breath: () -> Float = { 1f },
) {
    // A dark display is near-black whatever the theme: chalk and the dark-theme accents, controls included
    if (ui.dark) {
        CompositionLocalProvider(LocalQuietColors provides QuietPalettes.Ink) { FlowContent(ui, now, actions, breath, modifier) }
    } else {
        FlowContent(ui, now, actions, breath, modifier)
    }
}

@Composable
private fun FlowContent(ui: FlowUi, now: () -> Now, actions: FlowActions, breath: () -> Float, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val accent = colors.accent(ui.accent)
    val landscape = LocalLandscape.current
    var why by rememberSaveable { mutableStateOf<Why?>(null) }
    val time by rememberNow(now, ui.tick, ui.timing.anchorMs)
    val level = animateFloatAsState(if (!ui.dark || ui.controls) 1f else CALM_ALPHA, tween(if (ui.controls) 400 else 900), label = "flow")
    // The nudge: one glow when it arrives while on screen (not again on coming back)
    val glow = remember { Animatable(0f) }
    val nudgedAtStart = remember { ui.nudged }
    if (ui.nudged && !nudgedAtStart && !ui.reduceMotion) {
        LaunchedEffect(Unit) {
            glow.animateTo(1f, tween(1_200, easing = LinearEasing))
            glow.animateTo(0f, tween(2_400, easing = LinearEasing))
        }
    }
    val tapLabel = stringResource(R.string.flow_tap_hint)
    Box(
        modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = tapLabel, onClick = actions.tap),
    ) {
        if (!ui.hidden) {
            val minutes = ui.timing.focused(time)?.inWholeMinutes?.toInt() ?: 0
            val spoken = pluralStringResource(R.plurals.elapsed_description, minutes, minutes)
            Column(
                Modifier
                    .align(Alignment.Center)
                    .offset(y = if (landscape) (-70).dp else (-120).dp)
                    .graphicsLayer {
                        val s = 1f - 0.08f * sin(PI.toFloat() * breath()) // Begin's breath: 1.0 → 0.92 → 1.0
                        scaleX = s
                        scaleY = s
                        // Calm's luminance as alpha on each draw call, never an offscreen layer (LOG M3)
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        alpha = (level.value + glow.value * (1f - CALM_ALPHA) * 0.7f).coerceAtMost(1f)
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                QuietText(
                    pluralStringResource(R.plurals.minutes_short, minutes, minutes),
                    QuietTheme.type.timer,
                    color = colors.ink,
                    maxLines = 1,
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = spoken
                        liveRegion = LiveRegionMode.Polite
                    },
                )
                QuietText(
                    if (ui.paused) stringResource(R.string.paused) else stringResource(R.string.flow_label),
                    QuietTheme.type.label,
                    color = if (ui.paused) accent else colors.inkDim,
                    maxLines = 1,
                )
                if (ui.intention != null) {
                    Spacer(Modifier.height(12.dp))
                    QuietText(ui.intention, QuietTheme.type.title, color = colors.inkDim, maxLines = 1, align = TextAlign.Center, modifier = Modifier.width(720.dp))
                }
                if (ui.nudged) {
                    Spacer(Modifier.height(QuietTheme.spacing.l))
                    QuietText(stringResource(R.string.flow_nudge), QuietTheme.type.title, color = accent, maxLines = 2, align = TextAlign.Center)
                }
            }
        }
        if (ui.controls) {
            FlowControls(
                ui,
                // Read fresh when the controls open (the minute ticker may be most of a minute old)
                breakMinutes = remember(ui.controls, time) { ui.breakIfStopped(now()) },
                accent = accent,
                actions = actions,
                onWhy = { why = Why.Tiers },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (landscape) 36.dp else 120.dp),
            )
        }
        why?.let { WhySheet(it, onClose = { why = null }) }
    }
}

/** What stopping now earns, then Pause (or Resume) and Stop here: big targets on a quiet panel. */
@Composable
private fun FlowControls(
    ui: FlowUi,
    breakMinutes: Int?,
    accent: Color,
    actions: FlowActions,
    onWhy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = QuietTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(36.dp))
            .background(colors.surface)
            .padding(horizontal = 28.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (breakMinutes != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                QuietText(pluralStringResource(R.plurals.flow_stop_hint, breakMinutes, breakMinutes), QuietTheme.type.body, color = colors.inkDim, maxLines = 1)
                InfoButton(onWhy, color = colors.inkDim)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (ui.paused) {
                QuietButton(stringResource(R.string.resume), actions.resume, modifier = Modifier.width(240.dp).height(QuietTheme.BigTarget))
            } else {
                QuietButton(stringResource(R.string.pause), actions.pause, modifier = Modifier.width(240.dp).height(QuietTheme.BigTarget))
            }
            QuietButton(stringResource(R.string.flow_stop), actions.stop, fill = accent, modifier = Modifier.width(320.dp).height(QuietTheme.BigTarget))
        }
    }
}
