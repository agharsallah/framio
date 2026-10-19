package dev.framio.meadow.worlds.numbers

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.play.ChoiceRow
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.Type
import dev.framio.meadow.ui.floaty
import dev.framio.meadow.ui.softShadow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.random.Random

/**
 * How many? A little picture (ducks on a pond, apples on a branch, stars in the night sky…) and
 * three answer cards with the numeral and a dice or ten-frame dot pattern, so even non-readers can
 * match the amount at a glance (subitising). After the right card, Pip counts the things aloud one
 * by one while each gets a soft glow and its number.
 */
@Composable
fun HowMany(s: PlayScope) {
    val total = 5
    var round by remember { mutableIntStateOf(0) }
    val lastScene = remember { arrayOfNulls<HowManyScene>(1) } // not state: only read when a round is built
    val spec = remember(round) {
        howManyRound(s.level, Random(round * 7919 + System.nanoTime().toInt()), lastScene[0]).also { lastScene[0] = it.scene }
    }
    // how many things have been counted aloud so far in this round (the reinforcement after the answer)
    var counted by remember(round) { mutableIntStateOf(0) }
    val co = rememberCoroutineScope()

    LaunchedEffect(round) {
        s.rounds(round, total)
        if (round == 0) s.awaitQuiet() else delay(300)
        s.prompt(spec.scene.ask)
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(2.dp))
        ScenePanel(spec, counted, round, Modifier.size(960.dp, 392.dp))
        Spacer(Modifier.height(22.dp))
        key(round) { ChoiceRow( // keyed: two rounds can have equal choice lists
            s, spec.choices, isRight = { it == spec.n },
            cardSize = 210.dp, spacing = 40.dp,
            onRight = { _, first ->
                co.launch {
                    s.sayAndWait("howmany_together")
                    for (k in 1..spec.n) {
                        counted = k
                        s.sfx(Sound.Ding, 0.6f, 2.0.pow((k - 1).coerceAtMost(12) / 12.0).toFloat())
                        s.sayAndWait("num_$k")
                    }
                    delay(200)
                    s.sayAndWait("thats_${spec.n}")
                    s.practised("count:${spec.n}")
                    s.roundDone(first)
                    delay(500)
                    if (round + 1 >= total) {
                        s.rounds(total, total)
                        s.finish()
                    } else round++
                }
            },
        ) { n -> NumberFace(n, spec.dots) } }
    }
}

/** An answer card: the numeral, and under it the dots (until the top level). */
@Composable
private fun NumberFace(n: Int, dots: Boolean) {
    if (!dots) {
        BasicText("$n", style = Type.clay(112.sp, Palette.ink))
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        BasicText("$n", style = Type.clay(64.sp, Palette.ink))
        Spacer(Modifier.height(4.dp))
        if (n > 6) DotPattern(n, Modifier.size(170.dp, 84.dp)) else DotPattern(n, Modifier.size(100.dp))
    }
}

/** The picture: a soft rounded window onto a tiny scene with [HowManyRound.n] things in it. */
@Composable
private fun ScenePanel(spec: HowManyRound, counted: Int, round: Int, modifier: Modifier) {
    BoxWithConstraints(
        modifier
            .softShadow(44.dp, 6.dp)
            .drawWithCache {
                // painted once into a bitmap with its rounded corners: no stencil clip on the GPU every frame
                val bmp = ImageBitmap(size.width.toInt(), size.height.toInt())
                CanvasDrawScope().draw(this, layoutDirection, Canvas(bmp), size) {
                    clipPath(Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(44.dp.toPx()))) }) {
                        drawSceneBackground(spec.scene)
                    }
                }
                onDrawBehind { drawImage(bmp) }
            },
    ) {
        val w = maxWidth
        val h = maxHeight
        val item: Dp = when {
            spec.n <= 3 -> 150.dp
            spec.n <= 5 -> 128.dp
            else -> 104.dp
        }
        val rows = if (spec.n <= 5) 1 else 2
        SceneDecor(spec.scene, rows, w, h)
        val spots = remember(round) { sceneSpots(spec.scene, spec.n, round * 31 + spec.n) }
        spots.forEachIndexed { i, (fx, fy) ->
            Thing(
                sprite = "ob_${spec.scene.thing}",
                number = if (i < counted) i + 1 else null,
                current = i == counted - 1,
                appearDelay = 120L * i,
                seed = i + round * 17,
                float = spec.n <= 6, // a few bobbing things are alive, ten would be busy
                modifier = Modifier
                    .offset(w * fx - item / 2, h * fy - item / 2)
                    .size(item),
            )
        }
    }
}

