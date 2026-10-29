package dev.framio.launcher

import android.graphics.drawable.Drawable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap

// Shared look of the launcher: colours, type, and the few controls it needs. Foundation only, no Material:
// R8 keeps the release APK under a megabyte and the HOME process near 15 MB.

internal val Ink = Color(0xFFF1ECE4) // warm white, softer than #FFF at night
internal val InkDim = Color(0xFF9A968E)
internal val InkFaint = Color(0xFF55575D)
internal val Card = Color(0xFF16181D)
internal val CardHigh = Color(0xFF1E2128)
internal val Hairline = Color(0xFF262A31)
internal val Amber = Color(0xFFE9A23B)
internal val Teal = Color(0xFF5BC6B4)
internal val Sky = Color(0xFF7DB4F0)
internal val Coral = Color(0xFFF08A7D)
internal val Top = Color(0xFF14161B)
internal val Bottom = Color(0xFF0B0C0F) // the background's lower colour, for the shelf's edge fades

// Named family on purpose: below API 28 Compose only reaches Roboto Light/Thin via "sans-serif-light" etc.
internal val Base = TextStyle(fontFamily = FontFamily.SansSerif)
internal val Body = Base.copy(color = Ink, fontSize = 24.sp)
internal val Small = Base.copy(color = InkDim, fontSize = 19.sp)
internal val Heading = Base.copy(color = Ink, fontSize = 34.sp, fontWeight = FontWeight.Light)

internal typealias Glyph = DrawScope.(Color) -> Unit

/** Press feedback for everything tappable: a slight shrink, no ripple (nothing to draw on Mali-400 for it). */
@Composable
internal fun Modifier.press(
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, label = "press")
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = if (enabled) 1f else 0.38f
        }
        .combinedClickable(interaction, indication = null, enabled = enabled, role = Role.Button, onLongClick = onLongClick, onClick = onClick)
}

/** A rounded button with a label, optionally a glyph. [accent] colours its outline and text. */
@Composable
internal fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Ink,
    glyph: Glyph? = null,
    enabled: Boolean = true,
    filled: Boolean = false,
) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier
            .press(enabled, onClick = onClick)
            .height(56.dp)
            .clip(shape)
            .background(if (filled) accent.copy(alpha = 0.18f) else CardHigh)
            .border(1.dp, if (filled) accent.copy(alpha = 0.6f) else Hairline, shape)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) {
            Canvas(Modifier.size(26.dp)) { glyph(accent) }
            Spacer(Modifier.width(12.dp))
        }
        BasicText(text, style = Body.copy(color = accent, fontSize = 22.sp), maxLines = 1)
    }
}

/** A round icon button, for the home screen's corner. */
@Composable
internal fun RoundButton(glyph: Glyph, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = InkDim, size: Dp = 64.dp) {
    Box(
        modifier
            .press(onClick = onClick)
            .size(size)
            .clip(RoundedCornerShape(size / 2))
            .background(Card)
            .border(1.dp, Hairline, RoundedCornerShape(size / 2)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.44f)) { glyph(tint) }
    }
}

/**
 * A wide level slider in the style of a phone's control centre: the whole bar is the handle, drag or tap anywhere.
 * [onChange] fires while dragging (cheap work only), [onCommit] once on release.
 */
@Composable
internal fun LevelSlider(
    value: Float,
    onChange: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    accent: Color,
    glyph: Glyph,
    label: String,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    var live by remember { mutableFloatStateOf(value) }
    val shape = RoundedCornerShape(20.dp)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(shape)
            .background(CardHigh)
            .border(1.dp, Hairline, shape)
            .pointerInput(Unit) {
                val w = size.width.toFloat()
                detectTapGestures { p ->
                    live = (p.x / w).coerceIn(0f, 1f)
                    change(live)
                    commit(live)
                }
            }
            .pointerInput(Unit) {
                val w = size.width.toFloat()
                detectHorizontalDragGestures(
                    onDragStart = { live = current },
                    onDragEnd = { commit(live) },
                    onDragCancel = { commit(live) },
                ) { _, dx ->
                    live = (live + dx / w).coerceIn(0f, 1f)
                    change(live)
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(maxWidth * value.coerceIn(0f, 1f))
                .background(accent.copy(alpha = 0.32f))
        )
        Row(Modifier.fillMaxHeight().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(32.dp)) { glyph(Ink) }
            Spacer(Modifier.width(18.dp))
            BasicText(label, style = Body)
            Spacer(Modifier.weight(1f))
            BasicText("${Math.round(value * 100)}%", style = Body.copy(color = InkDim, fontFeatureSettings = "tnum"))
        }
    }
}

