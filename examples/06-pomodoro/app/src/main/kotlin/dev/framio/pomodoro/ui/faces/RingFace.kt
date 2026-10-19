package dev.framio.pomodoro.ui.faces

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The **Ring** face: the time left is an arc that ends at twelve o'clock; its leading edge sweeps clockwise towards
 * twelve as time passes. [progress] (0 at the start, 1 at the end) is read only while drawing, so a tick redraws the
 * ring and never recomposes anything. The strokes are built once per size (drawWithCache).
 */
@Composable
fun RingFace(
    progress: () -> Float,
    accent: Color,
    track: Color,
    modifier: Modifier = Modifier,
    width: Dp = 18.dp,
) {
    Spacer(
        modifier.drawWithCache {
            val w = width.toPx()
            val trackStroke = Stroke(w * 0.5f)
            val arcStroke = Stroke(w, cap = StrokeCap.Round)
            val topLeft = Offset(w / 2, w / 2)
            val arcSize = Size(size.width - w, size.height - w)
            onDrawBehind {
                drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = trackStroke)
                val left = 1f - progress().coerceIn(0f, 1f)
                if (left > 0f) {
                    val sweep = 360f * left
                    drawArc(accent, 270f - sweep, sweep, useCenter = false, topLeft = topLeft, size = arcSize, style = arcStroke)
                }
            }
        },
    )
}

/** A small ring for "3 / 6 today": filled by [fraction] (goal gradient, PLAN §3 principle 10). Static. */
@Composable
fun TargetRing(fraction: Float, accent: Color, track: Color, modifier: Modifier = Modifier) {
    Spacer(
        modifier.drawWithCache {
            val w = size.minDimension * 0.14f
            val stroke = Stroke(w, cap = StrokeCap.Round)
            val thin = Stroke(w * 0.6f)
            val topLeft = Offset(w / 2, w / 2)
            val arcSize = Size(size.width - w, size.height - w)
            onDrawBehind {
                drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = thin)
                if (fraction > 0f) drawArc(accent, -90f, 360f * fraction.coerceIn(0f, 1f), useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
            }
        },
    )
}

/** A thin bar for a break's progress; [progress] is read while drawing only. */
@Composable
fun ProgressLine(progress: () -> Float, color: Color, track: Color, modifier: Modifier = Modifier) {
    Spacer(
        modifier.drawWithCache {
            val h = size.height
            onDrawBehind {
                drawLine(track, Offset(h / 2, h / 2), Offset(size.width - h / 2, h / 2), strokeWidth = h, cap = StrokeCap.Round)
                val left = 1f - progress().coerceIn(0f, 1f)
                if (left > 0f) {
                    drawLine(color, Offset(h / 2, h / 2), Offset(h / 2 + (size.width - h) * left, h / 2), strokeWidth = h, cap = StrokeCap.Round)
                }
            }
        },
    )
}
