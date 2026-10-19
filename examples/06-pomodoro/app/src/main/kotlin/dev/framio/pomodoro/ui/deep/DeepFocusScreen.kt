package dev.framio.pomodoro.ui.deep

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.framio.pomodoro.R
import dev.framio.pomodoro.core.time.Now
import dev.framio.pomodoro.di.DeepHardware
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
import dev.framio.pomodoro.ui.rememberNow
import dev.framio.pomodoro.ui.theme.QuietPalettes
import dev.framio.pomodoro.ui.theme.QuietTheme
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun DeepFocusRoute(
    port: EnginePort,
    hardware: DeepHardware,
    breath: () -> Float,
    modifier: Modifier = Modifier,
    history: HistoryPort = NoHistory,
    vm: DeepFocusViewModel = viewModel(factory = DeepFocusViewModel.factory(port, hardware, history)),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    DisposableEffect(vm) { onDispose { vm.leave() } }
    if (ui.knock && !ui.paused) {
        // The accelerometer only while Deep focus is on screen and resumed (PLAN §6.1)
        LifecycleResumeEffect(Unit) {
            vm.listen(true)
            onPauseOrDispose { vm.listen(false) }
        }
    }
    DeepFocusScreen(
        ui = ui,
        now = vm::now,
        untilCheckIn = vm::untilCheckIn,
        actions = DeepActions(
            tap = vm::tap, hold = vm::hold, closePanel = vm::closePanel, pause = vm::pause, resume = vm::resume,
            openPark = vm::openPark, park = vm::park, cancelPark = vm::cancelPark, askEnd = vm::askEnd,
            editNote = vm::editNote, confirmEnd = vm::confirmEnd, cancelEnd = vm::cancelEnd,
            logReason = vm::logReason, moreReasons = vm::moreReasons,
            end = vm.end.actions(keepGoing = vm::cancelEnd, end = vm::confirmEnd),
        ),
        breath = breath,
        modifier = modifier,
    )
}

class DeepActions(
    val tap: () -> Unit = {},
    val hold: () -> Unit = {},
    val closePanel: () -> Unit = {},
    val pause: () -> Unit = {},
    val resume: () -> Unit = {},
    val openPark: () -> Unit = {},
    val park: (String?) -> Unit = {},
    val cancelPark: () -> Unit = {},
    val askEnd: () -> Unit = {},
    val editNote: (String) -> Unit = {},
    val confirmEnd: () -> Unit = {},
    val cancelEnd: () -> Unit = {},
    val logReason: (TagId) -> Unit = {},
    val moreReasons: () -> Unit = {},
    val end: EndActions = EndActions(keepGoing = cancelEnd, end = confirmEnd),
)

/** Calm content is the Ink chalk at this alpha over near-black: about 12 % luminance (PLAN §4.1). */
const val CALM_ALPHA: Float = 0.42f

/**
 * Deep focus (PLAN §4.1). **Calm**: near-black, one thin arc and "38 min" at about 12 % luminance, updated once a
 * minute (the arc eases forward for 600 ms, then nothing draws until the next minute). **Blackout**: the backlight is
 * off and nothing is composed at all until a peek or the panel. Taps do nothing but a faint "hold to open"; a 1.2 s
 * hold opens the slim panel. The background is the root's (it deepens during Begin's breath).
 */
@Composable
fun DeepFocusScreen(
    ui: DeepUi,
    now: () -> Now,
    untilCheckIn: () -> kotlin.time.Duration?,
    actions: DeepActions,
    modifier: Modifier = Modifier,
    breath: () -> Float = { 1f },
) {
    val colors = QuietTheme.colors
    val accent = colors.accent(ui.accent)
    var pressed by remember { mutableStateOf(false) }
    val description = stringResource(R.string.deep_locked_description)
    Box(
        modifier
            .fillMaxSize()
            .then(
                if (ui.locked) {
                    Modifier.holdToReveal(ui.hold, description, onPress = { pressed = it }, onTap = actions.tap, onHold = actions.hold)
                } else {
                    Modifier
                },
            ),
    ) {
        when {
            ui.paused -> PausedView(ui, now, accent, actions, Modifier.align(Alignment.Center))
            // Blackout: nothing to draw while the backlight is off (no ticker, no frames)
            !ui.blackout || ui.revealed -> CalmFace(ui, now, untilCheckIn, breath, Modifier.fillMaxSize())
        }
        if (!ui.blackout && !ui.paused) {
            HoldHint(ui.hint && !ui.panel, pressed && !ui.panel, ui.hold, Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp))
        }
        if (ui.panel) {
            Panel(ui, accent, actions, Modifier.align(Alignment.BottomCenter).padding(bottom = if (LocalLandscape.current) 24.dp else 120.dp))
        }
        if (ui.park) ParkPad(accent, actions.park, actions.cancelPark)
        ui.end?.let { end ->
            EndSheet(end, ui.focusedMinutes, ui.plannedMinutes, ui.logged, ui.endReasons, ui.suggestions, accent, actions.end)
        }
    }
}

