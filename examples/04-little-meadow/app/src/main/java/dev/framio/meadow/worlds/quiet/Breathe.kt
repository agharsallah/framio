package dev.framio.meadow.worlds.quiet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.framio.meadow.core.Friend
import dev.framio.meadow.core.Txt
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.FriendView
import dev.framio.meadow.ui.Mood
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.Type
import dev.framio.meadow.ui.floaty
import dev.framio.meadow.ui.hintPulse
import dev.framio.meadow.ui.pressable
import dev.framio.meadow.ui.rememberSlowClock
import kotlinx.coroutines.delay

/** The three breathing pictures. Narration: breathe_name_<id>, breathe_<id>_start/_in/_out. */
enum class BreathPic(val id: String, val title: Txt) {
    Flower("flower", Txt("Flower and candle", "Blume und Kerze")),
    Balloon("balloon", Txt("Balloon belly", "Ballonbauch")),
    Hand("hand", Txt("Starfish hand", "Seesternhand")),
}

/**
 * Breathe with Luma: pick a picture, then five slow breaths (about 4 s in, 5 s out). A big soft glow
 * grows and shrinks with a gentle colour shift, Luma breathes along, and the voice guides the first
 * breaths and then fades to "in… out…". Smell the flower and blow the candle, fill a balloon belly,
 * or trace the five fingers of a starfish hand (one finger per breath).
 */
@Composable
fun Breathe(s: PlayScope) {
    var pic by remember { mutableStateOf<BreathPic?>(null) }
    val timing = remember { breathTiming(s.level) }
    // The breath is a pure function of time on a ~25 Hz clock: slow motion needs no more, and the
    // frame (which redraws the whole screen for any change) stays steady instead of janky.
    val clock = rememberSlowClock(40)
    var breathAt by remember { mutableLongStateOf(-1L) }
    val breath = { if (breathAt < 0) 0f else breathLevel(clock.value - breathAt, timing) }
    val inhaling = { breathAt < 0 || clock.value - breathAt < timing.inMs + timing.holdMs }
    var finger by remember { mutableIntStateOf(0) }
    var done by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(s.app.voice.durationMs("breathe_intro").toLong() + 400)
        if (pic == null) s.prompt("breathe_pick")
    }
    LaunchedEffect(pic) {
        val p = pic ?: return@LaunchedEffect
        s.prompt() // nothing to repeat while breathing (and no idle reminders)
        s.sayAndWait("breathe_name_${p.id}")
        s.sayAndWait("breathe_${p.id}_start")
        delay(700)
        for (i in 0 until BREATHS) {
            finger = i
            val (inLine, outLine) = breathLines(p.id, i)
            breathAt = clock.value
            s.say(inLine)
            delay((timing.inMs + timing.holdMs).toLong())
            s.say(outLine)
            delay((timing.outMs + timing.restMs).toLong())
        }
        breathAt = -1L
        done = true
        s.practised("calm:breathe")
        delay(400)
        s.sayAndWait("breathe_end")
        s.roundDone(firstTry = true)
        s.finish()
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(pic == null, enter = fadeIn(tween(500)), exit = fadeOut(tween(500))) {
            Picker(s) { pic = it }
        }
        val p = pic
        if (p != null) {
            Stage(p, breath, inhaling, { finger }, Modifier.fillMaxSize())
        }
        // Luma breathes along, a little bigger while we breathe
        val lumaSize = if (p == null) 210.dp else 260.dp
        FriendView(
            Friend.Luma,
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 4.dp)
                .size(lumaSize)
                .graphicsLayer {
                    val b = breath()
                    scaleX = 1f + 0.05f * b
                    scaleY = 1f + 0.07f * b
                    transformOrigin = TransformOrigin(0.5f, 0.96f)
                },
            mood = if (done) Mood.Happy else Mood.Idle,
            onTap = { s.repeatPrompt() },
        )
    }
}

@Composable
private fun Picker(s: PlayScope, onPick: (BreathPic) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(Modifier.offset(y = (-20).dp), horizontalArrangement = Arrangement.spacedBy(44.dp)) {
            BreathPic.entries.forEachIndexed { i, p ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SoftCard(
                        Palette.cream,
                        Modifier
                            .size(280.dp)
                            .hintPulse(s.hint && i == 0)
                            .pressable { onPick(p) },
                    ) {
                        when (p) {
                            BreathPic.Flower -> Row(verticalAlignment = Alignment.Bottom) {
                                Sprite("ob_flower", Modifier.size(150.dp).floaty(1, 3.dp))
                                Sprite("qt_candlelit", Modifier.size(130.dp).offset(x = (-14).dp))
                            }
                            BreathPic.Balloon -> Sprite("qt_balloon", Modifier.size(220.dp).floaty(2, 4.dp))
                            BreathPic.Hand -> Sprite("qt_hand", Modifier.size(220.dp))
                        }
                    }
                    BasicText(p.title[s.lang], style = Type.label, modifier = Modifier.padding(top = 14.dp))
                }
            }
        }
    }
}

