package dev.framio.hub.ui.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.framio.hub.core.feeds.WeatherKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Line icons drawn on a 24-unit grid and scaled: no icon font, no bitmaps, crisp at any size. */
enum class Glyph {
    Today, Week, Timer, Board, Routines, Settings, Exit, Plus, Minus, Play, Pause, Stop, Check, Close, Trash, Pin, Pen, Eraser,
    Sun, Moon, Cloud, SunCloud, Rain, Snow, Thunder, Fog, Phone, Globe, Star, Back
}

@Composable
fun Icon(glyph: Glyph, color: Color, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Canvas(modifier.size(size)) { drawGlyph(glyph, color) }
}

fun glyphFor(kind: WeatherKind, isDay: Boolean): Glyph = when (kind) {
    WeatherKind.Clear -> if (isDay) Glyph.Sun else Glyph.Moon
    WeatherKind.PartlyCloudy -> if (isDay) Glyph.SunCloud else Glyph.Cloud
    WeatherKind.Cloudy -> Glyph.Cloud
    WeatherKind.Fog -> Glyph.Fog
    WeatherKind.Drizzle, WeatherKind.Rain, WeatherKind.Showers -> Glyph.Rain
    WeatherKind.Snow -> Glyph.Snow
    WeatherKind.Thunder -> Glyph.Thunder
}

