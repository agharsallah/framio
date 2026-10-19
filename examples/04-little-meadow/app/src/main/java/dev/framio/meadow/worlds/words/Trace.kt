package dev.framio.meadow.worlds.words

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.framio.meadow.audio.Instrument
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.Sprites
import dev.framio.meadow.ui.darken
import dev.framio.meadow.ui.lighten
import dev.framio.meadow.ui.rememberSlowClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** What each pre-writing shape turns into when it's done. */
private val SHAPE_THINGS = mapOf("down" to "ob_cloud", "across" to "ob_car", "circle" to "ob_ball", "zigzag" to "ob_crown", "wave" to "ob_boat")

/** A pentatonic climb, one soft kalimba note per bit of progress: the line "sings" as it fills. */
private val NOTES = intArrayOf(60, 62, 64, 67, 69, 72, 74, 76, 79, 81, 84)

private const val TRACE_ROUNDS = 5

/**
 * Trace: a big shape or letter drawn as a soft wide track with a dotted guide, a green dot where
 * to start and little arrows. The finger fills the track with colour as it follows (anywhere
 * within ~45 dp counts; wobbling is fine), leaving a soft trail and a rising kalimba melody.
 * Age 3 traces lines, a circle, zig-zags and waves; from 4 it's uppercase letters, simple ones
 * first. When it's done: a sparkle, the thing it stands for, and "You wrote it! L is for lemon."
 */
@Composable
fun Trace(s: PlayScope) {
    var round by remember { mutableIntStateOf(0) }
    val glyphs = remember { Glyphs.round(s.level, TRACE_ROUNDS, Random(System.nanoTime())) }
    val glyph = glyphs[round]
    val color = TILE_COLORS[(round * 3 + glyph.id[0].code) % TILE_COLORS.size]
    val thing = if (glyph.isLetter) Alphabet.word(s.lang, glyph.id[0]).sprite else SHAPE_THINGS.getValue(glyph.id)
    val co = rememberCoroutineScope()
    val density = LocalDensity.current

    val boxDp = 520.dp
    val padDp = 40.dp
    val boxPx = with(density) { boxDp.toPx() }
    val padPx = with(density) { padDp.toPx() }
    val tolPx = with(density) { 45.dp.toPx() }
    val tracker = remember(round) { TraceTracker(glyph, boxPx, tolPx) }
    var tick by remember(round) { mutableIntStateOf(0) }  // bumped on every change; only the canvas reads it
    val trail = remember(round) { Trail(28) }
    var finished by remember(round) { mutableStateOf(false) }
    var notesPlayed by remember(round) { mutableIntStateOf(0) }
    var saidNext by remember(round) { mutableStateOf(false) }
    val sparkle = remember(round) { Animatable(0f) }
    val reveal = remember(round) { Animatable(0f) }

    LaunchedEffect(round) {
        s.rounds(round, TRACE_ROUNDS)
        Sprites.preload(listOf(thing))
        if (round == 0) s.afterSpeech() else delay(400)
        if (glyph.isLetter) s.prompt("trace_letter", "letter_${glyph.id.lowercase()}") else s.prompt("trace_${glyph.id}")
    }

    fun complete() {
        finished = true
        s.sfx(Sound.Sparkle)
        co.launch { sparkle.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
        co.launch {
            delay(350)
            reveal.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow))
        }
        co.launch {
            s.friendMood = dev.framio.meadow.ui.Mood.Happy
            if (glyph.isLetter) {
                s.practised("letter:${glyph.id.lowercase()}")
                s.sayAndWait("trace_wrote", "abc_${glyph.id.lowercase()}")
            } else {
                s.practised("prewriting:${glyph.id}")
                s.sayAndWait("trace_done_${glyph.id}")
            }
            s.roundDone(firstTry = !s.hint)
            delay(700)
            if (round + 1 >= TRACE_ROUNDS) {
                s.rounds(TRACE_ROUNDS, TRACE_ROUNDS)
                s.finish()
            } else round++
        }
    }

    fun feed(p: Offset) {
        if (finished) return
        val x = p.x - padPx
        val y = p.y - padPx
        trail.add(x, y)
        val before = tracker.stroke to tracker.progress
        val strokeDone = tracker.feed(x, y)
        if (strokeDone) {
            s.sfx(Sound.Ding, 0.6f, 1f + tracker.stroke * 0.12f)
            notesPlayed = 0
            if (tracker.done) complete()
            else if (!saidNext) {
                saidNext = true
                s.say("trace_next")
            }
        } else if (tracker.stroke == before.first && tracker.progress > before.second) {
            // a soft note for every ~70 dp of new line
            val step = (tracker.progress / (70f * density.density)).toInt()
            if (step > notesPlayed) {
                notesPlayed = step
                s.app.sfx.note(Instrument.Kalimba, NOTES[(step - 1).coerceAtMost(NOTES.size - 1)], 0.45f)
            }
        }
        tick++
    }

    val clock = if (s.hint) rememberSlowClock() else null

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .offset(x = 380.dp, y = 14.dp)
                .size(boxDp + padDp * 2)
                .pointerInput(round) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        feed(down.position)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            feed(c.position) // the lift position counts too: it is often the last bit of the line
                            c.consume()
                            if (!c.pressed) break
                        }
                        trail.lift()
                        tick++
                    }
                },
        ) {
            @Suppress("UNUSED_EXPRESSION") tick
            translate(padPx) {
                drawGlyph(tracker, color, tolPx, finished, sparkle.value, clock?.value)
                drawTrail(trail, color.lighten(0.15f))
            }
        }
        Sprite(
            thing,
            Modifier
                .offset(x = 1000.dp, y = 210.dp)
                .size(240.dp)
                .graphicsLayer {
                    val r = reveal.value
                    alpha = r.coerceIn(0f, 1f)
                    scaleX = 0.4f + 0.6f * r
                    scaleY = 0.4f + 0.6f * r
                    translationY = (1f - r) * 60.dp.toPx()
                },
        )
    }
}

