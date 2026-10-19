package dev.framio.meadow.worlds.habits

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.Icon
import dev.framio.meadow.ui.Mood
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.RoundButton
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.rememberSlowClock
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// the hands' stage, in dp of the content area
private const val STAGE_X = 300f
private const val STAGE_Y = 150f
private const val STAGE_W = 700f
private const val STAGE_H = 520f
private const val HAND_W = 275f
private const val HAND_H = 330f

private class Bubble(val x: Float, val y: Float, val r: Float, val phase: Float)

/** Where one hand is at time [t] (0..1, looping), relative to the stage centre. */
private data class HandPose(val dx: Float, val dy: Float, val rot: Float = 0f, val back: Boolean = false, val mirror: Boolean = false, val scale: Float = 1f, val front: Boolean = false)

private fun poses(step: WashStep, t: Float, swap: Boolean): Pair<HandPose, HandPose> {
    val a = (t * 2 * PI).toFloat()
    val c = cos(a)
    val sn = sin(a)
    return when (step) {
        WashStep.Wet, WashStep.Rinse -> HandPose(-92f, 8f * sn, mirror = true) to HandPose(92f, -8f * sn)
        WashStep.Dry -> HandPose(-125f, 0f, mirror = true) to HandPose(125f, 0f)
        WashStep.Soap -> HandPose(-105f + 10f * c, 6f * sn, mirror = true) to HandPose(105f - 10f * c, -6f * sn)
        WashStep.Palms -> HandPose(-45f + 20f * c, 16f * sn, mirror = true) to HandPose(45f - 20f * c, -16f * sn, front = true)
        WashStep.Backs -> if (!swap) HandPose(-70f, 0f, mirror = true) to HandPose(40f + 30f * c, 10f + 6f * sn, back = true, front = true)
        else HandPose(70f, 0f) to HandPose(-40f - 30f * c, 10f + 6f * sn, back = true, mirror = true, front = true)
        WashStep.Between -> HandPose(-38f, 18f * sn, mirror = true) to HandPose(38f, -18f * sn, front = true)
        WashStep.Thumbs -> HandPose(-70f, 0f, mirror = true) to HandPose(55f, 10f, rot = -20f + 22f * c, back = true, front = true)
        WashStep.Nails -> HandPose(-60f, 0f, mirror = true) to HandPose(70f + 12f * c, 20f + 10f * sn, rot = -105f, back = true, scale = 0.85f, front = true)
    }
}

/**
 * Wash hands: the whole routine at the sink, wet, soap, five kinds of scrubbing, rinse, dry. Two
 * clay hands show each step with a gentle motion while Moss says what to do; bubbles build up as
 * we count to twenty together (the child can pop them), the water splashes, and each step moves on
 * by itself (or with the arrow).
 */