/** The Calm face: a thin arc and the minutes left. Brightens for a peek or the panel; breathes in at Begin. */
@Composable
private fun CalmFace(
    ui: DeepUi,
    now: () -> Now,
    untilCheckIn: () -> kotlin.time.Duration?,
    breath: () -> Float,
    modifier: Modifier = Modifier,
) {
    val chalk = QuietPalettes.Ink // Calm is near-black whatever the theme: chalk and the dark accents
    val accent = chalk.accent(ui.accent)
    val landscape = LocalLandscape.current
    val time by rememberNow(now, ui.tick, ui.timing.anchorMs)
    val progress = ui.timing.minuteProgress(time)
    // The minute tick: the arc eases one minute forward over 600 ms, then holds still
    val arc = remember { Animatable(progress) }
    LaunchedEffect(progress) {
        if (ui.reduceMotion || arc.value > progress) arc.snapTo(progress) else arc.animateTo(progress, tween(600, easing = FastOutSlowInEasing))
    }
    val level = animateFloatAsState(if (ui.revealed) 1f else CALM_ALPHA, tween(if (ui.revealed) 400 else 900), label = "calm")
    val glow = remember { Animatable(0f) }
    if (ui.checkIn != null && !ui.blackout) {
        LaunchedEffect(ui.checkIn, ui.timing) {
            while (true) {
                val wait = untilCheckIn() ?: break
                delay(wait)
                glow.animateTo(1f, tween(1000, easing = LinearEasing))
                glow.animateTo(0f, tween(2000, easing = LinearEasing))
            }
        }
    }
    val minutes = ui.timing.left(time)?.let(Format::minutesLeft)
    val spoken = minutes?.let { pluralStringResource(R.plurals.focus_left_description, it, it) }.orEmpty()
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                // Up from centre: clear of the panel (it shows over the face when held) and far from the hot corner
                .offset(y = if (landscape) (-50).dp else (-80).dp)
                .size(if (landscape) 420.dp else 520.dp)
                .graphicsLayer {
                    val b = breath()
                    val s = 1f - 0.08f * sin(PI.toFloat() * b) // 1.0 → 0.92 → 1.0
                    scaleX = s
                    scaleY = s
                    // Calm's low luminance is this alpha: applied to each draw call (arc, digits), never through an
                    // offscreen layer, which HWUI would re-render on every frame of the breath (atrace, LOG M3)
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    alpha = (level.value + glow.value * (1f - CALM_ALPHA) * 0.6f).coerceAtMost(1f)
                },
            contentAlignment = Alignment.Center,
        ) {
            ThinArc({ arc.value }, accent, chalk.inkFaint, Modifier.fillMaxSize())
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                QuietText(
                    minutes?.let { pluralStringResource(R.plurals.minutes_short, it, it) }.orEmpty(),
                    QuietTheme.type.timerSmall,
                    color = chalk.ink,
                    maxLines = 1,
                    modifier = Modifier.clearAndSetSemantics {
                        contentDescription = spoken
                        liveRegion = LiveRegionMode.Polite
                    },
                )
                if (ui.intention != null) {
                    Spacer(Modifier.height(8.dp))
                    QuietText(ui.intention, QuietTheme.type.body, color = chalk.inkDim, maxLines = 1, align = TextAlign.Center, modifier = Modifier.width(340.dp))
                }
            }
        }
    }
}

/** A hairline track and a thin arc of the time left, ending at twelve o'clock. Progress is read while drawing. */
@Composable
private fun ThinArc(progress: () -> Float, accent: Color, track: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Spacer(
        modifier.drawWithCache {
            val w = 4.dp.toPx()
            val trackStroke = Stroke(w * 0.5f)
            val arcStroke = Stroke(w, cap = StrokeCap.Round)
            val topLeft = Offset(w / 2, w / 2)
            val arcSize = Size(size.width - w, size.height - w)
            onDrawBehind {
                drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = trackStroke)
                val left = 1f - progress().coerceIn(0f, 1f)
                if (left > 0f) drawArc(accent, 270f - 360f * left, 360f * left, useCenter = false, topLeft = topLeft, size = arcSize, style = arcStroke)
            }
        },
    )
}

/**
 * "Hold to open", faint, for 2 s after a tap; while a finger is down, a thin line fills towards the 1.2 s hold.
 * Nothing runs while neither shows.
 */
@Composable
private fun HoldHint(visible: Boolean, pressed: Boolean, hold: kotlin.time.Duration, modifier: Modifier = Modifier) {
    val chalk = QuietPalettes.Ink
    val alpha = animateFloatAsState(if (visible || pressed) 0.35f else 0f, tween(300), label = "hint")
    val fill = remember { Animatable(0f) }
    LaunchedEffect(pressed) {
        if (pressed) {
            fill.snapTo(0f)
            fill.animateTo(1f, tween(hold.inWholeMilliseconds.toInt(), easing = LinearEasing))
        } else {
            fill.snapTo(0f)
        }
    }
    Column(modifier.graphicsLayer { this.alpha = alpha.value }, horizontalAlignment = Alignment.CenterHorizontally) {
        QuietText(stringResource(R.string.hold_to_open), QuietTheme.type.label, color = chalk.ink, maxLines = 1)
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.layout.Spacer(
            Modifier.size(200.dp, 3.dp).drawWithCache {
                onDrawBehind {
                    val f = fill.value
                    if (f > 0f) drawRect(chalk.ink, size = Size(size.width * f, size.height))
                }
            },
        )
    }
}

