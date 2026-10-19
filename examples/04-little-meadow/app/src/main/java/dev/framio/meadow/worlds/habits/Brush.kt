package dev.framio.meadow.worlds.habits

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.darken
import dev.framio.meadow.ui.floaty
import dev.framio.meadow.ui.hintPulse
import dev.framio.meadow.ui.lighten
import dev.framio.meadow.ui.pressable
import dev.framio.meadow.ui.rememberSlowClock
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.State
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val MOUTH_X = 230f
private const val MOUTH_Y = 96f
private const val MOUTH_W = 720f
private const val MOUTH_H = 450f
private const val PETALS = 12
private val MID_LINES = listOf("brush_mid_1", "brush_mid_2", "brush_mid_3", "brush_mid_4")

private class Bug(val area: MouthArea, val index: Int, val count: Int, val x: Float, val y: Float, val variant: Int)

/**
 * Brush teeth with Moss: a real two-minute brush (one minute at age two). The toothbrush makes
 * small gentle circles over one part of a big clay smile at a time while a soft scrub ticks along;
 * sleepy sugar bugs fade away as each part gets clean (or when tapped), Moss says where to go next,
 * and a flower opens petal by petal instead of a clock. At the end the teeth sparkle.
 */
@Composable
fun BrushTeeth(s: PlayScope) {
    val plan = remember { brushPlan(s.age) }
    val total = remember { plan.sumOf { it.seconds } }
    val bugs = remember {
        plan.flatMap { st ->
            val n = if (st.area == MouthArea.Tongue) 1 else 2
            bugSpots(st.area, n).mapIndexed { i, (x, y) -> Bug(st.area, i, n, x, y, (st.area.ordinal + i) % 2) }
        }
    }
    val tapped = remember { mutableStateListOf<Bug>() }
    var started by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var stepIndex by remember { mutableIntStateOf(0) }
    var stepProgress by remember { mutableFloatStateOf(0f) }
    var elapsed by remember { mutableIntStateOf(0) }
    // one shared ~12 Hz clock for every idle motion here: separate clocks would each add frames,
    // and the frame redraws the whole screen for every one of them
    val clock = rememberSlowClock()

    LaunchedEffect(Unit) {
        awaitQuiet(s)
        s.sayAndWait("brush_intro")
        if (!started) s.prompt("brush_ready")
    }
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        s.prompt() // nothing to repeat while brushing: the child isn't touching the screen, that's fine
        var mid = 0
        plan.forEachIndexed { i, st ->
            stepIndex = i
            stepProgress = 0f
            s.say(st.area.line)
            for (sec in 0 until st.seconds) {
                delay(1000)
                elapsed++
                stepProgress = (sec + 1f) / st.seconds
                s.sfx(Sound.Brush, 0.28f, if (sec % 2 == 0) 1f else 0.94f)
                if (st.seconds >= 16 && sec == st.seconds / 2) s.say(MID_LINES[mid++ % MID_LINES.size])
            }
        }
        done = true
        s.sfx(Sound.Sparkle, 0.6f)
        s.friendMood = dev.framio.meadow.ui.Mood.Happy
        s.practised("habit:brush")
        s.sayAndWait("brush_end")
        delay(1800)
        s.roundDone(true)
        s.finish()
    }

    Box(Modifier.offset(MOUTH_X.dp, MOUTH_Y.dp).size(MOUTH_W.dp, MOUTH_H.dp)) {
        Sprite("hb_mouth", Modifier.size(MOUTH_W.dp, MOUTH_H.dp))
        bugs.forEach { b ->
            val progress = when {
                done -> 1f
                !started -> 0f
                b.area.ordinal < plan[stepIndex].area.ordinal -> 1f
                b.area == plan[stepIndex].area -> stepProgress
                else -> 0f
            }
            val gone = b in tapped || bugGone(b.index, b.count, progress)
            SugarBug(b, gone, clock, onTap = {
                if (b !in tapped) {
                    tapped += b
                    s.sfx(Sound.Pop, 0.7f)
                    if (tapped.size == 1 || tapped.size % 4 == 0) s.say("brush_bye_bugs")
                }
            })
        }
        if (done) Sparkles(SPARKLE_SPOTS, Modifier.size(MOUTH_W.dp, MOUTH_H.dp), size = 20f, clock = clock)
    }
    if (started && !done) MovingBrush(plan[stepIndex].area, s.reducedMotion, clock)

    Bloom(elapsed, total, Modifier.offset(1000.dp, 120.dp).size(240.dp, 380.dp))

    if (!started) {
        SoftCard(
            Palette.mint.lighten(0.3f),
            Modifier
                .offset(985.dp, 360.dp)
                .size(250.dp, 170.dp)
                .hintPulse(s.hint)
                .pressable {
                    started = true
                    s.showHint(false)
                },
        ) {
            Sprite("hb_brush", Modifier.size(220.dp, 110.dp).floaty(2))
        }
    }
}

private val SPARKLE_SPOTS = listOf(0.3f to 0.2f, 0.52f to 0.17f, 0.7f to 0.26f, 0.38f to 0.8f, 0.62f to 0.78f, 0.84f to 0.4f, 0.17f to 0.5f)