/** The breathing picture: everything reads [breath] in the draw phase only. */
@Composable
private fun Stage(p: BreathPic, breath: () -> Float, inhaling: () -> Boolean, finger: () -> Int, modifier: Modifier) {
    Box(modifier) {
        // the big soft glow: grows as we breathe in, lavender to a warm rose and back
        Canvas(Modifier.fillMaxSize()) {
            val b = breath()
            val c = Offset(size.width / 2, size.height * 0.48f)
            val r = size.height * (0.3f + 0.17f * b)
            val col = lerp(Palette.lavender, Color(0xFFF2B9A8), b)
            drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.8f), col.copy(alpha = 0.42f), col.copy(alpha = 0f)), c, r * 1.35f), r * 1.35f, c)
            drawCircle(Palette.cream.copy(alpha = 0.16f + 0.1f * b), r * 0.9f, c)
        }
        when (p) {
            BreathPic.Flower -> FlowerAndCandle(breath, inhaling)
            BreathPic.Balloon -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Sprite(
                    "qt_balloon",
                    Modifier
                        .offset(y = 10.dp)
                        .size(width = 340.dp, height = 425.dp)
                        .graphicsLayer {
                            val s = 0.62f + 0.42f * breath()
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(0.5f, 0.92f)
                        },
                )
            }
            BreathPic.Hand -> StarfishHand(breath, inhaling, finger)
        }
    }
}

@Composable
private fun FlowerAndCandle(breath: () -> Float, inhaling: () -> Boolean) {
    Box(Modifier.fillMaxSize()) {
        Sprite(
            "ob_flower",
            Modifier
                .align(Alignment.Center)
                .offset(x = (-170).dp, y = 10.dp)
                .size(280.dp)
                .graphicsLayer {
                    // the flower opens up as we smell it
                    val s = 0.9f + 0.16f * breath()
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(0.5f, 0.95f)
                },
        )
        Box(
            Modifier
                .align(Alignment.Center)
                .offset(x = 170.dp, y = 30.dp)
                .size(260.dp),
        ) {
            Sprite("qt_candle", Modifier.fillMaxSize())
            // the flame: bends away and dims a little as we blow, then stands up again
            Canvas(Modifier.fillMaxSize()) {
                val blow = if (inhaling()) 0f else (1f - breath()).let { 4f * it * (1f - it) }
                val base = Offset(size.width * CANDLE_WICK.first, size.height * CANDLE_WICK.second)
                drawFlame(base, size.width * 0.075f, size.height * (0.17f - 0.04f * blow), blow)
            }
        }
    }
}

/** Where the wick ends in qt_candle (fractions of the sprite), projected from the model. */
internal val CANDLE_WICK = 0.5f to 0.049f

/**
 * The trace around each finger of qt_hand, thumb to little finger, in fractions of the sprite: up one
 * side, round the tip, down the other. Projected from the model (tools/render3d/models/quiet.py,
 * HAND_FINGERS) with the renderer's own camera and crop.
 */
