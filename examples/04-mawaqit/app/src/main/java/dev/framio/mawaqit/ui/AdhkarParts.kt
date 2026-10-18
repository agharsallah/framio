package dev.framio.mawaqit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.framio.mawaqit.core.AdhkarCategory
import kotlin.math.cos
import kotlin.math.sin

// Small drawn pieces of the adhkar screens: glyphs take the theme's colours like the weather icons do.

/** What a glyph shows: a collection, or the tasbih (null). */
@Composable
fun AdhkarGlyph(category: AdhkarCategory?, color: Color, modifier: Modifier, second: Color = color) {
    Canvas(modifier) {
        when (category) {
            AdhkarCategory.MORNING -> sun(color, rays = true)
            AdhkarCategory.EVENING -> {
                crescent(color, 0.34f, Offset(size.width * 0.46f, size.height * 0.52f))
                star(second, Offset(size.width * 0.78f, size.height * 0.26f), size.width * 0.09f)
            }
            AdhkarCategory.AFTER_PRAYER -> arch(color)
            AdhkarCategory.SLEEP -> {
                crescent(color, 0.28f, Offset(size.width * 0.42f, size.height * 0.56f))
                star(second, Offset(size.width * 0.74f, size.height * 0.22f), size.width * 0.07f)
                star(second, Offset(size.width * 0.86f, size.height * 0.5f), size.width * 0.05f)
            }
            AdhkarCategory.WAKING -> horizonSun(color, second)
            AdhkarCategory.DHIKR -> {
                star(color, center, size.width * 0.44f)
                drawCircle(second, size.width * 0.1f, center)
            }
            AdhkarCategory.ISTIGHFAR -> drop(color, second)
            AdhkarCategory.SALAWAT -> rose(color, second)
            AdhkarCategory.QURAN -> book(color, second)
            AdhkarCategory.DISTRESS -> lantern(color, second)
            AdhkarCategory.EVERYDAY -> house(color, second)
            null -> beads(color, second)
        }
    }
}

