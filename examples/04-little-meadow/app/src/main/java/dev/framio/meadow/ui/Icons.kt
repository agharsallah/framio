package dev.framio.meadow.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Icons drawn with thick rounded strokes, like the rest of the meadow. No text needed to read them. */
enum class Icon { Muted, Back, Home, Replay, Speaker, Play, Pause, Check, Star, Lock, Gear, Close, Next, Prev, Mail, Moon, Sun, Eraser, Brush, Trash, Heart, Music, Book, Plus, Minus }

/** Draws [icon] centred in the current DrawScope, using the middle 50 %. */
fun DrawScope.drawIcon(icon: Icon, color: Color) {
    val s = size.minDimension * 0.5f
    val ox = (size.width - s) / 2
    val oy = (size.height - s) / 2
    fun p(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)
    val w = s * 0.13f
    val stroke = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun path(vararg pts: Pair<Float, Float>, close: Boolean = false) = Path().apply {
        pts.forEachIndexed { i, (x, y) -> val o = p(x, y); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
        if (close) close()
    }
    when (icon) {
        Icon.Back -> drawPath(path(0.62f to 0.12f, 0.25f to 0.5f, 0.62f to 0.88f), color, style = stroke)
        Icon.Prev -> drawPath(path(0.62f to 0.12f, 0.25f to 0.5f, 0.62f to 0.88f), color, style = stroke)
        Icon.Next -> drawPath(path(0.38f to 0.12f, 0.75f to 0.5f, 0.38f to 0.88f), color, style = stroke)
        Icon.Home -> {
            drawPath(path(0.08f to 0.5f, 0.5f to 0.12f, 0.92f to 0.5f), color, style = stroke)
            drawPath(path(0.2f to 0.45f, 0.2f to 0.9f, 0.8f to 0.9f, 0.8f to 0.45f), color, style = stroke)
            drawRoundRect(color, p(0.41f, 0.62f), Size(0.18f * s, 0.28f * s), CornerRadius(w / 2))
        }
        Icon.Replay -> {
            drawArc(color, -60f, 290f, false, p(0.12f, 0.12f), Size(0.76f * s, 0.76f * s), style = stroke)
            val tip = p(0.5f + 0.38f * cos(-60.0 * PI / 180).toFloat(), 0.5f + 0.38f * sin(-60.0 * PI / 180).toFloat())
            drawPath(Path().apply { moveTo(tip.x - 0.2f * s, tip.y - 0.12f * s); lineTo(tip.x + 0.06f * s, tip.y + 0.02f * s); lineTo(tip.x - 0.12f * s, tip.y + 0.2f * s) }, color, style = stroke)
        }
        Icon.Speaker -> {
            drawPath(path(0.1f to 0.38f, 0.28f to 0.38f, 0.5f to 0.18f, 0.5f to 0.82f, 0.28f to 0.62f, 0.1f to 0.62f, close = true), color, style = Fill)
            drawArc(color, -50f, 100f, false, p(0.4f, 0.3f), Size(0.4f * s, 0.4f * s), style = stroke)
            drawArc(color, -50f, 100f, false, p(0.35f, 0.1f), Size(0.7f * s, 0.8f * s), style = stroke)
        }
        Icon.Muted -> {
            drawPath(path(0.06f to 0.38f, 0.24f to 0.38f, 0.46f to 0.18f, 0.46f to 0.82f, 0.24f to 0.62f, 0.06f to 0.62f, close = true), color, style = Fill)
            drawLine(color, p(0.62f, 0.36f), p(0.9f, 0.64f), w, StrokeCap.Round)
            drawLine(color, p(0.9f, 0.36f), p(0.62f, 0.64f), w, StrokeCap.Round)
        }
        Icon.Play -> drawPath(path(0.28f to 0.14f, 0.85f to 0.5f, 0.28f to 0.86f, close = true), color, style = Fill)
        Icon.Pause -> {
            drawRoundRect(color, p(0.2f, 0.15f), Size(0.2f * s, 0.7f * s), CornerRadius(w))
            drawRoundRect(color, p(0.6f, 0.15f), Size(0.2f * s, 0.7f * s), CornerRadius(w))
        }
        Icon.Check -> drawPath(path(0.12f to 0.52f, 0.4f to 0.8f, 0.9f to 0.22f), color, style = stroke)
        Icon.Close -> {
            drawLine(color, p(0.18f, 0.18f), p(0.82f, 0.82f), w, StrokeCap.Round)
            drawLine(color, p(0.82f, 0.18f), p(0.18f, 0.82f), w, StrokeCap.Round)
        }
        Icon.Plus -> {
            drawLine(color, p(0.5f, 0.15f), p(0.5f, 0.85f), w, StrokeCap.Round)
            drawLine(color, p(0.15f, 0.5f), p(0.85f, 0.5f), w, StrokeCap.Round)
        }
        Icon.Minus -> drawLine(color, p(0.15f, 0.5f), p(0.85f, 0.5f), w, StrokeCap.Round)
        Icon.Star -> drawPath(starPath(p(0.5f, 0.53f), 0.5f * s, 0.22f * s), color, style = Fill)
        Icon.Heart -> drawPath(Path().apply {
            val a = p(0.5f, 0.9f)
            moveTo(a.x, a.y)
            cubicTo(p(0.05f, 0.55f).x, p(0.05f, 0.55f).y, p(0.1f, 0.05f).x, p(0.1f, 0.05f).y, p(0.5f, 0.3f).x, p(0.5f, 0.3f).y)
            cubicTo(p(0.9f, 0.05f).x, p(0.9f, 0.05f).y, p(0.95f, 0.55f).x, p(0.95f, 0.55f).y, a.x, a.y)
            close()
        }, color, style = Fill)
        Icon.Lock -> {
            drawArc(color, 180f, 180f, false, p(0.26f, 0.1f), Size(0.48f * s, 0.5f * s), style = stroke)
            drawLine(color, p(0.26f, 0.35f), p(0.26f, 0.45f), w, StrokeCap.Round)
            drawLine(color, p(0.74f, 0.35f), p(0.74f, 0.45f), w, StrokeCap.Round)
            drawRoundRect(color, p(0.14f, 0.42f), Size(0.72f * s, 0.5f * s), CornerRadius(w * 1.2f))
        }
        Icon.Gear -> {
            for (i in 0 until 8) {
                val a = i * PI / 4
                drawLine(color, p(0.5f + 0.28f * cos(a).toFloat(), 0.5f + 0.28f * sin(a).toFloat()), p(0.5f + 0.44f * cos(a).toFloat(), 0.5f + 0.44f * sin(a).toFloat()), w * 1.3f, StrokeCap.Round)
            }
            drawCircle(color, 0.3f * s, p(0.5f, 0.5f), style = Stroke(w))
        }
        Icon.Mail -> {
            drawRoundRect(color, p(0.06f, 0.2f), Size(0.88f * s, 0.62f * s), CornerRadius(w), style = stroke)
            drawPath(path(0.1f to 0.26f, 0.5f to 0.56f, 0.9f to 0.26f), color, style = stroke)
        }
        Icon.Moon -> drawPath(Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(p(0.1f, 0.1f), Size(0.8f * s, 0.8f * s)))
        }.let { full ->
            Path().apply { op(full, Path().apply { addOval(androidx.compose.ui.geometry.Rect(p(0.32f, 0.0f), Size(0.72f * s, 0.72f * s))) }, androidx.compose.ui.graphics.PathOperation.Difference) }
        }, color, style = Fill)
        Icon.Sun -> {
            drawCircle(color, 0.22f * s, p(0.5f, 0.5f))
            for (i in 0 until 8) {
                val a = i * PI / 4
                drawLine(color, p(0.5f + 0.34f * cos(a).toFloat(), 0.5f + 0.34f * sin(a).toFloat()), p(0.5f + 0.46f * cos(a).toFloat(), 0.5f + 0.46f * sin(a).toFloat()), w, StrokeCap.Round)
            }
        }
        Icon.Eraser -> {
            drawRoundRect(color, p(0.15f, 0.3f), Size(0.7f * s, 0.4f * s), CornerRadius(w), style = stroke)
            drawLine(color, p(0.45f, 0.3f), p(0.45f, 0.7f), w, StrokeCap.Round)
        }
        Icon.Brush -> {
            drawLine(color, p(0.8f, 0.15f), p(0.42f, 0.55f), w, StrokeCap.Round)
            drawCircle(color, 0.17f * s, p(0.3f, 0.7f))
        }
        Icon.Trash -> {
            drawLine(color, p(0.15f, 0.25f), p(0.85f, 0.25f), w, StrokeCap.Round)
            drawPath(path(0.25f to 0.3f, 0.3f to 0.9f, 0.7f to 0.9f, 0.75f to 0.3f), color, style = stroke)
            drawLine(color, p(0.4f, 0.12f), p(0.6f, 0.12f), w, StrokeCap.Round)
        }
        Icon.Music -> {
            drawLine(color, p(0.38f, 0.75f), p(0.38f, 0.15f), w, StrokeCap.Round)
            drawLine(color, p(0.38f, 0.15f), p(0.8f, 0.25f), w, StrokeCap.Round)
            drawLine(color, p(0.8f, 0.25f), p(0.8f, 0.65f), w, StrokeCap.Round)
            drawCircle(color, 0.13f * s, p(0.28f, 0.78f))
            drawCircle(color, 0.13f * s, p(0.7f, 0.68f))
        }
        Icon.Book -> {
            drawPath(path(0.5f to 0.25f, 0.1f to 0.15f, 0.1f to 0.8f, 0.5f to 0.9f, 0.9f to 0.8f, 0.9f to 0.15f, 0.5f to 0.25f, 0.5f to 0.9f), color, style = stroke)
        }
    }
}

fun starPath(c: Offset, outer: Float, inner: Float, points: Int = 5): Path = Path().apply {
    for (i in 0 until points * 2) {
        val r = if (i % 2 == 0) outer else inner
        val a = -PI / 2 + i * PI / points
        val x = c.x + r * cos(a).toFloat()
        val y = c.y + r * sin(a).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}
