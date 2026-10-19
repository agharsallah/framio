package dev.framio.meadow.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.framio.meadow.LocalApp
import dev.framio.meadow.audio.Caption
import dev.framio.meadow.audio.CaptionKind
import dev.framio.meadow.audio.CaptionText
import dev.framio.meadow.core.Friend
import dev.framio.meadow.core.Pose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Where a screen wants its captions: a free band of the screen that never holds anything to tap
 * or look at. The pill sits in the band ([start]..width-[end]), [inset] from the top (its centre)
 * or from the bottom (its lower edge).
 */
data class CaptionSpot(
    val top: Boolean,
    val start: Dp,
    val end: Dp,
    val inset: Dp,
    /** -1 start, 0 centre, 1 end of the band. */
    val align: Int,
    /** Show the speaker's face in the pill (when the friend isn't standing right next to it). */
    val face: Boolean,
    /** A speech-bubble tail toward a friend in the bottom corner: -1 left, 1 right, 0 none. */
    val tail: Int = 0,
) {
    companion object {
        /** In the activity's top bar, between the back and "say it again" buttons; also the world screens. */
        val TopBar = CaptionSpot(top = true, start = 124.dp, end = 124.dp, inset = 56.dp, align = 0, face = true)
        /** Top right, beside a screen title (sticker book). */
        val TopEnd = CaptionSpot(top = true, start = 600.dp, end = 32.dp, inset = 64.dp, align = 1, face = true)
        /** Under centred content (rest and wiggle-break screens). */
        val BottomCenter = CaptionSpot(top = false, start = 120.dp, end = 120.dp, inset = 26.dp, align = 0, face = true)
        /** Next to the corner friend of the home screen and the mailbox, coming out of its mouth. */
        fun besideFriend(start: Dp) = CaptionSpot(top = false, start = start, end = 40.dp, inset = 34.dp, align = -1, face = false, tail = -1)
        /** Left of a friend leaning in from the bottom-right corner (a grown-up's call). */
        val ByCaller = CaptionSpot(top = false, start = 60.dp, end = 300.dp, inset = 40.dp, align = 1, face = false, tail = 1)
    }
}

private val CAPTION_STYLE = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.ExtraBold, fontSize = 27.sp, lineHeight = 35.sp)
private val MAX_PILL = 860.dp
private val PAD_X = 26.dp
private val PAD_Y = 13.dp
private val FACE = 58.dp

/**
 * What the friends say, written in a soft paper pill: for grown-ups and early readers, never needed
 * to play. The words of a line light up as they are spoken (timed from the clip, see [CaptionText]),
 * long lines turn page by sentence, and the pill stays a moment after the voice ends, then fades.
 *
 * It never takes touches (no pointer input: taps fall through to whatever is under it) and only
 * draws in the band [spot] gives it; null hides it. Everything is drawn in the draw phase: while the
 * text stands still nothing redraws, and while speaking it redraws once per word.
 */