fun DrawScope.drawGlyph(glyph: Glyph, color: Color) {
    val u = size.minDimension / 24f
    val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, p(x1, y1), p(x2, y2), stroke.width, StrokeCap.Round)
    fun circle(cx: Float, cy: Float, r: Float, fill: Boolean = false) =
        if (fill) drawCircle(color, r * u, p(cx, cy)) else drawCircle(color, r * u, p(cx, cy), style = stroke)
    fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 2f) =
        drawRoundRect(color, p(x, y), Size(w * u, h * u), androidx.compose.ui.geometry.CornerRadius(r * u), style = stroke)
    fun path(vararg pts: Float, close: Boolean = false) {
        val path = Path().apply {
            moveTo(pts[0] * u, pts[1] * u)
            for (i in 2 until pts.size step 2) lineTo(pts[i] * u, pts[i + 1] * u)
            if (close) close()
        }
        drawPath(path, color, style = stroke)
    }
    fun cloud(dy: Float = 0f) {
        val path = Path().apply {
            moveTo(6f * u, (17f + dy) * u)
            cubicTo(2.5f * u, (17f + dy) * u, 2.5f * u, (11.5f + dy) * u, 6.5f * u, (11.5f + dy) * u)
            cubicTo(7f * u, (7f + dy) * u, 13.5f * u, (6f + dy) * u, 15f * u, (10f + dy) * u)
            cubicTo(19.5f * u, (9.5f + dy) * u, 21.5f * u, (17f + dy) * u, 17f * u, (17f + dy) * u)
            close()
        }
        drawPath(path, color, style = stroke)
    }
    fun rays(cx: Float, cy: Float, r1: Float, r2: Float, n: Int = 8) {
        for (i in 0 until n) {
            val a = 2 * PI * i / n
            line(cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(), cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat())
        }
    }

    when (glyph) {
        Glyph.Today -> {
            rect(3f, 5f, 18f, 16f)
            line(3f, 10f, 21f, 10f)
            line(8f, 3f, 8f, 7f)
            line(16f, 3f, 16f, 7f)
            circle(12f, 15.5f, 1.6f, fill = true)
        }
        Glyph.Week -> {
            rect(3f, 5f, 18f, 16f)
            line(3f, 10f, 21f, 10f)
            for (x in listOf(7.5f, 12f, 16.5f)) line(x, 13f, x, 18f)
        }
        Glyph.Timer -> {
            circle(12f, 13f, 8f)
            line(12f, 13f, 12f, 8.5f)
            line(10f, 2.5f, 14f, 2.5f)
            line(12f, 2.5f, 12f, 5f)
        }
        Glyph.Board -> {
            path(5f, 3f, 15f, 3f, 19f, 7f, 19f, 21f, 5f, 21f, close = true)
            line(8.5f, 10f, 15.5f, 10f)
            line(8.5f, 14f, 15.5f, 14f)
            line(8.5f, 18f, 12.5f, 18f)
        }
        Glyph.Routines -> {
            rect(3f, 4f, 7f, 7f)
            path(4.5f, 7.5f, 6f, 9f, 8.5f, 6f)
            line(13f, 7.5f, 21f, 7.5f)
            rect(3f, 14f, 7f, 7f)
            line(13f, 17.5f, 21f, 17.5f)
        }
        Glyph.Settings -> {
            circle(12f, 12f, 3.2f)
            rays(12f, 12f, 6.5f, 9.5f)
            circle(12f, 12f, 6.5f)
        }
        Glyph.Exit -> {
            path(14f, 4f, 5f, 4f, 5f, 20f, 14f, 20f)
            line(10f, 12f, 21f, 12f)
            path(17f, 8f, 21f, 12f, 17f, 16f)
        }
        Glyph.Plus -> {
            line(12f, 5f, 12f, 19f)
            line(5f, 12f, 19f, 12f)
        }
        Glyph.Minus -> line(5f, 12f, 19f, 12f)
        Glyph.Play -> path(8f, 5f, 19f, 12f, 8f, 19f, close = true)
        Glyph.Pause -> {
            line(9f, 5f, 9f, 19f)
            line(15f, 5f, 15f, 19f)
        }
        Glyph.Stop -> rect(6f, 6f, 12f, 12f)
        Glyph.Check -> path(4.5f, 12.5f, 9.5f, 17.5f, 19.5f, 6.5f)
        Glyph.Close -> {
            line(6f, 6f, 18f, 18f)
            line(18f, 6f, 6f, 18f)
        }
        Glyph.Trash -> {
            line(4f, 6f, 20f, 6f)
            path(9f, 6f, 9f, 3.5f, 15f, 3.5f, 15f, 6f)
            path(6f, 6f, 7f, 21f, 17f, 21f, 18f, 6f)
        }
        Glyph.Pin -> {
            path(9f, 3f, 15f, 3f, 14f, 10f, 17f, 14f, 7f, 14f, 10f, 10f, close = true)
            line(12f, 14f, 12f, 21f)
        }
        Glyph.Pen -> {
            path(4f, 20f, 5f, 15f, 16f, 4f, 20f, 8f, 9f, 19f, close = true)
            line(13.5f, 6.5f, 17.5f, 10.5f)
        }
        Glyph.Eraser -> {
            path(3f, 15f, 13f, 5f, 20f, 12f, 12f, 20f, 7f, 20f, close = true)
            line(8f, 10f, 15f, 17f)
        }
        Glyph.Sun -> {
            circle(12f, 12f, 4.5f)
            rays(12f, 12f, 7.5f, 10f)
        }
        Glyph.Moon -> {
            val path = Path().apply {
                moveTo(15f * u, 3.5f * u)
                cubicTo(8f * u, 4f * u, 5f * u, 13f * u, 9f * u, 17.5f * u)
                cubicTo(12f * u, 21f * u, 18f * u, 20.5f * u, 20.5f * u, 16f * u)
                cubicTo(14.5f * u, 17f * u, 11.5f * u, 9f * u, 15f * u, 3.5f * u)
                close()
            }
            drawPath(path, color, style = stroke)
        }
        Glyph.Cloud -> cloud()
        Glyph.SunCloud -> {
            circle(8f, 8f, 3.2f)
            rays(8f, 8f, 5f, 6.8f, 6)
            cloud(3f)
        }
        Glyph.Rain -> {
            cloud(-3f)
            line(8f, 17f, 7f, 21f)
            line(12f, 17f, 11f, 21f)
            line(16f, 17f, 15f, 21f)
        }
        Glyph.Snow -> {
            cloud(-3f)
            for (x in listOf(8f, 12f, 16f)) circle(x, 19.5f, 0.9f, fill = true)
        }
        Glyph.Thunder -> {
            cloud(-3f)
            path(12.5f, 15f, 10f, 19f, 13f, 19f, 11f, 22.5f)
        }
        Glyph.Fog -> {
            line(4f, 8f, 20f, 8f)
            line(3f, 12f, 21f, 12f)
            line(5f, 16f, 19f, 16f)
            line(7f, 20f, 17f, 20f)
        }
        Glyph.Phone -> {
            rect(7f, 2.5f, 10f, 19f, 2.5f)
            line(11f, 18.5f, 13f, 18.5f)
        }
        Glyph.Globe -> {
            circle(12f, 12f, 9f)
            line(3f, 12f, 21f, 12f)
            drawOval(color, p(8f, 3f), Size(8f * u, 18f * u), style = stroke)
        }
        Glyph.Star -> {
            val pts = FloatArray(20)
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 9.5f else 4f
                val a = -PI / 2 + PI * i / 5
                pts[2 * i] = 12f + r * cos(a).toFloat()
                pts[2 * i + 1] = 12.5f + r * sin(a).toFloat()
            }
            path(*pts, close = true)
        }
        Glyph.Back -> path(15f, 5f, 8f, 12f, 15f, 19f)
    }
}
