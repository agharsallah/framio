package dev.framio.pomodoro.ui.notes

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.framio.pomodoro.core.notes.TagIcon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A reason tag's glyph, drawn (no icon font, no Material): simple strokes on a unit square, built once per size.
 * Decoration beside a label, so it has no semantics of its own. [cut] is the colour behind it (the moon's bite).
 */
@Composable
fun TagGlyph(icon: TagIcon, color: Color, cut: Color, modifier: Modifier = Modifier) {
    Spacer(
        modifier.drawWithCache {
            val s = size.minDimension
            val stroke = Stroke(s * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun p(x: Float, y: Float) = Offset(x * s, y * s)
            val path = Path()
            onDrawBehind { glyph(icon, color, cut, s, stroke, path, ::p) }
        },
    )
}

private fun DrawScope.glyph(icon: TagIcon, c: Color, cut: Color, s: Float, st: Stroke, path: Path, p: (Float, Float) -> Offset) {
    val w = st.width
    when (icon) {
        TagIcon.Phone -> {
            drawRoundRect(c, p(0.3f, 0.1f), Size(0.4f * s, 0.8f * s), CornerRadius(0.08f * s), style = st)
            drawLine(c, p(0.44f, 0.76f), p(0.56f, 0.76f), w, StrokeCap.Round)
        }
        TagIcon.Person -> {
            drawCircle(c, 0.15f * s, p(0.5f, 0.3f), style = st)
            drawArc(c, 180f, 180f, false, p(0.2f, 0.58f), Size(0.6f * s, 0.6f * s), style = st)
        }
        TagIcon.Noise -> {
            drawCircle(c, 0.07f * s, p(0.24f, 0.5f))
            for (r in listOf(0.2f, 0.34f, 0.48f)) drawArc(c, -40f, 80f, false, p(0.24f - r, 0.5f - r), Size(2 * r * s, 2 * r * s), style = st)
        }
        TagIcon.Cup -> {
            path.reset()
            path.moveTo(0.2f * s, 0.35f * s)
            path.lineTo(0.26f * s, 0.82f * s)
            path.lineTo(0.62f * s, 0.82f * s)
            path.lineTo(0.68f * s, 0.35f * s)
            path.close()
            drawPath(path, c, style = st)
            drawArc(c, -90f, 180f, false, p(0.58f, 0.42f), Size(0.24f * s, 0.24f * s), style = st)
            drawLine(c, p(0.38f, 0.12f), p(0.38f, 0.24f), w, StrokeCap.Round)
            drawLine(c, p(0.5f, 0.12f), p(0.5f, 0.24f), w, StrokeCap.Round)
        }
        TagIcon.Moon -> {
            drawCircle(c, 0.34f * s, p(0.48f, 0.52f))
            drawCircle(cut, 0.3f * s, p(0.64f, 0.38f))
        }
        TagIcon.Cloud -> {
            drawCircle(c, 0.16f * s, p(0.34f, 0.58f))
            drawCircle(c, 0.21f * s, p(0.54f, 0.46f))
            drawCircle(c, 0.15f * s, p(0.72f, 0.6f))
            drawRect(c, p(0.34f, 0.58f), Size(0.38f * s, 0.17f * s))
        }
        TagIcon.Bolt -> {
            path.reset()
            listOf(0.58f to 0.06f, 0.24f to 0.56f, 0.46f to 0.56f, 0.4f to 0.94f, 0.76f to 0.42f, 0.54f to 0.42f).forEachIndexed { i, (x, y) ->
                if (i == 0) path.moveTo(x * s, y * s) else path.lineTo(x * s, y * s)
            }
            path.close()
            drawPath(path, c)
        }
        TagIcon.Wrench -> {
            drawLine(c, p(0.22f, 0.8f), p(0.56f, 0.46f), w * 1.4f, StrokeCap.Round)
            drawArc(c, 100f, 290f, false, p(0.5f, 0.14f), Size(0.36f * s, 0.36f * s), style = st)
        }
        TagIcon.Dots -> for (x in listOf(0.22f, 0.5f, 0.78f)) drawCircle(c, 0.08f * s, p(x, 0.5f))
        TagIcon.Check -> {
            drawLine(c, p(0.18f, 0.52f), p(0.4f, 0.74f), w, StrokeCap.Round)
            drawLine(c, p(0.4f, 0.74f), p(0.82f, 0.28f), w, StrokeCap.Round)
        }
        TagIcon.Door -> {
            drawRoundRect(c, p(0.26f, 0.1f), Size(0.48f * s, 0.8f * s), CornerRadius(0.04f * s), style = st)
            drawCircle(c, 0.05f * s, p(0.62f, 0.52f))
        }
        TagIcon.Star -> {
            path.reset()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 0.42f else 0.18f
                val a = -PI / 2 + i * PI / 5
                val x = (0.5f + r * cos(a).toFloat()) * s
                val y = (0.54f + r * sin(a).toFloat()) * s
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, c)
        }
        TagIcon.Book -> {
            drawRect(c, p(0.12f, 0.22f), Size(0.36f * s, 0.58f * s), style = st)
            drawRect(c, p(0.52f, 0.22f), Size(0.36f * s, 0.58f * s), style = st)
        }
        TagIcon.Leaf -> {
            path.reset()
            path.moveTo(0.2f * s, 0.8f * s)
            path.quadraticTo(0.2f * s, 0.2f * s, 0.82f * s, 0.18f * s)
            path.quadraticTo(0.8f * s, 0.8f * s, 0.2f * s, 0.8f * s)
            drawPath(path, c, style = st)
            drawLine(c, p(0.2f, 0.8f), p(0.6f, 0.4f), w, StrokeCap.Round)
        }
        TagIcon.Heart -> {
            path.reset()
            path.moveTo(0.5f * s, 0.84f * s)
            path.cubicTo(0.0f * s, 0.5f * s, 0.18f * s, 0.06f * s, 0.5f * s, 0.32f * s)
            path.cubicTo(0.82f * s, 0.06f * s, 1.0f * s, 0.5f * s, 0.5f * s, 0.84f * s)
            drawPath(path, c)
        }
        TagIcon.Pin -> {
            drawCircle(c, 0.16f * s, p(0.5f, 0.3f))
            drawLine(c, p(0.5f, 0.42f), p(0.5f, 0.9f), w, StrokeCap.Round)
        }
    }
}