@Composable
fun CaptionOverlay(spot: CaptionSpot?, friend: Friend?, night: Boolean) {
    val app = LocalApp.current
    val live by app.voice.caption.collectAsState()
    val settings by app.settings.flow.collectAsState()
    val on = settings.captions && spot != null
    val reduced = settings.reducedMotion

    var shown by remember { mutableStateOf<Caption?>(null) }
    var place by remember { mutableStateOf(spot) }
    var who by remember { mutableStateOf(friend) }
    val show = remember { Animatable(0f) }

    LaunchedEffect(live, on, spot) {
        val c = live
        if (place != spot && show.value > 0f) show.animateTo(0f, tween(150)) // moving to another spot: out, then in there
        if (!on || c == null) {
            show.animateTo(0f, tween(300))
            return@LaunchedEffect
        }
        val left = if (c.done) CaptionText.lingerMs(c.text) - (SystemClock.uptimeMillis() - c.clipStartMs) else Long.MAX_VALUE
        if (left <= 0) {
            show.animateTo(0f, tween(300))
            return@LaunchedEffect
        }
        place = spot
        who = friend
        shown = c
        show.animateTo(1f, tween(if (reduced) 500 else 360, easing = FastOutSlowInEasing))
        if (c.done) {
            delay(left)
            show.animateTo(0f, tween(450))
        }
    }

    // the word being said: [spoken] characters are said (through the current word), [word] starts it
    var spoken by remember { mutableIntStateOf(0) }
    var word by remember { mutableIntStateOf(-1) }
    LaunchedEffect(shown?.seq, shown?.clip, shown?.clipStartMs) {
        val c = shown ?: return@LaunchedEffect
        if (c.done) {
            spoken = c.text.length
            word = -1
            return@LaunchedEffect
        }
        val part = c.parts[c.clip]
        spoken = part.start
        word = -1
        for (w in CaptionText.words(c.text, part)) {
            val wait = c.clipStartMs + w.atMs - SystemClock.uptimeMillis()
            if (wait > 0) delay(wait)
            word = w.start
            spoken = w.end
        }
    }

    val c = shown ?: return
    val s = place ?: return
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 6)
    val faceShown = s.face && (who != null || c.kind == CaptionKind.Message)
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val bandPx = with(density) { (config.screenWidthDp.dp - s.start - s.end).coerceAtMost(MAX_PILL).toPx() }
    val textMaxPx = (bandPx - with(density) { (PAD_X * 2 + if (faceShown) FACE + 10.dp else 0.dp).toPx() }).roundToInt()
    // about two lines' worth: Nunito ExtraBold averages a little over half an em per character
    val maxChars = remember(textMaxPx) { (2 * 0.9f * textMaxPx / with(density) { CAPTION_STYLE.fontSize.toPx() * 0.55f }).toInt().coerceAtLeast(30) }
    val pages = remember(c.text, maxChars) { CaptionText.pages(c.text, maxChars) }
    val pageIndex by remember(pages) { derivedStateOf { CaptionText.pageAt(pages, if (word >= 0) word else spoken) } }
    val page = pages[pageIndex.coerceIn(0, pages.lastIndex)]
    val layout = remember(c.text, page, textMaxPx) {
        val t = c.text.substring(page.first, page.last + 1)
        val l = measurer.measure(t, CAPTION_STYLE, constraints = Constraints(maxWidth = textMaxPx))
        if (l.lineCount <= 2) l else measurer.measure(t, CAPTION_STYLE.copy(fontSize = 23.sp, lineHeight = 30.sp), constraints = Constraints(maxWidth = textMaxPx))
    }

    val padX = with(density) { PAD_X.toPx() }
    val padY = with(density) { PAD_Y.toPx() }
    val facePx = with(density) { FACE.toPx() }
    val faceRoom = if (faceShown) facePx + with(density) { 10.dp.toPx() } else 0f
    val targetW = layout.size.width + 2 * padX + faceRoom
    val targetH = maxOf(layout.size.height + 2 * padY, if (faceShown) facePx + 2 * with(density) { 6.dp.toPx() } else 0f)
    val pillW = remember { Animatable(targetW) }
    val pillH = remember { Animatable(targetH) }
    val textIn = remember { Animatable(1f) }
    LaunchedEffect(targetW, targetH) {
        if (show.value < 0.05f) {
            pillW.snapTo(targetW)
            pillH.snapTo(targetH)
        } else {
            coroutineScope {
                launch { pillW.animateTo(targetW, tween(260, easing = FastOutSlowInEasing)) }
                launch { pillH.animateTo(targetH, tween(260, easing = FastOutSlowInEasing)) }
            }
        }
    }
    LaunchedEffect(layout) {
        if (show.value < 0.05f) textIn.snapTo(1f) else {
            textIn.snapTo(0f)
            textIn.animateTo(1f, tween(240))
        }
    }

    val faceBmp = who?.let { rememberSprite(it.sprite(Pose.Happy)) }
    val colors = if (night) NightColors else DayColors
    val tailPath = remember { Path() }
    val pageStart = page.first

    Spacer(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val a = show.value
                if (a <= 0.01f) return@drawBehind
                val w = pillW.value
                val h = pillH.value
                val left = s.start.toPx()
                val right = size.width - s.end.toPx()
                val x = when (s.align) {
                    -1 -> left
                    1 -> right - w
                    else -> (left + right - w) / 2
                }
                val slide = if (reduced) 0f else (1f - a) * 10.dp.toPx() * (if (s.top) -1f else 1f)
                val y = (if (s.top) s.inset.toPx() - h / 2 else size.height - s.inset.toPx() - h) + slide
                val r = minOf(h / 2, 30.dp.toPx())
                val corner = CornerRadius(r)

                // a soft drawn shadow (three faint layers): no blur, no layer, cheap at any size
                if (colors.shadow.alpha > 0f) for (i in 1..3) {
                    val g = i * 2.dp.toPx()
                    drawRoundRect(colors.shadow, Offset(x - g / 2, y + g * 0.9f), Size(w + g, h + g / 2), CornerRadius(r + g / 2), alpha = a * (0.55f - i * 0.12f))
                }
                if (s.tail != 0) drawTail(tailPath, s.tail, x, y, w, h, colors.paper, a)
                drawRoundRect(colors.paper, Offset(x, y), Size(w, h), corner, alpha = a * colors.paperAlpha)
                drawRoundRect(colors.rim, Offset(x, y), Size(w, h), corner, alpha = a, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f.dp.toPx()))

                if (faceShown) {
                    val fx = x + padX * 0.55f
                    val fy = y + (h - facePx) / 2
                    translate(fx, fy) {
                        if (c.kind == CaptionKind.Message) {
                            drawCircle(Palette.sky.lighten(0.25f), facePx / 2, Offset(facePx / 2, facePx / 2), alpha = a)
                            inset(0f, 0f, size.width - facePx, size.height - facePx) { drawIcon(Icon.Mail, colors.ink.copy(alpha = a)) }
                        } else if (faceBmp != null) {
                            drawFace(faceBmp, facePx, a * colors.faceAlpha)
                        }
                    }
                }

                val tx = x + if (faceShown) padX * 0.55f + faceRoom else padX
                val ty = y + (h - layout.size.height) / 2
                val ta = a * textIn.value
                translate(tx, ty) {
                    drawWords(layout, pageStart, word, spoken, colors, ta)
                }
            },
    )
}