internal val HAND_PATHS: List<List<Pair<Float, Float>>> = listOf(
    listOf(0.337f to 0.6574f, 0.26f to 0.6164f, 0.1831f to 0.5755f, 0.1501f to 0.5579f, 0.1158f to 0.5191f, 0.1188f to 0.4708f, 0.1572f to 0.4413f, 0.2085f to 0.448f, 0.2415f to 0.4655f, 0.3185f to 0.5064f, 0.3955f to 0.5474f),
    listOf(0.4356f to 0.4298f, 0.3859f to 0.3183f, 0.3363f to 0.2067f, 0.315f to 0.1589f, 0.3123f to 0.1135f, 0.3405f to 0.0816f, 0.3831f to 0.0819f, 0.4151f to 0.1144f, 0.4364f to 0.1621f, 0.4861f to 0.2737f, 0.5357f to 0.3852f),
    listOf(0.5002f to 0.3867f, 0.4908f to 0.2518f, 0.4814f to 0.117f, 0.4774f to 0.0592f, 0.4904f to 0.0156f, 0.5278f to -0.0047f, 0.5677f to 0.0102f, 0.5867f to 0.0516f, 0.5907f to 0.1094f, 0.6002f to 0.2442f, 0.6096f to 0.379f),
    listOf(0.5743f to 0.3808f, 0.6084f to 0.2618f, 0.6425f to 0.1428f, 0.6571f to 0.0918f, 0.6843f to 0.0552f, 0.7264f to 0.0489f, 0.7588f to 0.0766f, 0.7625f to 0.122f, 0.7479f to 0.173f, 0.7137f to 0.292f, 0.6796f to 0.411f),
    listOf(0.6408f to 0.4074f, 0.6982f to 0.3284f, 0.7556f to 0.2494f, 0.7802f to 0.2156f, 0.8182f to 0.1905f, 0.86f to 0.199f, 0.8809f to 0.2361f, 0.8689f to 0.28f, 0.8443f to 0.3138f, 0.7869f to 0.3928f, 0.7295f to 0.4719f),
)


private fun DrawScope.drawFlame(base: Offset, w: Float, h: Float, lean: Float) {
    val tip = Offset(base.x + w * 1.6f * lean, base.y - h)
    val glow = Color(0xFFF8D58A)
    drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.45f - 0.15f * lean), glow.copy(alpha = 0f)), Offset(base.x, base.y - h * 0.4f), h * 1.2f), h * 1.2f, Offset(base.x, base.y - h * 0.4f))
    val path = Path().apply {
        moveTo(base.x - w, base.y - h * 0.15f)
        cubicTo(base.x - w, base.y + w * 0.9f, base.x + w, base.y + w * 0.9f, base.x + w, base.y - h * 0.15f)
        quadraticTo(base.x + w * 0.6f + w * lean, base.y - h * 0.6f, tip.x, tip.y)
        quadraticTo(base.x - w * 0.7f + w * lean, base.y - h * 0.55f, base.x - w, base.y - h * 0.15f)
        close()
    }
    drawPath(path, Brush.verticalGradient(listOf(Color(0xFFFBEFC8), Color(0xFFF6C66C), Color(0xFFEFA15E)), tip.y, base.y + w))
    drawCircle(Color(0xFFFFF8E6).copy(alpha = 0.85f), w * 0.42f, Offset(base.x + w * 0.2f * lean, base.y - h * 0.08f))
}

@Composable
private fun StarfishHand(breath: () -> Float, inhaling: () -> Boolean, finger: () -> Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.offset(y = 6.dp).size(520.dp)) {
            Sprite("qt_hand", Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val f = finger()
                val u = if (inhaling()) breath() * 0.5f else 0.5f + (1f - breath()) * 0.5f
                val trail = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                for (i in 0 until f) drawPolyline(HAND_PATHS[i], 1f, Palette.honey.copy(alpha = 0.45f), trail)
                val dot = drawPolyline(HAND_PATHS[f], u, Palette.honey.copy(alpha = 0.8f), trail)
                drawCircle(Brush.radialGradient(listOf(Color(0xFFFBE7B8).copy(alpha = 0.7f), Color(0x00FBE7B8)), dot, 40.dp.toPx()), 40.dp.toPx(), dot)
                drawCircle(Color(0xFFFFF6E2), 13.dp.toPx(), dot)
                drawCircle(Palette.honey, 13.dp.toPx(), dot, style = Stroke(3.dp.toPx()))
            }
        }
    }
}

/** Draws the first [upTo] (0..1, by length) of a polyline in fractions of the canvas; returns its end. */
private fun DrawScope.drawPolyline(pts: List<Pair<Float, Float>>, upTo: Float, color: Color, stroke: Stroke): Offset {
    val ps = pts.map { Offset(it.first * size.width, it.second * size.height) }
    val lens = ps.zipWithNext { a, b -> (b - a).getDistance() }
    val total = lens.sum()
    var left = total * upTo.coerceIn(0f, 1f)
    val path = Path().apply { moveTo(ps[0].x, ps[0].y) }
    var end = ps[0]
    for (i in lens.indices) {
        if (left <= 0f) break
        val step = minOf(left, lens[i])
        val t = if (lens[i] > 0f) step / lens[i] else 0f
        end = ps[i] + (ps[i + 1] - ps[i]) * t
        path.lineTo(end.x, end.y)
        left -= step
    }
    if (upTo > 0f) drawPath(path, color, style = stroke)
    return end
}

