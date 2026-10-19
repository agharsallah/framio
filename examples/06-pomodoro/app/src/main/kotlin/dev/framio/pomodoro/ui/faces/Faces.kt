package dev.framio.pomodoro.ui.faces

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import dev.framio.pomodoro.core.model.Face
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * The timer faces of PLAN §5 (principle 12: time you can see from across the room). Each is stateless and reads
 * `progress` (0 at the start, 1 at the end) only while drawing: a tick redraws the face and recomposes nothing. Shapes
 * that don't move (tick marks, strokes) are built once per size in drawWithCache. Two to four draw calls each.
 */

/** The face of [face] for [progress]; [Face.Digits] draws nothing (its numbers are the face). */
@Composable
fun TimerFace(face: Face, progress: () -> Float, accent: Color, track: Color, modifier: Modifier = Modifier) {
    when (face) {
        Face.Ring -> RingFace(progress, accent, track, modifier)
        Face.Disc -> DiscFace(progress, accent, track, modifier)
        Face.Horizon -> HorizonFace(progress, accent, track, modifier)
        Face.Digits -> Unit
    }
}

/** Whether the face's digits sit inside it (Ring) or beside it (Disc, Horizon); Digits is all digits. */
val Face.digitsInside: Boolean get() = this == Face.Ring

/**
 * **Disc** (Time Timer style): the time left is a filled wedge that ends at twelve o'clock and shrinks clockwise as
 * time passes, over a faint full disc with twelve tick marks.
 */
@Composable
fun DiscFace(progress: () -> Float, accent: Color, track: Color, modifier: Modifier = Modifier) {
    Spacer(
        modifier.drawWithCache {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            val inner = r * 0.9f
            val ticks = Path().apply {
                for (i in 0 until 12) {
                    val a = (i * 30.0 - 90.0) * PI / 180.0
                    val from = if (i % 3 == 0) r * 0.9f else r * 0.94f
                    moveTo(c.x + from * cos(a).toFloat(), c.y + from * sin(a).toFloat())
                    lineTo(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
                }
            }
            val tickStroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
            val topLeft = Offset(c.x - inner, c.y - inner)
            val wedgeSize = Size(inner * 2, inner * 2)
            val hub = r * 0.05f
            onDrawBehind {
                drawCircle(track, inner, c)
                drawPath(ticks, track, style = tickStroke)
                val left = 1f - progress().coerceIn(0f, 1f)
                if (left > 0f) {
                    val sweep = 360f * left
                    drawArc(accent, 270f - sweep, sweep, useCenter = true, topLeft = topLeft, size = wedgeSize)
                }
                drawCircle(accent, hub, c)
            }
        },
    )
}

/**
 * **Horizon**: a sun sinks from high up towards a horizon line and is half set when the time is up; below the line,
 * nothing of it shows, only a few still strokes of water. A faint ring marks where it started.
 */
@Composable
fun HorizonFace(progress: () -> Float, accent: Color, track: Color, modifier: Modifier = Modifier) {
    Spacer(
        modifier.drawWithCache {
            val w = size.width
            val line = size.height * 0.7f
            val sun = size.minDimension * 0.19f
            val top = sun * 1.05f
            val lineStroke = 4.dp.toPx()
            val startRing = Stroke(2.dp.toPx())
            val x = w / 2f
            // The water below the line: three short strokes, narrower further down (static, one path)
            val water = Path().apply {
                for (i in 1..3) {
                    val y = line + (size.height - line) * i / 4f
                    val half = w * (0.36f - 0.09f * i)
                    moveTo(x - half, y)
                    lineTo(x + half, y)
                }
            }
            val waterStroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
            onDrawBehind {
                drawCircle(track, sun, Offset(x, top), style = startRing)
                val y = top + (line - top) * progress().coerceIn(0f, 1f)
                clipRect(bottom = line) { drawCircle(accent, sun, Offset(x, y)) }
                drawLine(track, Offset(0f, line), Offset(w, line), strokeWidth = lineStroke, cap = StrokeCap.Round)
                drawPath(water, track, style = waterStroke)
            }
        },
    )
}
