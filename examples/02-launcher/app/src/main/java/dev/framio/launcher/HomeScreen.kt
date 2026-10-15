package dev.framio.launcher

import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Laid out for the frame: 1280×800 at 160 dpi, so 1.dp == 1 px. Sizes are meant to read from across a room.

private val Ink = Color(0xFFF1ECE4) // warm white, softer than #FFF at night
private val InkDim = Color(0xFF9A968E)
private val InkFaint = Color(0xFF55575D)
private val Card = Color(0xFF16181D)
private val Hairline = Color(0xFF262A31)
private val Amber = Color(0xFFE9A23B)
private val Teal = Color(0xFF5BC6B4)

// Named family on purpose: below API 28 Compose only reaches Roboto Light/Thin via "sans-serif-light" etc.
private val Base = TextStyle(fontFamily = FontFamily.SansSerif)

@Composable
fun HomeScreen(
    lifecycle: Lifecycle,
    use24h: Boolean,
    frameoInstalled: Boolean,
    helloInstalled: Boolean,
    onPhotos: () -> Unit,
    onHello: () -> Unit,
) {
    val now by rememberMinuteClock(lifecycle)

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF14161B), Color(0xFF0B0C0F))))
            .padding(start = 72.dp, end = 72.dp, top = 48.dp, bottom = 88.dp) // bottom: keep clear of the hot corner dot
    ) {
        BasicText(
            "framio",
            Modifier.align(Alignment.TopEnd).padding(top = 18.dp),
            style = Base.copy(color = InkFaint, fontSize = 20.sp, letterSpacing = 6.sp),
        )

        Column(Modifier.fillMaxSize()) {
            Clock(now, use24h)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().height(280.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                Tile(
                    title = "Photos",
                    subtitle = if (frameoInstalled) "Frameo slideshow" else "Frameo not installed",
                    accent = Amber,
                    enabled = frameoInstalled,
                    onClick = onPhotos,
                    modifier = Modifier.weight(1.4f),
                    glyph = ::photosGlyph,
                )
                Tile(
                    title = "Hello Frame",
                    subtitle = if (helloInstalled) "Example 01 · WebView" else "Not installed",
                    accent = Teal,
                    enabled = helloInstalled,
                    onClick = onHello,
                    modifier = Modifier.weight(1f),
                    glyph = ::clockGlyph,
                )
            }
        }
    }
}

@Composable
private fun Clock(now: Long, use24h: Boolean) {
    val locale = Locale.getDefault()
    val time = remember(now, use24h) { SimpleDateFormat(if (use24h) "HH:mm" else "h:mm", locale).format(Date(now)) }
    val amPm = remember(now) { SimpleDateFormat("a", locale).format(Date(now)) }
    val date = remember(now) {
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(Date(now))
    }

    Row(verticalAlignment = Alignment.Bottom) {
        BasicText(
            time,
            Modifier.alignByBaseline(),
            style = Base.copy(
                color = Ink,
                fontSize = 196.sp,
                lineHeight = 196.sp,
                fontWeight = FontWeight.Light, // Thin looks elegant up close but fades from across the room
                letterSpacing = (-6).sp,
                fontFeatureSettings = "tnum", // digits don't shift as the minutes change
            ),
        )
        if (!use24h) {
            Spacer(Modifier.width(20.dp))
            BasicText(
                amPm,
                Modifier.alignByBaseline(),
                style = Base.copy(color = InkDim, fontSize = 40.sp, fontWeight = FontWeight.Light),
            )
        }
    }
    BasicText(
        date,
        Modifier.padding(start = 6.dp),
        style = Base.copy(color = InkDim, fontSize = 40.sp, fontWeight = FontWeight.Light),
    )
}

@Composable
private fun Tile(
    title: String,
    subtitle: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    glyph: DrawScope.(Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
    val shape = RoundedCornerShape(24.dp)

    Box(
        modifier
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.38f
            }
            .clip(shape)
            .background(Card)
            .drawBehind {
                // soft accent glow from the top-right corner gives each tile its colour
                val r = size.height * 1.1f
                val corner = Offset(size.width, 0f)
                drawCircle(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.22f), Color.Transparent), corner, r),
                    radius = r,
                    center = corner,
                )
            }
            .border(if (pressed) 2.dp else 1.dp, if (pressed) accent else Hairline, shape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(36.dp)
    ) {
        Canvas(Modifier.size(96.dp, 72.dp).align(Alignment.TopStart)) { glyph(accent) }
        Column(Modifier.align(Alignment.BottomStart)) {
            BasicText(title, style = Base.copy(color = Ink, fontSize = 48.sp, letterSpacing = (-0.5).sp))
            Spacer(Modifier.height(4.dp))
            BasicText(subtitle, style = Base.copy(color = InkDim, fontSize = 24.sp))
        }
    }
}

/** Sun over two hills. */
private fun photosGlyph(scope: DrawScope, accent: Color) = with(scope) {
    val w = size.width
    val h = size.height
    drawCircle(accent, radius = h * 0.15f, center = Offset(w * 0.78f, h * 0.22f))
    drawPath(
        Path().apply { moveTo(w * 0.36f, h); lineTo(w * 0.66f, h * 0.46f); lineTo(w, h); close() },
        accent.copy(alpha = 0.55f),
    )
    drawPath(
        Path().apply { moveTo(0f, h); lineTo(w * 0.30f, h * 0.28f); lineTo(w * 0.68f, h); close() },
        accent,
    )
}

/** A clock face: example 01 is a clock page. */
private fun clockGlyph(scope: DrawScope, accent: Color) = with(scope) {
    val stroke = 6.dp.toPx()
    val r = size.height / 2 - stroke / 2
    val c = Offset(size.height / 2, size.height / 2)
    drawCircle(accent, radius = r, center = c, style = Stroke(stroke))
    drawLine(accent, c, Offset(c.x, c.y - r * 0.6f), stroke, StrokeCap.Round)
    drawLine(accent, c, Offset(c.x + r * 0.45f, c.y), stroke, StrokeCap.Round)
}

/** Current time, refreshed on each minute boundary, only while the screen is visible. */
@Composable
private fun rememberMinuteClock(lifecycle: Lifecycle): State<Long> =
    produceState(System.currentTimeMillis(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = System.currentTimeMillis()
                delay(60_000L - value % 60_000L + 20L)
            }
        }
    }
