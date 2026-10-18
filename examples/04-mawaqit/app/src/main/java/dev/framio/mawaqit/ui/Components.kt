package dev.framio.mawaqit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.framio.mawaqit.data.Weather

val Base = TextStyle(fontFamily = Fonts.regular)

/** Tabular digits: a ticking clock or countdown doesn't wobble as its digits change. */
fun digits(size: TextUnit, color: Color, light: Boolean = true) = TextStyle(
    fontFamily = if (light) Fonts.light else Fonts.regular,
    fontSize = size,
    lineHeight = size,
    color = color,
    fontFeatureSettings = "tnum",
    letterSpacing = (-0.02f * size.value).sp,
)

@Composable
fun Label(text: String, color: Color, modifier: Modifier = Modifier, size: TextUnit = 15.sp) {
    // Spaced capitals for Latin; Arabic is a joined script, so no spacing (and a size up: it reads smaller)
    val style = if (text.any { it in '\u0600'..'\u06FF' }) Base.arabic().copy(color = color, fontSize = size * 1.4f)
    else Base.copy(color = color, fontSize = size, letterSpacing = 3.sp, fontWeight = FontWeight.Medium)
    BasicText(if (style.letterSpacing == 3.sp) text.uppercase() else text, modifier, style = style)
}

@Composable
fun Pill(text: String, background: Color, color: Color, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading?.invoke()
        BasicText(text, style = Base.copy(color = color, fontSize = 20.sp))
    }
}

fun Modifier.card(p: Palette, highlighted: Boolean = false, radius: Dp = 24.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return clip(shape)
        .background(if (highlighted) p.accent.copy(alpha = if (p.light) 0.14f else 0.16f) else p.card)
        .border(if (highlighted) 2.dp else 1.dp, if (highlighted) p.accent else p.hairline, shape)
}

@Composable
fun WeatherBadge(w: Weather, p: Palette) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(Modifier.size(34.dp)) { weatherGlyph(w.icon, w.day, p.accent, p.inkDim) }
        BasicText("${w.temperature}°${w.unit}", style = digits(28.sp, p.ink))
    }
}

/** Sun, moon, cloud, rain, snow, storm, fog: drawn, not bitmaps, so they take the theme's colours. */
private fun DrawScope.weatherGlyph(icon: String, day: Boolean, sun: Color, cloud: Color) {
    val w = size.width
    val s = Stroke(w * 0.08f, cap = StrokeCap.Round)
    fun cloudShape(dy: Float = 0f) {
        drawCircle(cloud, w * 0.2f, Offset(w * 0.38f, w * 0.55f + dy))
        drawCircle(cloud, w * 0.26f, Offset(w * 0.58f, w * 0.48f + dy))
        drawRoundRect(cloud, Offset(w * 0.18f, w * 0.55f + dy), Size(w * 0.68f, w * 0.2f), androidx.compose.ui.geometry.CornerRadius(w * 0.1f))
    }
    when (icon) {
        "clear" -> if (day) {
            drawCircle(sun, w * 0.22f, center)
            for (i in 0 until 8) {
                val a = i * Math.PI / 4
                val c = kotlin.math.cos(a).toFloat()
                val sn = kotlin.math.sin(a).toFloat()
                drawLine(sun, Offset(center.x + c * w * 0.33f, center.y + sn * w * 0.33f), Offset(center.x + c * w * 0.45f, center.y + sn * w * 0.45f), s.width, StrokeCap.Round)
            }
        } else { // a crescent: the moon minus a shifted disc
            val moon = androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, w * 0.32f)) }
            val bite = androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(center.x + w * 0.16f, center.y - w * 0.1f), w * 0.28f)) }
            drawPath(androidx.compose.ui.graphics.Path().apply { op(moon, bite, androidx.compose.ui.graphics.PathOperation.Difference) }, sun)
        }
        "cloudy" -> {
            drawCircle(sun, w * 0.16f, Offset(w * 0.68f, w * 0.3f))
            cloudShape()
        }
        "rain", "storm" -> {
            cloudShape(-w * 0.12f)
            for (i in 0..2) {
                val x = w * (0.32f + i * 0.18f)
                drawLine(if (icon == "storm") sun else cloud, Offset(x, w * 0.78f), Offset(x - w * 0.06f, w * 0.95f), s.width, StrokeCap.Round)
            }
        }
        "snow" -> {
            cloudShape(-w * 0.12f)
            for (i in 0..2) drawCircle(cloud, w * 0.05f, Offset(w * (0.32f + i * 0.18f), w * 0.86f))
        }
        else -> { // fog
            for (i in 0..2) drawLine(cloud, Offset(w * 0.15f, w * (0.35f + i * 0.17f)), Offset(w * 0.85f, w * (0.35f + i * 0.17f)), s.width, StrokeCap.Round)
        }
    }
}

/** A phone with a stroke through it: the "please turn off your phones" sign. */
@Composable
fun PhoneOffGlyph(color: Color, modifier: Modifier = Modifier.size(64.dp)) {
    Canvas(modifier) {
        val w = size.width
        val s = w * 0.07f
        drawRoundRect(color, Offset(w * 0.3f, w * 0.12f), Size(w * 0.4f, w * 0.76f), androidx.compose.ui.geometry.CornerRadius(w * 0.08f), style = Stroke(s))
        drawLine(color, Offset(w * 0.44f, w * 0.78f), Offset(w * 0.56f, w * 0.78f), s, StrokeCap.Round)
        drawLine(color, Offset(w * 0.12f, w * 0.12f), Offset(w * 0.88f, w * 0.88f), s * 1.2f, StrokeCap.Round)
    }
}

/** A gear: opens the frame's settings sheet. */
@Composable
fun GearGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2
        val s = r * 0.18f
        drawCircle(color, r * 0.55f, center, style = Stroke(s))
        drawCircle(color, r * 0.2f, center)
        for (i in 0 until 8) {
            val a = i * Math.PI / 4
            val c = kotlin.math.cos(a).toFloat()
            val sn = kotlin.math.sin(a).toFloat()
            drawLine(color, Offset(center.x + c * r * 0.62f, center.y + sn * r * 0.62f), Offset(center.x + c * r * 0.95f, center.y + sn * r * 0.95f), s * 1.4f, StrokeCap.Round)
        }
    }
}

/** A QR code of [text], quiet zone included, in [dark] on [light]. */
@Composable
fun QrCode(text: String, modifier: Modifier, dark: Color = Color.Black, light: Color = Color.White) {
    val matrix = androidx.compose.runtime.remember(text) {
        runCatching {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
        }.getOrNull()
    } ?: return
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(light).padding(14.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val n = matrix.width
            val cell = size.minDimension / n
            for (y in 0 until n) for (x in 0 until n) {
                if (matrix.get(x, y)) drawRect(dark, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
