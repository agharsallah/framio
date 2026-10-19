package dev.framio.pomodoro.ui.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.framio.pomodoro.R
import dev.framio.pomodoro.ui.components.QuietButton
import dev.framio.pomodoro.ui.components.QuietText
import dev.framio.pomodoro.ui.theme.QuietTheme
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The arithmetic of a duration dial (pure, unit-tested): one turn is [MINUTES_PER_TURN] minutes and you drag around it
 * like a kitchen timer, several turns for long blocks. Drags are relative (wherever the finger lands, turning
 * clockwise adds time), so a coarse finger never jumps the value. Values snap to whole minutes below [FINE_BELOW] and
 * to fives from there (25, 50: the usual lengths sit on the snap).
 */
object DialMath {
    const val MINUTES_PER_TURN = 60f
    const val FINE_BELOW = 15

    /** Degrees clockwise from 12 o'clock of [p] around [center] (screen coordinates, y down), in 0..360. */
    fun angle(p: Offset, center: Offset): Float {
        val deg = Math.toDegrees(atan2((p.x - center.x).toDouble(), (center.y - p.y).toDouble())).toFloat()
        return if (deg < 0) deg + 360f else deg
    }

    /** The shortest turn from [from] to [to], in -180..180 (crossing 12 o'clock is not a jump of a whole turn). */
    fun turn(from: Float, to: Float): Float {
        var d = to - from
        while (d > 180f) d -= 360f
        while (d <= -180f) d += 360f
        return d
    }

    fun minutesFor(degrees: Float): Float = degrees / 360f * MINUTES_PER_TURN

    /** [raw] minutes snapped (1s below [FINE_BELOW], 5s above) and kept in [range]. */
    fun snap(raw: Float, range: IntRange): Int {
        val fine = raw.roundToInt()
        val v = if (fine < FINE_BELOW) fine else (raw / 5f).roundToInt() * 5
        return v.coerceIn(range)
    }

    /** One step of the − / + buttons from [value]: 1 below [FINE_BELOW], else to the next multiple of 5. */
    fun step(value: Int, up: Boolean, range: IntRange): Int {
        val next = when {
            up && value < FINE_BELOW -> value + 1
            up -> (value / 5 + 1) * 5
            value <= FINE_BELOW -> value - 1
            value % 5 != 0 -> value / 5 * 5
            else -> value - 5
        }
        return next.coerceIn(range)
    }

    /** Where the knob sits for [minutes]: degrees from 12 o'clock. */
    fun knobDegrees(minutes: Int): Float = (minutes % MINUTES_PER_TURN.toInt()) / MINUTES_PER_TURN * 360f
}

/**
 * A duration picker without a keyboard (PLAN §5.1, after the Hub's timer dial): drag around the dial to set
 * [minutes] in [range]; it snaps per [DialMath.snap] and reports each new value through [onChange] while dragging
 * (the mode is saved as you go). Big − and + buttons below take one step for fine work. The ring shows the minutes
 * within the hour; whole hours show as a number of dots. Static unless dragged: drawn from cached geometry.
 */