@Composable
private fun SugarBug(b: Bug, gone: Boolean, clock: State<Long>, onTap: () -> Unit) {
    val a by animateFloatAsState(if (gone) 0f else 1f, tween(900, easing = FastOutSlowInEasing), label = "bug")
    if (a <= 0.01f && gone) return
    val size = if (b.area == MouthArea.Tongue) 88f else 74f
    Box(
        Modifier
            .offset((b.x * MOUTH_W - size / 2).dp, (b.y * MOUTH_H - size / 2).dp)
            .size(size.dp)
            .graphicsLayer {
                alpha = a
                val sc = 0.6f + 0.4f * a
                scaleX = sc
                scaleY = sc
                translationY = (1f - a) * -30f * density // floats away as it fades
            }
            .pressable(enabled = !gone, sound = null, onTap = onTap),
    ) {
        val phase = (b.area.ordinal * 2 + b.index) * 0.37f
        Sprite(
            if (b.variant == 0) "hb_bug" else "hb_bug2",
            Modifier.size(size.dp).graphicsLayer { translationY = sin((clock.value / 2900f + phase) * 2f * PI.toFloat()) * 2.5f * density },
        )
    }
}

/** The brush glides to the area, then makes small circles on it; bristles always face the teeth. */
@Composable
private fun MovingBrush(area: MouthArea, reduced: Boolean, clock: State<Long>) {
    val x = remember { Animatable(area.x) }
    val y = remember { Animatable(area.y) }
    LaunchedEffect(area) {
        val ease = tween<Float>(700, easing = FastOutSlowInEasing)
        coroutineScope {
            launch { x.animateTo(area.x, ease) }
            y.animateTo(area.y, ease)
        }
    }
    // the circles tick with the shared slow clock: calm, and cheap on the frame's GPU
    val w = 340f
    val h = 170f
    // pivot = the brush head on the sprite (head on the left, bristles up)
    val px = 0.16f
    val py = 0.42f
    val bottom = area == MouthArea.BottomA || area == MouthArea.BottomB
    val leftSide = area == MouthArea.TopA || area == MouthArea.BottomA
    val tilt = when (area) {
        MouthArea.Front -> -18f
        MouthArea.Tongue -> 22f
        else -> 0f
    }
    Box(
        Modifier
            .size(w.dp, h.dp)
            .graphicsLayer {
                val ang = (clock.value % 1000L) / 1000f * 2f * Math.PI.toFloat()
                val amp = if (reduced) 5f else 12f
                translationX = (MOUTH_X + x.value * MOUTH_W - px * w + cos(ang) * amp) * density
                translationY = (MOUTH_Y + y.value * MOUTH_H - py * h + (if (bottom) 30f else 22f) + sin(ang) * amp * 0.7f) * density
                transformOrigin = TransformOrigin(px, py)
                scaleX = if (leftSide) -1f else 1f
                scaleY = if (bottom) -1f else 1f
                rotationZ = tilt
            },
    ) {
        Sprite("hb_brush", Modifier.size(w.dp, h.dp))
    }
}

/** A flower that opens one petal at a time while you brush: how far along, without numbers. */
@Composable
private fun Bloom(elapsed: Int, total: Int, modifier: Modifier) {
    // one petal at a time: the flower only animates while a petal opens, not all the time
    val target = petalsOpen(elapsed, total, PETALS) / PETALS.toFloat()
    val bloom by animateFloatAsState(target, tween(700, easing = FastOutSlowInEasing), label = "bloom")
    val colors = listOf(Palette.rose, Palette.honey, Palette.lavender, Palette.sky)
    Canvas(modifier) {
        val cx = size.width / 2
        val cy = size.height * 0.36f
        val r = size.width * 0.36f
        // stem and two leaves
        drawLine(Palette.sage.darken(0.1f), Offset(cx, cy), Offset(cx, size.height), strokeWidth = 12.dp.toPx())
        rotate(-35f, Offset(cx, size.height * 0.78f)) {
            drawOval(Palette.sage, Offset(cx - 70.dp.toPx(), size.height * 0.78f - 16.dp.toPx()), Size(70.dp.toPx(), 32.dp.toPx()))
        }
        rotate(35f, Offset(cx, size.height * 0.68f)) {
            drawOval(Palette.sage.lighten(0.1f), Offset(cx, size.height * 0.68f - 14.dp.toPx()), Size(60.dp.toPx(), 28.dp.toPx()))
        }
        for (i in 0 until PETALS) {
            val open = (bloom * PETALS - i).coerceIn(0f, 1f)
            val angle = i * 360f / PETALS - 90f
            val c = colors[i % colors.size]
            rotate(angle, Offset(cx, cy)) {
                // a closed petal is a small pale bud; it grows out and takes its colour
                val len = r * (0.45f + 0.55f * open)
                val wid = r * (0.22f + 0.16f * open)
                drawOval(
                    if (open > 0f) c.copy(alpha = 0.35f + 0.65f * open) else Palette.paperDeep,
                    Offset(cx + r * 0.12f, cy - wid / 2), Size(len, wid),
                )
            }
        }
        drawCircle(Palette.honey.lighten(0.15f), r * 0.32f, Offset(cx, cy))
        drawCircle(Palette.honey, r * 0.32f, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
    }
}