private inline fun DrawScope.translate(by: Float, block: DrawScope.() -> Unit) {
    drawContext.transform.translate(by, by)
    block()
    drawContext.transform.translate(-by, -by)
}

/** The last few finger positions, for a soft fading trail. */
private class Trail(val cap: Int) {
    val xs = FloatArray(cap)
    val ys = FloatArray(cap)
    var n = 0
    var head = 0
    fun add(x: Float, y: Float) {
        xs[head] = x; ys[head] = y
        head = (head + 1) % cap
        if (n < cap) n++
    }
    fun lift() { n = 0 }
    /** i = 0 is the oldest. */
    fun x(i: Int) = xs[(head - n + i + cap) % cap]
    fun y(i: Int) = ys[(head - n + i + cap) % cap]
}

private fun DrawScope.drawTrail(t: Trail, color: Color) {
    for (i in 1 until t.n) {
        val a = i.toFloat() / t.n
        drawLine(color.copy(alpha = 0.55f * a), Offset(t.x(i - 1), t.y(i - 1)), Offset(t.x(i), t.y(i)), strokeWidth = 10f + 16f * a, cap = StrokeCap.Round)
    }
}

private fun pathOf(st: StrokePath, upTo: Float = st.length): Path = Path().apply {
    moveTo(st.xs[0], st.ys[0])
    for (i in 1 until st.xs.size) {
        if (st.s[i] > upTo) {
            val p = st.at(upTo)
            lineTo(p.x, p.y)
            return@apply
        }
        lineTo(st.xs[i], st.ys[i])
    }
}