@Composable
fun DurationDial(
    label: String,
    minutes: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    enabled: Boolean = true,
    format: @Composable (Int) -> String = { formatMinutes(it) },
) {
    val colors = QuietTheme.colors
    val change by rememberUpdatedState(onChange)
    val current by rememberUpdatedState(minutes)
    var dragging by remember { mutableIntStateOf(-1) }
    val shown = if (dragging >= 0) dragging else minutes
    val valueText = format(shown)
    Column(modifier.width(size + 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        QuietText(label, QuietTheme.type.label, color = colors.inkDim, maxLines = 1)
        Box(
            Modifier
                .size(size)
                .semantics {
                    contentDescription = label
                    stateDescription = valueText
                    testTag = "dial-$label"
                    progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), range.first.toFloat()..range.last.toFloat(), range.last - range.first)
                    if (enabled) {
                        setProgress { v ->
                            change(v.roundToInt().coerceIn(range))
                            true
                        }
                    }
                }
                .then(if (enabled) Modifier.dialDrag(range, { current }, { dragging = it; change(it) }, { dragging = -1 }) else Modifier)
                .drawWithCache {
                    val stroke = 22.dp.toPx()
                    val r = (this.size.minDimension - stroke) / 2f - 6.dp.toPx()
                    val c = Offset(this.size.width / 2f, this.size.height / 2f)
                    val topLeft = Offset(c.x - r, c.y - r)
                    val arcSize = Size(r * 2, r * 2)
                    val sweep = DialMath.knobDegrees(shown).let { if (it == 0f && shown > 0) 360f else it }
                    val knobRad = (sweep - 90f) * PI.toFloat() / 180f
                    val knob = Offset(c.x + r * cos(knobRad), c.y + r * sin(knobRad))
                    val hours = shown / DialMath.MINUTES_PER_TURN.toInt()
                    onDrawBehind {
                        drawCircle(colors.inkFaint, r, c, style = Stroke(stroke))
                        // Five-minute ticks inside the ring
                        for (i in 0 until 12) {
                            val a = i * 30f * PI.toFloat() / 180f
                            val inner = r - stroke / 2f - (if (i % 3 == 0) 18.dp.toPx() else 10.dp.toPx())
                            val outer = r - stroke / 2f - 4.dp.toPx()
                            drawLine(colors.line, Offset(c.x + inner * sin(a), c.y - inner * cos(a)), Offset(c.x + outer * sin(a), c.y - outer * cos(a)), 3.dp.toPx())
                        }
                        if (enabled) drawArc(accent, -90f, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                        drawCircle(if (enabled) accent else colors.inkDim, stroke * 0.9f, knob)
                        drawCircle(colors.background, stroke * 0.38f, knob)
                        for (h in 0 until hours.coerceAtMost(4)) {
                            drawCircle(accent, 5.dp.toPx(), Offset(c.x - (hours.coerceAtMost(4) - 1) * 9.dp.toPx() + h * 18.dp.toPx(), c.y + r * 0.42f))
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            QuietText(valueText, QuietTheme.type.title, maxLines = 1, align = TextAlign.Center)
        }
        if (enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                QuietButton(stringResource(R.string.less), { change(DialMath.step(minutes, up = false, range)) }, Modifier.width(96.dp), enabled = minutes > range.first)
                QuietButton(stringResource(R.string.more), { change(DialMath.step(minutes, up = true, range)) }, Modifier.width(96.dp), enabled = minutes < range.last)
            }
        }
    }
}

/**
 * The drag: every pointer move adds its turn around the centre (in degrees → minutes) to a running total started from
 * the value under the finger; each snapped value that differs is reported, the release ends the drag.
 */
private fun Modifier.dialDrag(range: IntRange, start: () -> Int, onValue: (Int) -> Unit, onEnd: () -> Unit): Modifier =
    pointerInput(range) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val center = Offset(size.width / 2f, size.height / 2f)
            var last = DialMath.angle(down.position, center)
            var raw = start().toFloat()
            var reported = start()
            down.consume()
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                if (change.positionChange() != Offset.Zero) {
                    val a = DialMath.angle(change.position, center)
                    raw = (raw + DialMath.minutesFor(DialMath.turn(last, a))).coerceIn(range.first.toFloat(), range.last.toFloat())
                    last = a
                    val snapped = DialMath.snap(raw, range)
                    if (snapped != reported) {
                        reported = snapped
                        onValue(snapped)
                    }
                    change.consume()
                }
            }
            onEnd()
        }
    }

/** "25 min", "1 h 30". */
@Composable
fun formatMinutes(minutes: Int): String = when {
    minutes < 60 -> stringResource(R.string.dial_minutes, minutes)
    minutes % 60 == 0 -> stringResource(R.string.dial_hours, minutes / 60)
    else -> stringResource(R.string.dial_hours_minutes, minutes / 60, minutes % 60)
}