/**
 * The hold panel's actions, in order. One list on purpose: later items (e.g. "Log a distraction" with reason chips)
 * slot in here without touching the panel's layout.
 */
enum class PanelAction(@param:androidx.annotation.StringRes val label: Int, val width: Int, val primary: Boolean = false) {
    Park(R.string.park_thought, 280),
    Pause(R.string.pause, 200),
    End(R.string.end, 200, primary = true),
    ;

    companion object {
        val Shown: List<PanelAction> = entries
    }
}

private fun DeepActions.run(action: PanelAction) = when (action) {
    PanelAction.Park -> openPark()
    PanelAction.Pause -> pause()
    PanelAction.End -> askEnd()
}

/**
 * The panel: "Pulled away by…" reason chips (one tap logs a distraction, DESIGN-history §5.1), then
 * [PanelAction.Shown]. Lit (the display lifts while it shows); every action is a 160 px target.
 */
@Composable
private fun Panel(ui: DeepUi, accent: Color, actions: DeepActions, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    Column(
        modifier
            .padding(horizontal = 24.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(colors.surface)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (ui.reasons.isNotEmpty()) {
            val logged = ui.reasons.filter { it.id in ui.logging }.joinToString(", ") { it.label }
            Row(Modifier.width(if (LocalLandscape.current) 1100.dp else 680.dp), verticalAlignment = Alignment.CenterVertically) {
                QuietText(
                    if (logged.isEmpty()) stringResource(R.string.pulled_away_by) else stringResource(R.string.logged_reasons, logged),
                    QuietTheme.type.label,
                    color = if (logged.isEmpty()) colors.inkDim else colors.ink,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (ui.logged > 0) QuietText(pluralStringResource(R.plurals.distractions_logged, ui.logged, ui.logged), QuietTheme.type.caption, color = colors.inkDim, maxLines = 1)
            }
            Spacer(Modifier.height(14.dp))
            ReasonChips(
                ui.reasons,
                ui.logging,
                actions.logReason,
                Modifier.width(if (LocalLandscape.current) 1100.dp else 680.dp),
                limit = if (ui.moreReasons) Int.MAX_VALUE else PANEL_REASONS,
                onMore = actions.moreReasons,
            )
            Spacer(Modifier.height(20.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            PanelAction.Shown.forEach { action ->
                QuietButton(
                    stringResource(action.label),
                    { actions.run(action) },
                    fill = if (action.primary) accent else null,
                    modifier = Modifier.width(action.width.dp).height(QuietTheme.BigTarget),
                )
            }
        }
    }
}

/** Reasons shown in the panel before "More" (Hick: a short list is quicker to choose from). */
const val PANEL_REASONS: Int = 6

/** Paused (the display is normal again): the time left, Resume, End; after the cap, the offer to end. */
@Composable
private fun PausedView(ui: DeepUi, now: () -> Now, accent: Color, actions: DeepActions, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val left = remember(ui.timing) { ui.timing.left(now()) } // paused: the time left doesn't move
    val minutes = left?.let(Format::minutesLeft)
    Column(modifier.padding(horizontal = QuietTheme.spacing.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
        QuietText(stringResource(R.string.paused), QuietTheme.type.label, color = accent, maxLines = 1)
        Spacer(Modifier.height(QuietTheme.spacing.s))
        if (minutes != null) {
            QuietText(pluralStringResource(R.plurals.minutes_left, minutes, minutes), QuietTheme.type.timerSmall, maxLines = 1)
        }
        if (ui.intention != null) {
            QuietText(ui.intention, QuietTheme.type.title, color = colors.inkDim, maxLines = 1, align = TextAlign.Center)
        }
        Spacer(Modifier.height(QuietTheme.spacing.l))
        if (ui.overCap) {
            QuietText(stringResource(R.string.pause_long), QuietTheme.type.body, maxLines = 2, align = TextAlign.Center)
            Spacer(Modifier.height(QuietTheme.spacing.m))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            QuietButton(stringResource(R.string.resume), actions.resume, fill = if (ui.overCap) null else accent, big = true, modifier = Modifier.width(320.dp))
            QuietButton(stringResource(R.string.end), actions.askEnd, fill = if (ui.overCap) accent else null, big = true, modifier = Modifier.width(320.dp))
        }
    }
}

/** Calm's near-black, for the root background (Blackout is pure black). */
val CalmBackground: Color = Color(0xFF07080A)

/** Kept for previews and tests that draw the face without the root. */
@Composable
fun DeepBackdrop(blackout: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().background(if (blackout) Color.Black else CalmBackground)) { content() }
}
