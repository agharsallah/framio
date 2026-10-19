package dev.framio.meadow.worlds.colors

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.Mood
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.Sprites
import dev.framio.meadow.ui.Type
import dev.framio.meadow.ui.darken
import dev.framio.meadow.ui.halo
import dev.framio.meadow.ui.hintPulse
import dev.framio.meadow.ui.lighten
import dev.framio.meadow.ui.pressable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private enum class MixPhase { Pick, Pouring, Paint, Painted }

/** The paint surface inside the co_bowl sprite, as fractions of its box (tuned on the frame). */
private const val PAINT_CX = 0.485f
private const val PAINT_CY = 0.40f
private const val PAINT_RX = 0.345f
private const val PAINT_RY = 0.155f
private val BOWL_W = 470.dp
private val BOWL_H = 352.dp
private val POT = 176.dp

/**
 * Mixing colors: paint pots and a big bowl. Tap two pots: each pours in, the paints swirl slowly
 * together and Tuka names the new colour ("Red and yellow make orange!"). Then the child paints a
 * waiting picture with it. Any two pots make something, so there are no wrong answers, only new
 * colours; each new mix lands on the recipe shelf, and four discoveries finish. Black joins the
 * pots at the top level.
 */
@Composable
fun MixColors(s: PlayScope) {
    val pots = remember { mixPots(s.level) }
    val discovered = remember { mutableStateListOf<Mix>() }
    var phase by remember { mutableStateOf(MixPhase.Pick) }
    var first by remember { mutableStateOf<Pot?>(null) }
    var current by remember { mutableStateOf<Mix?>(null) }
    var wasNew by remember { mutableStateOf(false) }
    var thing by remember { mutableIntStateOf(0) }
    var painted by remember { mutableStateOf<Long?>(null) }
    val co = rememberCoroutineScope()

    // the bowl: how full (0..1), the first paint, the second paint and how far they have swirled together
    val level = remember { Animatable(0f) }
    val swirl = remember { Animatable(0f) }
    var paintA by remember { mutableStateOf(0L) }
    var paintB by remember { mutableStateOf<Long?>(null) }
    var stream by remember { mutableStateOf<Long?>(null) }
    val streamLen = remember { Animatable(0f) }
    val pours = remember { pots.associateWith { Animatable(0f) } }
    val reveal = remember { Animatable(1f) }
    var revealAt by remember { mutableStateOf(Offset.Zero) }
    var bowlRect by remember { mutableStateOf(Rect.Zero) }
    val potRects = remember { HashMap<Pot, Rect>() }
    var firstTouch by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        Sprites.preload(listOf("co_bowl") + pots.map { it.sprite })
        s.rounds(0, MIX_GOAL)
        s.waitForIntro(0)
        s.prompt("mix_pick")
    }

    suspend fun pour(p: Pot, fillTo: Float, mixing: Boolean) {
        val a = pours.getValue(p)
        a.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        stream = p.argb
        s.sfx(Sound.Splash, 0.45f, 1.2f)
        streamLen.animateTo(1f, tween(260))
        coroutineScope {
            launch { level.animateTo(fillTo, tween(900, easing = FastOutSlowInEasing)) }
            if (mixing) launch { swirl.animateTo(1f, tween(2600, easing = LinearEasing)) }
            delay(700)
            streamLen.animateTo(0f, tween(220))
            stream = null
            a.animateTo(0f, tween(600, easing = FastOutSlowInEasing))
        }
    }

    fun potTapped(p: Pot) {
        if (phase != MixPhase.Pick) return
        val a = first
        when {
            a == null -> {
                first = p
                s.say(p.line)
                if (firstTouch) co.launch {
                    delay(900)
                    if (first == p && phase == MixPhase.Pick) s.say("mix_more")
                }
                firstTouch = false
            }
            a == p -> first = null
            else -> {
                phase = MixPhase.Pouring
                val mix = mixOf(a, p)
                current = mix
                co.launch {
                    s.say(p.line)
                    paintA = a.argb
                    paintB = null
                    swirl.snapTo(0f)
                    pour(a, 0.55f, mixing = false)
                    paintB = p.argb
                    pour(p, 1f, mixing = true)
                    swirl.animateTo(1f, tween(400))
                    first = null
                    s.sfx(Sound.Sparkle, 0.7f)
                    s.friendMood = Mood.Happy
                    s.sayAndWait(mix.line)
                    wasNew = discovered.none { it.key == mix.key }
                    if (wasNew) {
                        discovered += mix
                        s.practised("mix:${mix.name}")
                    }
                    phase = MixPhase.Paint
                    s.prompt("mix_paint")
                }
            }
        }
    }

    fun paint(at: Offset) {
        if (phase != MixPhase.Paint) return
        val mix = current ?: return
        phase = MixPhase.Painted
        revealAt = at
        painted = mix.argb
        s.sfx(Sound.Brush)
        co.launch {
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
            s.praiseAndWait()
            if (wasNew) {
                s.roundDone(firstTry = true)
                s.rounds(discovered.size, MIX_GOAL)
            }
            delay(700)
            if (discovered.size >= MIX_GOAL) {
                s.finish()
                return@launch
            }
            level.animateTo(0f, tween(700))
            swirl.snapTo(0f)
            paintB = null
            painted = null
            thing = (thing + 1) % MIX_THINGS.size
            current = null
            phase = MixPhase.Pick
            if (wasNew) s.prompt("mix_pick") else s.prompt("mix_new", "mix_pick")
        }
    }

    // a hint pot: one that makes something new with the pot already chosen (or any two to start)
    val hintPots: Set<Pot> = if (!s.hint || phase != MixPhase.Pick) emptySet() else {
        val a = first
        if (a == null) pots.take(2).toSet()
        else setOfNotNull(pots.firstOrNull { it != a && discovered.none { d -> d.key == mixOf(a, it).key } } ?: pots.first { it != a })
    }

    Box(Modifier.fillMaxSize()) {
        RecipeShelf(discovered, MIX_GOAL, Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 6.dp)) { s.say(it.line) }

        // the bowl, its paint and the pouring stream
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset(x = 268.dp, y = 66.dp)
                .size(BOWL_W, BOWL_H)
                .onGloballyPositioned { bowlRect = it.boundsInRoot() },
        ) {
            Sprite("co_bowl", Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val st = stream
                if (st != null && streamLen.value > 0f) {
                    val x = w * 0.30f + 56.dp.toPx()
                    val top = -h * 0.02f - 26.dp.toPx()
                    val bottom = top + (h * PAINT_CY - top) * streamLen.value
                    drawRoundRect(Color(st), Offset(x - 11.dp.toPx(), top), Size(22.dp.toPx(), bottom - top), CornerRadius(11.dp.toPx()))
                }
                val lv = level.value
                if (lv <= 0.01f) return@Canvas
                val rx = w * PAINT_RX * (0.55f + 0.45f * lv)
                val ry = h * PAINT_RY * (0.55f + 0.45f * lv)
                val cx = w * PAINT_CX
                val cy = h * (PAINT_CY + 0.07f * (1f - lv))
                val t = swirl.value
                val mix = current
                val b = paintB
                val base = if (b != null && mix != null) blendArgb(paintA, mix.argb, t) else paintA
                drawOval(Color(base), Offset(cx - rx, cy - ry), Size(rx * 2, ry * 2))
                if (b != null && mix != null && t < 1f) {
                    // a slow spiral of the second paint, melting into the new colour
                    val spiral = Path()
                    val turns = 2.2f
                    val steps = 60
                    val rot = t * 3.2f * PI.toFloat()
                    for (k in 0..steps) {
                        val f = k / steps.toFloat()
                        val ang = rot + f * turns * 2f * PI.toFloat()
                        val px = cx + cos(ang) * rx * 0.86f * f
                        val py = cy + sin(ang) * ry * 0.86f * f
                        if (k == 0) spiral.moveTo(px, py) else spiral.lineTo(px, py)
                    }
                    val oval = Path().apply { addOval(Rect(Offset(cx - rx, cy - ry), Size(rx * 2, ry * 2))) }
                    clipPath(oval) {
                        drawPath(spiral, Color(blendArgb(b, mix.argb, t)).copy(alpha = 1f - t * 0.6f), style = Stroke(ry * 0.36f * (1f - t * 0.5f), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    }
                }
                // a soft shine on the paint
                drawOval(Color.White.copy(alpha = 0.22f), Offset(cx - rx * 0.55f, cy - ry * 0.7f), Size(rx * 0.5f, ry * 0.35f))
            }
        }

        // the picture waiting to be painted
        val art = remember(thing) { PageArt(MIX_THINGS[thing].page, outline = 20f) }
        val ready = phase == MixPhase.Paint
        SoftCard(
            Palette.cream,
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-30).dp, y = 34.dp)
                .size(390.dp, 300.dp)
                .hintPulse(ready, Palette.honey, 40.dp)
                .pointerInput(Unit) { detectTapGestures { paint(it) } },
        ) {
            Canvas(Modifier.fillMaxSize().padding(22.dp)) {
                val sc = minOf(size.width / PAGE_W, size.height / PAGE_H)
                val ox = (size.width - PAGE_W * sc) / 2
                val oy = (size.height - PAGE_H * sc) / 2
                val col = (painted ?: 0xFFFFFDF8).toInt()
                val r = reveal.value
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    nc.save()
                    nc.translate(ox, oy)
                    nc.scale(sc, sc)
                    val rv = if (r < 1f) Reveal(0, PAPER_ARGB, (revealAt.x - 22.dp.toPx() - ox) / sc, (revealAt.y - 22.dp.toPx() - oy) / sc, r) else null
                    art.draw(nc, { col }, rv)
                    nc.restore()
                }
            }
        }

        // the pots
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 230.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(if (pots.size > 4) 18.dp else 34.dp),
        ) {
            pots.forEach { p ->
                val pour = pours.getValue(p)
                val chosen = first == p
                val lift by animateFloatAsState(if (chosen) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow), label = "lift")
                Box(
                    Modifier
                        .size(POT)
                        .onGloballyPositioned { potRects[p] = it.boundsInRoot() }
                        .graphicsLayer {
                            val v = pour.value
                            val home = potRects[p] ?: Rect.Zero
                            val tx = bowlRect.left + bowlRect.width * 0.30f - home.center.x
                            val ty = bowlRect.top - bowlRect.height * 0.02f - home.center.y
                            translationX = tx * v
                            translationY = ty * v - lift * 26f * density
                            rotationZ = 62f * ((v - 0.45f) / 0.55f).coerceIn(0f, 1f)
                        }
                        .halo(chosen, Palette.honey, POT / 2)
                        .hintPulse(p in hintPots, Palette.honey, POT / 2)
                        .pressable(enabled = phase == MixPhase.Pick, sound = Sound.Pick) { potTapped(p) },
                ) {
                    Sprite(p.sprite, Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** The discovered mixes, one recipe per line: ● + ● = ●. Tapping one says it again. */
@Composable
private fun RecipeShelf(found: List<Mix>, goal: Int, modifier: Modifier, onTap: (Mix) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (i in 0 until goal) {
            val m = found.getOrNull(i)
            val pop = remember(m?.key) { Animatable(if (m == null) 1f else 0f) }
            LaunchedEffect(m?.key) { if (m != null) pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)) }
            SoftCard(
                if (m == null) Palette.paperDeep else Palette.cream,
                Modifier
                    .size(212.dp, 90.dp)
                    .graphicsLayer { scaleX = 0.85f + 0.15f * pop.value; scaleY = 0.85f + 0.15f * pop.value }
                    .then(if (m != null) Modifier.pressable { onTap(m) } else Modifier),
                corner = 30.dp,
                elevation = if (m == null) 0.dp else 4.dp,
            ) {
                if (m == null) {
                    Canvas(Modifier.size(56.dp)) { drawCircle(Palette.ink.copy(alpha = 0.12f), style = Stroke(4.dp.toPx())) }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Blob(Color(m.a.argb), 36.dp)
                        BasicText("+", style = Type.clay(26.sp, Palette.inkSoft))
                        Blob(Color(m.b.argb), 36.dp)
                        BasicText("=", style = Type.clay(26.sp, Palette.inkSoft))
                        Blob(Color(m.argb), 52.dp)
                    }
                }
            }
        }
    }
}

/** A round dab of paint with a soft shine. */
@Composable
private fun Blob(c: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        drawCircle(c.darken(0.12f), radius = this.size.minDimension / 2)
        drawCircle(c, radius = this.size.minDimension / 2 - 2.dp.toPx(), center = center - Offset(0f, 1.5f.dp.toPx()))
        drawCircle(c.lighten(0.45f).copy(alpha = 0.7f), radius = this.size.minDimension * 0.12f, center = center - Offset(this.size.width * 0.16f, this.size.height * 0.18f))
    }
}