@Composable
fun WashHands(s: PlayScope) {
    val steps = WashStep.entries
    val counts = remember { scrubCounts() }
    var stepIndex by remember { mutableIntStateOf(-1) }
    var swap by remember { mutableStateOf(false) }
    val bubbles = remember { mutableStateListOf<Bubble>() }
    val rnd = remember { Random(System.nanoTime()) }
    var done by remember { mutableStateOf(false) }
    val step = steps.getOrNull(stepIndex)

    LaunchedEffect(Unit) {
        awaitQuiet(s)
        s.sayAndWait("wash_intro")
        stepIndex = 0
    }
    LaunchedEffect(stepIndex) {
        val st = steps.getOrNull(stepIndex) ?: return@LaunchedEffect
        s.rounds(stepIndex, steps.size)
        swap = false
        // said, not set as the prompt: the child is busy at the sink, not touching the screen, and the
        // idle re-prompt would nag (and pulse the ear button) every ten seconds
        s.say(st.line)
        when (st) {
            WashStep.Wet -> {
                s.sfx(Sound.Splash, 0.6f)
                delay(2600)
                s.sfx(Sound.Splash, 0.45f)
                delay(1600)
            }
            WashStep.Soap -> {
                delay(600)
                repeat(10) {
                    bubbles += newBubble(rnd)
                    if (it % 3 == 0) s.sfx(Sound.Pop, 0.35f, 1.2f)
                    delay(320)
                }
                delay(800)
            }
            WashStep.Rinse -> {
                s.sfx(Sound.Splash, 0.6f)
                delay(900)
                while (bubbles.isNotEmpty()) {
                    bubbles.removeAt(0)
                    delay(140)
                }
                s.sfx(Sound.Splash, 0.45f)
                delay(1500)
            }
            WashStep.Dry -> delay(4200)
            else -> {
                delay(300)
                waitVoice(s)
                val range = counts.getValue(st)
                for (n in range) {
                    s.say("num_$n")
                    if (bubbles.size < 26) bubbles += newBubble(rnd)
                    if (n == range.first + range.count() / 2) swap = true
                    delay(1050)
                }
                delay(300)
            }
        }
        if (stepIndex + 1 < steps.size) stepIndex++ else {
            done = true
            s.rounds(steps.size, steps.size)
            s.friendMood = Mood.Happy
            s.sfx(Sound.Sparkle, 0.6f)
            s.practised("habit:wash")
            s.sayAndWait("wash_done")
            s.roundDone(true)
            delay(1200)
            s.finish()
        }
    }

    // one shared ~12 Hz clock drives the hands, water and bubbles (the frame redraws the whole
    // screen for every frame, so separate clocks would multiply the work); read in the draw phase
    val clock = rememberSlowClock()
    val period = if (s.reducedMotion) 2400L else 1500L

    // the tap and its water
    Sprite("hb_tap", Modifier.offset(470.dp, (-30).dp).size(230.dp))
    val water by animateFloatAsState(if (step == WashStep.Wet || step == WashStep.Rinse) 1f else 0f, tween(500), label = "water")
    WaterStream(water, clock, Modifier.offset(623.dp, 172.dp).size(64.dp, 250.dp))

    Box(Modifier.offset(STAGE_X.dp, STAGE_Y.dp).size(STAGE_W.dp, STAGE_H.dp)) {
        if (step != null) {
            for (left in listOf(true, false)) {
                val front = poses(step, 0f, swap).let { if (left) it.first.front else it.second.front }
                Box(
                    Modifier
                        .zIndex(if (front) 2f else 1f)
                        .offset(((STAGE_W - HAND_W) / 2).dp, ((STAGE_H - HAND_H) / 2).dp)
                        .size(HAND_W.dp, HAND_H.dp)
                        .graphicsLayer {
                            val t = (clock.value % period) / period.toFloat()
                            val p = poses(step, t, swap).let { if (left) it.first else it.second }
                            translationX = p.dx * density
                            translationY = p.dy * density
                            rotationZ = p.rot
                            transformOrigin = TransformOrigin(0.5f, 0.8f)
                            scaleX = p.scale * if (p.mirror) -1f else 1f
                            scaleY = p.scale
                        },
                ) {
                    val back = poses(step, 0f, swap).let { if (left) it.first.back else it.second.back }
                    Sprite(if (back) "hb_hand_back" else "hb_hand_palm", Modifier.size(HAND_W.dp, HAND_H.dp))
                }
            }
            if (step == WashStep.Soap) SoapBar(Modifier.zIndex(3f).offset(270.dp, 300.dp).size(160.dp))
            if (step == WashStep.Dry) Towel(clock, Modifier.zIndex(3f).offset(180.dp, 60.dp).size(340.dp))
        }
        Bubbles(bubbles, clock, Modifier.zIndex(4f).size(STAGE_W.dp, STAGE_H.dp)) { b ->
            bubbles.remove(b)
            s.sfx(Sound.Pop, 0.8f, 0.9f + rnd.nextFloat() * 0.3f)
        }
        if (done) Sparkles(listOf(0.3f to 0.3f, 0.5f to 0.2f, 0.7f to 0.35f, 0.4f to 0.6f, 0.62f to 0.66f), Modifier.zIndex(5f).size(STAGE_W.dp, STAGE_H.dp), clock = clock)
    }

    if (step != null && !done) {
        RoundButton(Icon.Next, Modifier.offset(1110.dp, 470.dp), size = 110.dp, color = Palette.sage, iconColor = Palette.cream) {
            if (stepIndex + 1 < steps.size) stepIndex++
        }
    }
}

