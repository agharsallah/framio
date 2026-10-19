package dev.framio.meadow.worlds.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.framio.meadow.audio.Instrument
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.rememberSprite
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val TINTS = listOf(Palette.sky, Palette.lavender, Palette.mint, Palette.rose)

private class Pop(val bubble: Bubble, val tMs: Float, val x: Float, val y: Float)

/** Everything the bubbles need between frames; plain fields, the canvas redraws from them each frame. */
private class Pond(val level: Int) {
    val popped = HashMap<Int, Pop>()
    val cache = HashMap<Int, Bubble>()
    var pops = 0
    var lastPopMs = 0f
    fun bubble(i: Int) = cache.getOrPut(i) { bubble(i, level) }
}

/**
 * Bubble notes: soft see-through bubbles drift slowly upwards, swaying. Touch one and it pops with a
 * note of the pentatonic scale (any order sounds lovely; bigger bubbles sing lower) and a tiny soft
 * burst. Now and then a bubble carries a little thing that floats gently down when freed and is
 * named aloud. One canvas, every position computed from the clock: nothing recomposes per frame.
 */
@Composable
fun BubbleNotes(s: PlayScope) {
    val pond = remember { Pond(s.level) } // the level is fixed for the session so bubbles never jump
    var t by remember { mutableFloatStateOf(0f) }
    val co = rememberCoroutineScope()
    val things: Map<String, ImageBitmap?> = BUBBLE_THINGS.associateWith { rememberSprite("ob_$it") }

    LaunchedEffect(Unit) {
        s.rounds(0, BUBBLE_POPS_TO_FINISH / 6)
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { t = (it - start) / 1_000_000f }
            if (pond.pops >= BUBBLE_POPS_TO_FINISH && t - pond.lastPopMs > 4000f) break // all settled: stop drawing
            if (pond.popped.size > 12) pond.popped.entries.removeAll { t - it.value.tMs > 4000f }
            if (pond.cache.size > 40) pond.cache.keys.removeAll { it < liveRange(t, pond.level, 800f).first }
        }
    }
    LaunchedEffect(Unit) {
        s.awaitQuiet()
        s.prompt("bubbles_pop")
    }

    fun popAt(px: Offset, density: Float, wDp: Float, hDp: Float) {
        if (pond.pops >= BUBBLE_POPS_TO_FINISH) return
        val p = Offset(px.x / density, px.y / density)
        val now = t
        var best: Bubble? = null
        var bestD = Float.MAX_VALUE
        var bx = 0f
        var by = 0f
        for (i in liveRange(now, pond.level, hDp)) {
            val b = pond.bubble(i)
            if (now < b.bornMs || b.index in pond.popped || b.gone(now, hDp)) continue
            val (x, y) = b.at(now, wDp, hDp)
            val d = (Offset(x, y) - p).getDistance()
            if (d < b.radius + 22f && d < bestD) {
                best = b; bestD = d; bx = x; by = y
            }
        }
        val b = best ?: return
        pond.popped[b.index] = Pop(b, now, bx, by)
        pond.pops++
        pond.lastPopMs = now
        s.app.sfx.note(Instrument.Kalimba, bubbleNote(b.size), 0.9f)
        s.sfx(Sound.Pop, 0.45f)
        b.thing?.let { thing -> co.launch { delay(250); s.say("name_$thing") } }
        if (pond.pops % 6 == 0) {
            s.rounds(pond.pops / 6, BUBBLE_POPS_TO_FINISH / 6)
            s.roundDone(firstTry = true)
        }
        if (pond.pops >= BUBBLE_POPS_TO_FINISH) {
            s.practised("music:bubbles")
            co.launch { delay(1600); s.finish() }
        }
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent()
                        for (c in ev.changes) if (c.changedToDown()) popAt(c.position, density, size.width / density, size.height / density)
                    }
                }
            },
    ) {
        val d = density
        val wDp = size.width / d
        val hDp = size.height / d
        val now = t
        var lowest: Pair<Offset, Float>? = null
        for (i in liveRange(now, pond.level, hDp)) {
            if (pond.pops >= BUBBLE_POPS_TO_FINISH) break
            val b = pond.bubble(i)
            if (now < b.bornMs || b.index in pond.popped || b.gone(now, hDp)) continue
            val (x, y) = b.at(now, wDp, hDp)
            val c = Offset(x * d, y * d)
            drawBubble(b, c, b.radius * d, things[b.thing], now)
            if (y < hDp - 40f && (lowest == null || c.y > lowest.first.y)) lowest = c to b.radius * d
        }
        if (s.hint) lowest?.let { (c, r) ->
            val p = (now % 1500f) / 1500f
            drawCircle(Palette.honey.copy(alpha = 0.4f * (1f - p)), r + 8f * d + 20f * d * p, c, style = Stroke(6f * d * (1f - p * 0.6f)))
        }
        for (pop in pond.popped.values) drawPop(pop, now, d, things[pop.bubble.thing])
    }
}