/** An on/off switch drawn as a track and a knob. */
@Composable
internal fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit, accent: Color = Teal, enabled: Boolean = true) {
    val x by animateFloatAsState(if (checked) 1f else 0f, label = "toggle")
    Canvas(
        Modifier
            .size(84.dp, 48.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
    ) {
        val h = size.height
        drawRoundRect(
            if (checked) accent.copy(alpha = 0.45f) else Hairline,
            cornerRadius = CornerRadius(h / 2),
        )
        val r = h / 2 - 5.dp.toPx()
        drawCircle(if (checked) accent else InkDim, r, Offset(h / 2 + (size.width - h) * x, h / 2))
    }
}

/** A setting: title and explanation on the left, its control on the right. */
@Composable
internal fun SettingRow(title: String, modifier: Modifier = Modifier, detail: String? = null, control: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(end = 24.dp)) {
            BasicText(title, style = Body)
            if (detail != null) {
                Spacer(Modifier.height(4.dp))
                BasicText(detail, style = Small)
            }
        }
        control()
    }
}

/** Choose one of a few values: a row of pills, the chosen one lit. */
@Composable
internal fun <T> Choice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, accent: Color = Teal) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { (v, label) ->
            Pill(label, { onSelect(v) }, Modifier.widthIn(min = 72.dp), accent = if (v == selected) accent else InkDim, filled = v == selected)
        }
    }
}

@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) =
    BasicText(text, modifier, style = Small, maxLines = 3, overflow = TextOverflow.Ellipsis)

/** Icons can be vectors, bitmaps or layer lists: draw any of them into a bitmap of the given size, once. */
internal fun render(drawable: Drawable, sizePx: Int): ImageBitmap = renderBitmap(drawable, sizePx).asImageBitmap()

internal fun renderBitmap(drawable: Drawable, sizePx: Int): android.graphics.Bitmap {
    val bitmap = createBitmap(sizePx, sizePx)
    drawable.setBounds(0, 0, sizePx, sizePx)
    drawable.draw(android.graphics.Canvas(bitmap))
    return bitmap
}

// --- Glyphs: drawn, so they stay sharp at any size and cost no resources

internal object Glyphs {

    private fun DrawScope.stroke() = Stroke(size.minDimension * 0.1f, cap = StrokeCap.Round)

    /** Wi-Fi fan with [level] of 4 arcs lit (null: off, drawn struck through). */
    fun wifi(level: Int?): Glyph = { c ->
        val w = size.width
        val h = size.height
        val center = Offset(w / 2, h * 0.9f)
        val st = stroke()
        for (i in 1..3) {
            val r = h * 0.28f * i
            drawArc(
                if (level != null && level >= i) c else c.copy(alpha = 0.3f),
                startAngle = 225f, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(center.x - r, center.y - r), size = Size(r * 2, r * 2), style = st,
            )
        }
        drawCircle(if (level != null) c else c.copy(alpha = 0.3f), h * 0.08f, center)
        if (level == null) drawLine(c, Offset(w * 0.1f, h * 0.1f), Offset(w * 0.9f, h * 0.9f), st.width, StrokeCap.Round)
    }

    /** Speaker with waves for [level] 0..1 (0: struck through). */
    fun speaker(level: Float): Glyph = { c ->
        val w = size.width
        val h = size.height
        drawPath(
            Path().apply {
                moveTo(w * 0.08f, h * 0.36f); lineTo(w * 0.28f, h * 0.36f); lineTo(w * 0.5f, h * 0.14f)
                lineTo(w * 0.5f, h * 0.86f); lineTo(w * 0.28f, h * 0.64f); lineTo(w * 0.08f, h * 0.64f); close()
            },
            c,
        )
        val st = stroke()
        if (level <= 0f) {
            drawLine(c, Offset(w * 0.64f, h * 0.36f), Offset(w * 0.92f, h * 0.64f), st.width, StrokeCap.Round)
            drawLine(c, Offset(w * 0.92f, h * 0.36f), Offset(w * 0.64f, h * 0.64f), st.width, StrokeCap.Round)
        } else {
            for (i in 1..2) {
                val r = w * 0.18f * i
                drawArc(
                    if (level > (i - 1) * 0.5f) c else c.copy(alpha = 0.3f),
                    startAngle = -45f, sweepAngle = 90f, useCenter = false,
                    topLeft = Offset(w * 0.52f - r, h / 2 - r), size = Size(r * 2, r * 2), style = st,
                )
            }
        }
    }

    val bell: Glyph = { c ->
        val w = size.width
        val h = size.height
        drawPath(
            Path().apply {
                moveTo(w * 0.15f, h * 0.76f); quadraticTo(w * 0.26f, h * 0.62f, w * 0.26f, h * 0.44f)
                cubicTo(w * 0.26f, h * 0.1f, w * 0.74f, h * 0.1f, w * 0.74f, h * 0.44f)
                quadraticTo(w * 0.74f, h * 0.62f, w * 0.85f, h * 0.76f); close()
            },
            c,
        )
        drawCircle(c, h * 0.09f, Offset(w / 2, h * 0.86f))
    }