private suspend fun waitVoice(s: PlayScope) {
    // let the step's line finish before counting starts
    var waited = 0
    while (s.app.voice.speaking.value && waited < 4000) {
        delay(100)
        waited += 100
    }
}

private fun newBubble(r: Random): Bubble {
    val a = r.nextFloat() * 2 * PI
    val d = 60f + r.nextFloat() * 170f
    return Bubble(
        STAGE_W / 2 + (cos(a) * d * 1.25f).toFloat(), STAGE_H / 2 + 30f + (sin(a) * d * 0.75f).toFloat(),
        14f + r.nextFloat() * 22f, r.nextFloat(),
    )
}

/** Soap bubbles: pale, see-through, a little shine; they sway slowly and pop when tapped. */
@Composable
private fun Bubbles(list: List<Bubble>, clock: State<Long>, modifier: Modifier, onPop: (Bubble) -> Unit) {
    Canvas(
        modifier.pointerInput(Unit) {
            detectTapGestures { pos ->
                val dp = density
                val hit = list.minByOrNull { b -> (Offset(b.x * dp, b.y * dp) - pos).getDistance() - b.r * dp }
                if (hit != null && (Offset(hit.x * dp, hit.y * dp) - pos).getDistance() < (hit.r + 34f) * dp) onPop(hit)
            }
        },
    ) {
        val p = (clock.value % 4000L) / 4000f
        for (b in list) {
            val sway = sin(((p + b.phase) * 2 * PI).toFloat()) * 4f
            val c = Offset((b.x + sway) * density, (b.y - sway * 0.5f) * density)
            val r = b.r * density
            // two fills only: stroked rings were the costly part on the Mali-400
            drawCircle(Color(0x88F4F9FC), r, c)
            drawCircle(Color(0xDDFFFFFF), r * 0.24f, c + Offset(-r * 0.36f, -r * 0.36f))
        }
    }
}

/** A soft stream of water from the tap; it fades in and out and its shine slides down. */
@Composable
private fun WaterStream(amount: Float, clock: State<Long>, modifier: Modifier) {
    if (amount <= 0.01f) return
    Canvas(modifier) {
        val p = (clock.value % 900L) / 900f
        val w = size.width * 0.55f
        val x = (size.width - w) / 2
        drawRoundRect(Color(0xFFA9D3E6).copy(alpha = 0.55f * amount), Offset(x, 0f), Size(w, size.height), CornerRadius(w / 2))
        for (i in 0 until 4) {
            val y = ((p + i / 4f) % 1f) * size.height
            drawRoundRect(Color.White.copy(alpha = 0.5f * amount), Offset(x + w * 0.25f, y), Size(w * 0.18f, size.height * 0.12f), CornerRadius(w))
        }
        // a few drops where it lands
        for (i in 0 until 3) {
            val q = (p + i / 3f) % 1f
            drawCircle(Color(0xFFA9D3E6).copy(alpha = (1f - q) * 0.7f * amount), 6f * density, Offset(size.width / 2 + (i - 1) * 26f * density * q, size.height - 10f * density - q * 30f * density))
        }
    }
}

@Composable
private fun SoapBar(modifier: Modifier) {
    val a by animateFloatAsState(1f, tween(500, easing = FastOutSlowInEasing), label = "soap")
    Sprite("ob_soap", modifier.graphicsLayer { alpha = a })
}

/** The towel pats the left hand, then the right. */
@Composable
private fun Towel(clock: androidx.compose.runtime.State<Long>, modifier: Modifier) {
    Box(
        modifier.graphicsLayer {
            val t = (clock.value % 3000L) / 3000f
            val side = if (t < 0.5f) -1f else 1f
            val pat = sin(t * 4 * PI).toFloat()
            translationX = side * 120f * density
            translationY = (20f + pat * 14f) * density
            rotationZ = side * 6f
        },
    ) { Sprite("hb_towel", Modifier.size(340.dp)) }
}