/** Branches for the apples to hang from: scenery that is never counted. */
@Composable
private fun SceneDecor(scene: HowManyScene, rows: Int, w: Dp, h: Dp) {
    when (scene) {
        HowManyScene.Branch -> {
            val ys = if (rows == 1) listOf(0.2f) else listOf(0.08f, 0.55f)
            for (y in ys) Sprite("nu_branch", Modifier.offset(w * 0.015f, h * y - 64.dp).size(w * 0.97f, 128.dp))
        }
        else -> Unit
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSceneBackground(scene: HowManyScene) {
    val w = size.width
    val h = size.height
    fun ground(top: Float, c1: Color, c2: Color) {
        drawRect(Brush.verticalGradient(listOf(c1, c2), startY = h * top, endY = h), Offset(0f, h * top), Size(w, h * (1 - top)))
    }
    when (scene) {
        HowManyScene.Pond -> {
            ground(0f, Color(0xFFDDEBCB), Color(0xFFC9DFB2))
            drawOval(
                Brush.verticalGradient(listOf(Color(0xFFB9D6EA), Color(0xFF9EC3DF)), startY = h * 0.2f, endY = h * 0.95f),
                Offset(w * 0.04f, h * 0.2f), Size(w * 0.92f, h * 0.74f),
            )
            drawOval(Color.White.copy(alpha = 0.22f), Offset(w * 0.2f, h * 0.28f), Size(w * 0.3f, h * 0.06f))
            drawOval(Color.White.copy(alpha = 0.16f), Offset(w * 0.55f, h * 0.84f), Size(w * 0.22f, h * 0.04f))
            for (x in floatArrayOf(0.05f, 0.08f, 0.93f, 0.96f)) { // reeds at the edge
                drawRoundRect(Color(0xFF8DB48A), Offset(w * x, h * 0.08f), Size(7f, h * 0.3f), CornerRadius(4f))
                drawOval(Color(0xFFA9785A), Offset(w * x - 6f, h * 0.06f), Size(19f, h * 0.11f))
            }
        }
        HowManyScene.Branch -> {
            drawRect(Brush.verticalGradient(listOf(Color(0xFFE3EEF3), Color(0xFFF4EFE2))))
            ground(0.86f, Color(0xFFD5E6C2), Color(0xFFC6DDB0))
        }
        HowManyScene.Night -> {
            drawRect(Brush.verticalGradient(listOf(Palette.nightDeep, Palette.night, Color(0xFF45477A))))
            val r = Random(5)
            repeat(26) { // tiny far-away twinkles, much smaller than the stars to count
                drawCircle(Palette.moon.copy(alpha = 0.25f + r.nextFloat() * 0.3f), 1.6f + r.nextFloat() * 1.6f, Offset(r.nextFloat() * w, r.nextFloat() * h))
            }
        }
        HowManyScene.Meadow -> {
            drawRect(Brush.verticalGradient(listOf(Color(0xFFDCEBF1), Color(0xFFF2F0E3)), endY = h * 0.45f))
            drawOval(Color(0xFFD9E8C5), Offset(-w * 0.1f, h * 0.24f), Size(w * 0.7f, h * 0.4f))
            drawOval(Color(0xFFD2E4BC), Offset(w * 0.45f, h * 0.27f), Size(w * 0.7f, h * 0.4f))
            ground(0.4f, Color(0xFFCBE0B2), Color(0xFFB8D49C))
        }
        HowManyScene.Sky -> {
            drawRect(Brush.verticalGradient(listOf(Color(0xFFCFE3EF), Color(0xFFEFF2EA))))
            // faint far-away clouds, flat and pale so they never look like something to count
            for ((cx, cy, cw) in listOf(Triple(0.16f, 0.13f, 0.2f), Triple(0.7f, 0.9f, 0.26f), Triple(0.9f, 0.12f, 0.14f))) {
                val c = Color.White.copy(alpha = 0.55f)
                drawOval(c, Offset(w * (cx - cw / 2), h * cy - w * cw * 0.12f), Size(w * cw, w * cw * 0.24f))
                drawOval(c, Offset(w * (cx - cw * 0.25f), h * cy - w * cw * 0.26f), Size(w * cw * 0.45f, w * cw * 0.3f))
            }
        }
        HowManyScene.Forest -> {
            drawRect(Brush.verticalGradient(listOf(Color(0xFFE5EEDC), Color(0xFFDCE7CC)), endY = h * 0.4f))
            for (i in 0..4) { // soft trees far behind: trunks and round crowns
                val x = w * (0.1f + i * 0.2f)
                drawRoundRect(Color(0xFFCDBBA5).copy(alpha = 0.6f), Offset(x - w * 0.015f, h * 0.12f), Size(w * 0.03f, h * 0.3f), CornerRadius(12f))
                drawCircle(Color(0xFFC6DDB2), h * 0.15f, Offset(x, h * 0.12f))
                drawCircle(Color(0xFFBCD6A6), h * 0.11f, Offset(x + h * 0.1f, h * 0.18f))
            }
            ground(0.36f, Color(0xFFC3D9A8), Color(0xFFAECB92))
        }
    }
}

@Composable
private fun Thing(sprite: String, number: Int?, current: Boolean, appearDelay: Long, seed: Int, float: Boolean, modifier: Modifier) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(appearDelay)
        enter.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    val glow = remember { Animatable(0f) }
    val hop = remember { Animatable(0f) }
    LaunchedEffect(number != null) {
        if (number != null) {
            launch { glow.animateTo(1f, tween(350)) }
            hop.animateTo(-18f, tween(170, easing = FastOutSlowInEasing))
            hop.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow))
        }
    }
    Box(
        modifier
            .drawBehind {
                val g = glow.value
                if (g > 0f) {
                    drawCircle(
                        Brush.radialGradient(listOf(Palette.honey.copy(alpha = 0.55f * g), Palette.honey.copy(alpha = 0f)), radius = size.minDimension * 0.62f),
                        size.minDimension * 0.62f,
                    )
                }
            }
            .graphicsLayer {
                alpha = enter.value
                val sc = (0.8f + 0.2f * enter.value) * (if (current) 1.06f else 1f)
                scaleX = sc
                scaleY = sc
                translationY = hop.value * density
            },
        contentAlignment = Alignment.Center,
    ) {
        Sprite(sprite, Modifier.fillMaxSize().padding(6.dp).floaty(seed, 3.dp, enabled = float))
        if (number != null) {
            SoftCard(Palette.honey, Modifier.align(Alignment.TopEnd).size(52.dp), corner = 26.dp, elevation = 3.dp) {
                BasicText("$number", style = Type.clay(28.sp, Palette.cream))
            }
        }
    }
}