    val sun: Glyph = { c ->
        val m = size.minDimension
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(c, m * 0.2f, center)
        val st = stroke()
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val dx = Math.cos(a).toFloat()
            val dy = Math.sin(a).toFloat()
            drawLine(c, center + Offset(dx, dy) * (m * 0.34f), center + Offset(dx, dy) * (m * 0.47f), st.width, StrokeCap.Round)
        }
    }

    val moon: Glyph = { c ->
        val m = size.minDimension
        val path = Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(Offset(size.width / 2, size.height / 2), m * 0.42f))
        }
        val bite = Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(Offset(size.width * 0.72f, size.height * 0.32f), m * 0.36f))
        }
        drawPath(Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, path, bite), c)
    }

    val gear: Glyph = { c ->
        val m = size.minDimension
        val center = Offset(size.width / 2, size.height / 2)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val d = Offset(Math.cos(a).toFloat(), Math.sin(a).toFloat())
            drawLine(c, center + d * (m * 0.25f), center + d * (m * 0.46f), m * 0.16f, StrokeCap.Butt)
        }
        drawCircle(c, m * 0.32f, center)
        drawCircle(Card, m * 0.13f, center)
    }

    /** Nine dots: all apps. */
    val grid: Glyph = { c ->
        val step = size.minDimension / 3
        for (i in 0..2) for (j in 0..2) drawCircle(c, step * 0.2f, Offset(step * (i + 0.5f), step * (j + 0.5f)))
    }

    val home: Glyph = { c ->
        val w = size.width
        val h = size.height
        val st = stroke()
        drawPath(
            Path().apply {
                moveTo(w * 0.12f, h * 0.5f); lineTo(w * 0.5f, h * 0.14f); lineTo(w * 0.88f, h * 0.5f)
                moveTo(w * 0.24f, h * 0.4f); lineTo(w * 0.24f, h * 0.86f); lineTo(w * 0.76f, h * 0.86f); lineTo(w * 0.76f, h * 0.4f)
            },
            c, style = st,
        )
    }

    val back: Glyph = { c ->
        val w = size.width
        val h = size.height
        val st = stroke()
        drawPath(
            Path().apply { moveTo(w * 0.42f, h * 0.2f); lineTo(w * 0.12f, h * 0.5f); lineTo(w * 0.42f, h * 0.8f) },
            c, style = st,
        )
        drawLine(c, Offset(w * 0.14f, h * 0.5f), Offset(w * 0.88f, h * 0.5f), st.width, StrokeCap.Round)
    }

    val close: Glyph = { c ->
        val w = size.width
        val h = size.height
        val sw = stroke().width
        drawLine(c, Offset(w * 0.2f, h * 0.2f), Offset(w * 0.8f, h * 0.8f), sw, StrokeCap.Round)
        drawLine(c, Offset(w * 0.8f, h * 0.2f), Offset(w * 0.2f, h * 0.8f), sw, StrokeCap.Round)
    }

    val power: Glyph = { c ->
        val m = size.minDimension
        val st = stroke()
        val r = m * 0.36f
        val center = Offset(size.width / 2, size.height * 0.55f)
        drawArc(c, -60f, 300f, false, Offset(center.x - r, center.y - r), Size(r * 2, r * 2), style = st)
        drawLine(c, Offset(size.width / 2, size.height * 0.08f), Offset(size.width / 2, size.height * 0.5f), st.width, StrokeCap.Round)
    }

    val refresh: Glyph = { c ->
        val m = size.minDimension
        val st = stroke()
        val r = m * 0.34f
        val center = Offset(size.width / 2, size.height / 2)
        drawArc(c, -80f, 290f, false, Offset(center.x - r, center.y - r), Size(r * 2, r * 2), style = st)
        drawPath(
            Path().apply {
                moveTo(center.x + m * 0.04f, center.y - r - m * 0.14f); lineTo(center.x + m * 0.2f, center.y - r)
                lineTo(center.x + m * 0.04f, center.y - r + m * 0.14f)
            },
            c, style = st,
        )
    }

    val lock: Glyph = { c ->
        val w = size.width
        val h = size.height
        val st = stroke()
        drawRoundRect(c, Offset(w * 0.2f, h * 0.45f), Size(w * 0.6f, h * 0.45f), CornerRadius(w * 0.08f))
        drawArc(c, 180f, 180f, false, Offset(w * 0.32f, h * 0.15f), Size(w * 0.36f, h * 0.6f), style = st)
    }
}