private class CaptionColors(
    val paper: Color, val paperAlpha: Float, val rim: Color, val shadow: Color,
    val ink: Color, val upcoming: Color, val marker: Color, val faceAlpha: Float,
)

private val DayColors = CaptionColors(
    paper = Palette.cream, paperAlpha = 0.97f, rim = Color(0x14000000), shadow = Color(0x2E3E3346),
    ink = Palette.ink, upcoming = Palette.inkSoft.copy(alpha = 0.78f), marker = Palette.honey.copy(alpha = 0.34f), faceAlpha = 1f,
)

// bedtime: a dim dusk pill with moonlight letters, nothing bright in a dark room
private val NightColors = CaptionColors(
    paper = Palette.nightDeep, paperAlpha = 0.9f, rim = Color(0x22F3E7C9), shadow = Color.Transparent,
    ink = Palette.moon.copy(alpha = 0.8f), upcoming = Palette.moon.copy(alpha = 0.42f), marker = Palette.lavender.copy(alpha = 0.3f), faceAlpha = 0.7f,
)

/** The text with the said part in ink, the rest softer, and a soft marker behind the word being said. */
private fun DrawScope.drawWords(layout: TextLayoutResult, pageStart: Int, word: Int, spoken: Int, colors: CaptionColors, alpha: Float) {
    val len = layout.layoutInput.text.length
    val said = (spoken - pageStart).coerceIn(0, len)
    val w = word - pageStart
    if (w in 0 until len && said > w) {
        val a = layout.getBoundingBox(w)
        val b = layout.getBoundingBox((said - 1).coerceAtLeast(w))
        val line = layout.getLineForOffset(w)
        val top = layout.getLineTop(line)
        val bottom = layout.getLineBottom(line)
        val px = 5.dp.toPx()
        val right = if (layout.getLineForOffset(said - 1) == line) b.right else layout.getLineRight(line)
        drawRoundRect(colors.marker, Offset(a.left - px, top + 2.dp.toPx()), Size(right - a.left + 2 * px, bottom - top - 2.dp.toPx()), CornerRadius(10.dp.toPx()), alpha = alpha)
    }
    if (said >= len) {
        drawText(layout, color = colors.ink, alpha = alpha)
        return
    }
    drawText(layout, color = colors.upcoming, alpha = alpha)
    if (said <= 0) return
    // the said part again in ink, clipped line by line (rectangular clips are cheap on the Mali)
    for (line in 0 until layout.lineCount) {
        val start = layout.getLineStart(line)
        if (said <= start) break
        val end = layout.getLineEnd(line)
        val right = if (said >= end) size.width + layout.size.width else layout.getHorizontalPosition(said, usePrimaryDirection = true)
        clipRect(left = -4f, top = layout.getLineTop(line), right = right, bottom = layout.getLineBottom(line)) {
            drawText(layout, color = colors.ink, alpha = alpha)
        }
    }
}

private fun DrawScope.drawFace(bmp: androidx.compose.ui.graphics.ImageBitmap, px: Float, alpha: Float) {
    drawImage(
        bmp,
        srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
        srcSize = androidx.compose.ui.unit.IntSize(bmp.width, bmp.height),
        dstSize = androidx.compose.ui.unit.IntSize(px.roundToInt(), px.roundToInt()),
        alpha = alpha,
        filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
    )
}

/** A small rounded speech tail on the side of the pill, toward a friend's face at the left (-1) or right (1). */
private fun DrawScope.drawTail(path: Path, side: Int, x: Float, y: Float, w: Float, h: Float, color: Color, alpha: Float) {
    val edge = if (side < 0) x else x + w
    val inward = -side.toFloat() // toward the inside of the pill
    val tip = Offset(edge - inward * 22.dp.toPx(), y + h * 0.22f)
    val upper = Offset(edge + inward * 8.dp.toPx(), y + h * 0.32f)
    val lower = Offset(edge + inward * 28.dp.toPx(), y + h * 0.82f)
    path.rewind()
    path.moveTo(upper.x, upper.y)
    path.quadraticTo(edge - inward * 6.dp.toPx(), y + h * 0.32f, tip.x, tip.y)
    path.quadraticTo(edge - inward * 2.dp.toPx(), y + h * 0.66f, lower.x, lower.y)
    path.close()
    drawPath(path, color, alpha = alpha)
}