private fun DrawScope.drawGlyph(tr: TraceTracker, color: Color, tol: Float, finished: Boolean, sparkle: Float, hintMs: Long?) {
    val wide = tol * 1.7f
    val edge = Stroke(wide + 6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val track = Stroke(wide, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val fill = Stroke(wide * 0.72f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val paths = tr.strokes.map { pathOf(it) }
    // the soft track: a faint edge, then cream
    for (p in paths) drawPath(p, Palette.ink.copy(alpha = 0.07f), style = edge)
    for (p in paths) drawPath(p, Palette.cream, style = track)
    tr.strokes.forEachIndexed { i, st ->
        when {
            finished || i < tr.stroke -> drawPath(paths[i], color, style = fill)
            i == tr.stroke -> {
                if (tr.progress > 0f) drawPath(pathOf(st, tr.progress), color, style = fill)
                drawDots(st, tr.progress + 18f, Palette.inkSoft.copy(alpha = 0.45f))
                drawArrows(st, tr.progress, Palette.inkSoft.copy(alpha = 0.55f))
            }
            else -> drawDots(st, 0f, Palette.inkSoft.copy(alpha = 0.22f))
        }
    }
    if (!finished && !tr.done) {
        val st = tr.strokes[tr.stroke]
        val head = st.at(tr.progress)
        val c = Offset(head.x, head.y)
        if (hintMs != null) { // idle: a slow breathing ring around where to put the finger
            val b = 0.5f - 0.5f * cos(hintMs / 1400f * 2f * PI.toFloat())
            drawCircle(Palette.green.copy(alpha = 0.25f * (1f - b)), radius = 30f + 26f * b, center = c)
        }
        drawCircle(Palette.green.darken(0.05f), radius = 24f, center = c)
        drawCircle(Palette.green.lighten(0.35f), radius = 11f, center = Offset(c.x - 5f, c.y - 6f))
    }
    if (sparkle > 0f && sparkle < 1f) drawSparkles(tr, sparkle)
}

/** The dotted guide along the middle of the track, from arc length [from] on. */
private fun DrawScope.drawDots(st: StrokePath, from: Float, color: Color) {
    var at = from
    while (at < st.length) {
        val p = st.at(at)
        drawCircle(color, radius = 4.5f, center = Offset(p.x, p.y))
        at += 20f
    }
}

/** Small chevrons ahead of the finger, pointing the way. */
private fun DrawScope.drawArrows(st: StrokePath, from: Float, color: Color) {
    var at = from + 70f
    val stroke = Stroke(5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    while (at < st.length - 20f) {
        val p = st.at(at)
        val d = st.direction(at)
        val nx = -d.y
        val ny = d.x
        val tip = Offset(p.x + d.x * 9f, p.y + d.y * 9f)
        val path = Path().apply {
            moveTo(p.x - d.x * 7f + nx * 11f, p.y - d.y * 7f + ny * 11f)
            lineTo(tip.x, tip.y)
            lineTo(p.x - d.x * 7f - nx * 11f, p.y - d.y * 7f - ny * 11f)
        }
        drawPath(path, color, style = stroke)
        at += 110f
    }
}

/** A calm sparkle: little stars drift out from the strokes and fade. */
private fun DrawScope.drawSparkles(tr: TraceTracker, t: Float) {
    val r = Random(7)
    val fade = if (t < 0.2f) t / 0.2f else 1f - (t - 0.2f) / 0.8f
    val colors = listOf(Palette.honey, Palette.rose, Palette.sky, Palette.lavender, Palette.mint)
    for (k in 0 until 14) {
        val st = tr.strokes[k % tr.strokes.size]
        val p = st.at(st.length * r.nextFloat())
        val a = r.nextFloat() * 2f * PI.toFloat()
        val dist = 30f + 60f * t * (0.6f + r.nextFloat())
        val c = Offset(p.x + cos(a) * dist, p.y + sin(a) * dist - 20f * t)
        val size = 8f + 8f * r.nextFloat()
        drawStar(c, size, colors[k % colors.size].copy(alpha = 0.9f * fade))
    }
}

private fun DrawScope.drawStar(c: Offset, r: Float, color: Color) {
    val path = Path()
    for (i in 0 until 10) {
        val rad = if (i % 2 == 0) r else r * 0.45f
        val a = -PI.toFloat() / 2 + i * PI.toFloat() / 5
        val x = c.x + cos(a) * rad
        val y = c.y + sin(a) * rad
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}