private fun DrawScope.drawBubble(b: Bubble, c: Offset, r: Float, thing: ImageBitmap?, now: Float) {
    val tint = TINTS[b.hue]
    if (thing != null) {
        val bob = sin(now / 700f + b.phase) * r * 0.04f
        drawThing(thing, Offset(c.x, c.y + bob), r * 1.15f, 0.95f)
    }
    drawCircle(
        Brush.radialGradient(
            0f to tint.copy(alpha = 0.04f), 0.72f to tint.copy(alpha = 0.10f), 0.92f to tint.copy(alpha = 0.32f), 1f to Color.White.copy(alpha = 0.55f),
            center = c, radius = r,
        ),
        r, c,
    )
    drawCircle(tint.copy(alpha = 0.45f), r, c, style = Stroke(r * 0.035f))
    // the window highlight and a tiny sparkle, like light on soap
    rotate(-35f, c) {
        drawOval(Color.White.copy(alpha = 0.7f), Offset(c.x - r * 0.62f, c.y - r * 0.72f), Size(r * 0.55f, r * 0.26f))
    }
    drawCircle(Color.White.copy(alpha = 0.8f), r * 0.07f, Offset(c.x - r * 0.18f, c.y - r * 0.62f))
    drawCircle(Color.White.copy(alpha = 0.25f), r * 0.16f, Offset(c.x + r * 0.52f, c.y + r * 0.48f))
}

/** The soft burst (a ring and six droplets fading out) and, if it carried one, the thing floating down. */
private fun DrawScope.drawPop(pop: Pop, now: Float, d: Float, thing: ImageBitmap?) {
    val age = now - pop.tMs
    val c = Offset(pop.x * d, pop.y * d)
    val r = pop.bubble.radius * d
    val tint = TINTS[pop.bubble.hue]
    if (age < 450f) {
        val p = age / 450f
        drawCircle(tint.copy(alpha = 0.45f * (1f - p)), r * (1f + 0.35f * p), c, style = Stroke(r * 0.05f * (1f - p) + 1f))
        for (k in 0 until 6) {
            val a = k * PI.toFloat() / 3f + pop.bubble.phase
            val dist = r * (0.9f + 0.6f * p)
            drawCircle(Color.White.copy(alpha = 0.8f * (1f - p)), r * 0.06f, Offset(c.x + cos(a) * dist, c.y + sin(a) * dist))
        }
    }
    if (thing != null && age < 3600f) {
        val fall = (age / 1000f) * (age / 1000f).coerceAtMost(1f) * 70f * d // eases into a slow fall
        val sway = sin(age / 600f + pop.bubble.phase) * 14f * d
        val alpha = ((3600f - age) / 900f).coerceIn(0f, 1f)
        rotate(sin(age / 600f + pop.bubble.phase) * 8f, Offset(c.x + sway, c.y + fall)) {
            drawThing(thing, Offset(c.x + sway, c.y + fall), r * 1.15f, alpha)
        }
    }
}

private fun DrawScope.drawThing(bmp: ImageBitmap, c: Offset, side: Float, alpha: Float) {
    val s = side / maxOf(bmp.width, bmp.height)
    val w = (bmp.width * s).toInt()
    val h = (bmp.height * s).toInt()
    drawImage(
        bmp, IntOffset.Zero, IntSize(bmp.width, bmp.height), IntOffset((c.x - w / 2).toInt(), (c.y - h / 2).toInt()), IntSize(w, h),
        alpha = alpha, filterQuality = FilterQuality.Low,
    )
}