private fun DrawScope.stroke() = Stroke(size.width * 0.07f, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun DrawScope.sun(color: Color, rays: Boolean) {
    val w = size.width
    drawCircle(color, w * 0.2f, center)
    if (rays) for (i in 0 until 8) {
        val a = i * Math.PI / 4
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        drawLine(color, Offset(center.x + c * w * 0.32f, center.y + s * w * 0.32f), Offset(center.x + c * w * 0.44f, center.y + s * w * 0.44f), w * 0.07f, StrokeCap.Round)
    }
}

/** The sun half over the horizon, three rays: upon waking. */
private fun DrawScope.horizonSun(color: Color, line: Color) {
    val w = size.width
    val base = Offset(w / 2, w * 0.66f)
    val half = Path().apply { addArc(Rect(base, w * 0.22f), 180f, 180f); close() }
    drawPath(half, color)
    for (deg in listOf(-150.0, -90.0, -30.0)) {
        val a = Math.toRadians(deg)
        drawLine(color, Offset(base.x + cos(a).toFloat() * w * 0.32f, base.y + sin(a).toFloat() * w * 0.32f),
            Offset(base.x + cos(a).toFloat() * w * 0.43f, base.y + sin(a).toFloat() * w * 0.43f), w * 0.07f, StrokeCap.Round)
    }
    drawLine(line, Offset(w * 0.1f, base.y + w * 0.06f), Offset(w * 0.9f, base.y + w * 0.06f), w * 0.07f, StrokeCap.Round)
}

private fun DrawScope.crescent(color: Color, r: Float, c: Offset) {
    val w = size.width
    val moon = Path().apply { addOval(Rect(c, w * r)) }
    val bite = Path().apply { addOval(Rect(Offset(c.x + w * r * 0.5f, c.y - w * r * 0.32f), w * r * 0.86f)) }
    drawPath(Path().apply { op(moon, bite, PathOperation.Difference) }, color)
}

private fun DrawScope.star(color: Color, c: Offset, r: Float) {
    val p = Path()
    for (i in 0 until 8) {
        val a = -Math.PI / 2 + i * Math.PI / 4
        val rr = if (i % 2 == 0) r else r * 0.38f
        val x = c.x + cos(a).toFloat() * rr
        val y = c.y + sin(a).toFloat() * rr
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    drawPath(p, color)
}

/** A pointed arch, like a mihrab: after the prayer. */
private fun DrawScope.arch(color: Color) {
    val w = size.width
    val p = Path().apply {
        moveTo(w * 0.24f, w * 0.88f)
        lineTo(w * 0.24f, w * 0.48f)
        cubicTo(w * 0.24f, w * 0.3f, w * 0.42f, w * 0.22f, w * 0.5f, w * 0.1f)
        cubicTo(w * 0.58f, w * 0.22f, w * 0.76f, w * 0.3f, w * 0.76f, w * 0.48f)
        lineTo(w * 0.76f, w * 0.88f)
    }
    drawPath(p, color, style = stroke())
    drawLine(color, Offset(w * 0.12f, w * 0.88f), Offset(w * 0.88f, w * 0.88f), w * 0.07f, StrokeCap.Round)
    drawCircle(color, w * 0.06f, Offset(w * 0.5f, w * 0.56f))
}

/** A drop of water, a smaller one beside it: seeking forgiveness. */
private fun DrawScope.drop(color: Color, small: Color) {
    val w = size.width
    fun drop(cx: Float, top: Float, r: Float) = Path().apply {
        moveTo(cx, top)
        cubicTo(cx + r * 0.2f, top + r * 0.9f, cx + r, top + r * 1.3f, cx + r, top + r * 2f)
        cubicTo(cx + r, top + r * 2.6f, cx + r * 0.55f, top + r * 3f, cx, top + r * 3f)
        cubicTo(cx - r * 0.55f, top + r * 3f, cx - r, top + r * 2.6f, cx - r, top + r * 2f)
        cubicTo(cx - r, top + r * 1.3f, cx - r * 0.2f, top + r * 0.9f, cx, top)
        close()
    }
    drawPath(drop(w * 0.44f, w * 0.08f, w * 0.27f), color)
    drawPath(drop(w * 0.8f, w * 0.5f, w * 0.1f), small)
}

/** Five petals round a heart: the salawat (the rose is the Prophet's flower in many songs of praise). */
private fun DrawScope.rose(color: Color, heart: Color) {
    val w = size.width
    for (i in 0 until 5) {
        val a = -Math.PI / 2 + i * 2 * Math.PI / 5
        drawCircle(color, w * 0.16f, Offset(center.x + cos(a).toFloat() * w * 0.22f, center.y + sin(a).toFloat() * w * 0.22f))
    }
    drawCircle(heart, w * 0.13f, center)
}

/** An open book on its stand: du'as from the Quran. */
private fun DrawScope.book(color: Color, stand: Color) {
    val w = size.width
    val page = { toRight: Boolean ->
        val edge = if (toRight) w * 0.9f else w * 0.1f
        Path().apply {
            moveTo(w * 0.5f, w * 0.3f)
            quadraticTo((w * 0.5f + edge) / 2, w * 0.18f, edge, w * 0.26f)
            lineTo(edge, w * 0.66f)
            quadraticTo((w * 0.5f + edge) / 2, w * 0.58f, w * 0.5f, w * 0.7f)
            close()
        }
    }
    drawPath(page(false), color, style = stroke())
    drawPath(page(true), color, style = stroke())
    drawLine(stand, Offset(w * 0.24f, w * 0.9f), Offset(w * 0.76f, w * 0.78f), w * 0.07f, StrokeCap.Round)
    drawLine(stand, Offset(w * 0.24f, w * 0.78f), Offset(w * 0.76f, w * 0.9f), w * 0.07f, StrokeCap.Round)
}

/** A lantern with its flame: light in hard times. */
private fun DrawScope.lantern(color: Color, flame: Color) {
    val w = size.width
    drawCircle(color, w * 0.07f, Offset(w * 0.5f, w * 0.12f), style = Stroke(w * 0.05f))
    val body = Path().apply {
        moveTo(w * 0.34f, w * 0.28f)
        lineTo(w * 0.66f, w * 0.28f)
        lineTo(w * 0.74f, w * 0.78f)
        lineTo(w * 0.26f, w * 0.78f)
        close()
    }
    drawPath(body, color, style = stroke())
    drawLine(color, Offset(w * 0.2f, w * 0.9f), Offset(w * 0.8f, w * 0.9f), w * 0.07f, StrokeCap.Round)
    drawOval(flame, Offset(w * 0.43f, w * 0.42f), Size(w * 0.14f, w * 0.24f))
}

/** A house with its door: the everyday du'as. */
private fun DrawScope.house(color: Color, door: Color) {
    val w = size.width
    val p = Path().apply {
        moveTo(w * 0.14f, w * 0.48f)
        lineTo(w * 0.5f, w * 0.16f)
        lineTo(w * 0.86f, w * 0.48f)
        moveTo(w * 0.24f, w * 0.42f)
        lineTo(w * 0.24f, w * 0.86f)
        lineTo(w * 0.76f, w * 0.86f)
        lineTo(w * 0.76f, w * 0.42f)
    }
    drawPath(p, color, style = stroke())
    val d = Path().apply {
        moveTo(w * 0.41f, w * 0.86f)
        lineTo(w * 0.41f, w * 0.64f)
        quadraticTo(w * 0.5f, w * 0.5f, w * 0.59f, w * 0.64f)
        lineTo(w * 0.59f, w * 0.86f)
        close()
    }
    drawPath(d, door)
}

/** A ring of beads with its tassel: the tasbih. */
private fun DrawScope.beads(color: Color, tassel: Color) {
    val w = size.width
    val c = Offset(w / 2, w * 0.44f)
    for (i in 0 until 11) {
        val a = Math.PI / 2 + (i + 0.5) * (2 * Math.PI / 12)
        drawCircle(color, w * 0.058f, Offset(c.x + cos(a).toFloat() * w * 0.3f, c.y + sin(a).toFloat() * w * 0.3f))
    }
    val bottom = Offset(c.x, c.y + w * 0.3f)
    drawCircle(tassel, w * 0.07f, bottom)
    drawLine(tassel, bottom, Offset(c.x, w * 0.96f), w * 0.06f, StrokeCap.Round)
}

/** A flame: the streak. */
@Composable
fun AdFlame(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val p = Path().apply {
            moveTo(w * 0.5f, w * 0.04f)
            cubicTo(w * 0.62f, w * 0.3f, w * 0.88f, w * 0.42f, w * 0.86f, w * 0.66f)
            cubicTo(w * 0.84f, w * 0.88f, w * 0.68f, w * 0.98f, w * 0.5f, w * 0.98f)
            cubicTo(w * 0.32f, w * 0.98f, w * 0.14f, w * 0.88f, w * 0.14f, w * 0.66f)
            cubicTo(w * 0.14f, w * 0.48f, w * 0.3f, w * 0.4f, w * 0.34f, w * 0.24f)
            cubicTo(w * 0.42f, w * 0.34f, w * 0.44f, w * 0.42f, w * 0.44f, w * 0.48f)
            cubicTo(w * 0.52f, w * 0.38f, w * 0.54f, w * 0.2f, w * 0.5f, w * 0.04f)
            close()
        }
        drawPath(p, color)
    }
}

/** A check mark. */
fun DrawScope.adCheck(color: Color, width: Float) {
    val w = size.width
    val p = Path().apply {
        moveTo(w * 0.28f, w * 0.52f)
        lineTo(w * 0.44f, w * 0.67f)
        lineTo(w * 0.73f, w * 0.36f)
    }
    drawPath(p, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** A chevron pointing to the start (‹ in left-to-right) or the end. Mirrored by the caller's layout direction. */
@Composable
fun AdChevron(color: Color, toEnd: Boolean, rtl: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val right = toEnd != rtl
        val x0 = if (right) w * 0.4f else w * 0.6f
        val x1 = if (right) w * 0.62f else w * 0.38f
        val p = Path().apply {
            moveTo(x0, w * 0.26f)
            lineTo(x1, w * 0.5f)
            lineTo(x0, w * 0.74f)
        }
        drawPath(p, color, style = Stroke(w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun AdCross(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        drawLine(color, Offset(w * 0.3f, w * 0.3f), Offset(w * 0.7f, w * 0.7f), w * 0.08f, StrokeCap.Round)
        drawLine(color, Offset(w * 0.7f, w * 0.3f), Offset(w * 0.3f, w * 0.7f), w * 0.08f, StrokeCap.Round)
    }
}

/** An arrow going round: start again. */
@Composable
fun AdReset(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val s = w * 0.09f
        val r = w * 0.32f
        drawArc(color, -50f, 280f, false, Offset(w / 2 - r, w / 2 - r), Size(2 * r, 2 * r), style = Stroke(s, cap = StrokeCap.Round))
        // the arrowhead at the arc's start, pointing along it
        val a = Math.toRadians(-50.0)
        val tip = Offset(w / 2 + r * cos(a).toFloat(), w / 2 + r * sin(a).toFloat())
        val head = Path().apply {
            moveTo(tip.x + w * 0.14f, tip.y - w * 0.02f)
            lineTo(tip.x - w * 0.02f, tip.y - w * 0.16f)
            lineTo(tip.x - w * 0.04f, tip.y + w * 0.06f)
            close()
        }
        drawPath(head, color)
    }
}

/**
 * A tap target that never takes the D-pad focus: on this frame the IR remote's arrows and OK belong to the reader
 * (AdhkarController.onKey), and a focused button would swallow OK as its own click.
 */
@Composable
fun Modifier.adClick(onClick: () -> Unit): Modifier =
    focusProperties { canFocus = false }.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)

/** A ring filled to [fraction] from the top, clockwise. */
@Composable
fun AdRing(fraction: Float, color: Color, track: Color, stroke: Dp, modifier: Modifier) {
    Canvas(modifier) {
        val s = stroke.toPx()
        val inset = s / 2
        val arcSize = Size(size.width - s, size.height - s)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(s))
        if (fraction > 0f) drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(s, cap = StrokeCap.Round))
    }
}

/** A thin bar of [total] segments: [done] full, the [current] one outlined, its partial count filled in part. */
@Composable
fun AdSegments(total: Int, filled: (Int) -> Float, current: Int, modifier: Modifier) {
    val p = LocalPalette.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (total > 30) 3.dp else 5.dp)) {
        for (i in 0 until total) {
            val f = filled(i)
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50))
                    .background(if (i == current) p.ink.copy(alpha = 0.22f) else p.hairline),
            ) {
                if (f > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(50)).background(if (f >= 1f) p.accent else p.accent.copy(alpha = 0.7f)))
            }
        }
    }
}

/** A round button for the remote and the finger: 64 dp, on the card colour. */
@Composable
fun AdRoundButton(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 64.dp, selected: Boolean = false, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier.size(size).clip(RoundedCornerShape(50))
            .background(if (selected) p.accent.copy(alpha = 0.22f) else p.card)
            .border(1.dp, if (selected) p.accent else p.hairline, RoundedCornerShape(50))
            .adClick(onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A pill-shaped text button. */
@Composable
fun AdPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, leading: (@Composable () -> Unit)? = null) {
    val p = LocalPalette.current
    Row(
        modifier.clip(RoundedCornerShape(50))
            .background(if (primary) p.accent else p.card)
            .border(1.dp, if (primary) p.accent else p.hairline, RoundedCornerShape(50))
            .adClick(onClick)
            .padding(horizontal = 26.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        val style = Base.copy(color = if (primary) p.onAccent else p.ink, fontSize = 22.sp)
        BasicText(text, style = if (text.any { it in '؀'..'ۿ' }) style.arabic().copy(fontSize = 24.sp) else style, maxLines = 1)
    }
}

