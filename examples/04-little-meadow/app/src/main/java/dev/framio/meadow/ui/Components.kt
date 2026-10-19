package dev.framio.meadow.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.framio.meadow.LocalApp
import dev.framio.meadow.audio.Sound
import kotlin.math.sin
import kotlin.random.Random

fun Color.lighten(f: Float) = lerp(this, Color.White, f)
fun Color.darken(f: Float) = lerp(this, Color(0xFF2A2030), f)

/**
 * Tap with a soft squish while pressed. One tap per 350 ms (toddlers double-tap and lean their
 * palm on the screen); [sound] plays on tap, null for silence.
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    sound: Sound? = Sound.Tap,
    scaleTo: Float = 0.94f,
    onTap: () -> Unit,
): Modifier = composed {
    val app = LocalApp.current
    var pressed by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (pressed) scaleTo else 1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "press")
    var last by remember { mutableLongStateOf(0L) }
    val tap by rememberUpdatedState(onTap)
    this
        .graphicsLayer { scaleX = s; scaleY = s }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                },
                onTap = {
                    val now = SystemClock.uptimeMillis()
                    if (now - last > 350) {
                        last = now
                        sound?.let(app.sfx::play)
                        tap()
                    }
                },
            )
        }
}

/** A soft ring that breathes outwards: "look here". Drawn behind, so it never moves the content. */
fun Modifier.hintPulse(active: Boolean, color: Color = Palette.honey, corner: Dp = 40.dp): Modifier = composed {
    if (!active) return@composed this
    // ~25 Hz is smooth enough for a slow ring and redraws the (whole) screen less than half as often
    val clock = rememberSlowClock(stepMs = 40)
    drawBehind {
        val p = (clock.value % 1500L) / 1500f
        val grow = 22.dp.toPx() * p
        val r = corner.toPx()
        drawRoundRect(
            color.copy(alpha = 0.32f * (1f - p)), Offset(-grow, -grow), Size(size.width + 2 * grow, size.height + 2 * grow),
            CornerRadius(r + grow), style = Stroke(10.dp.toPx() * (1f - p * 0.6f)),
        )
        drawRoundRect(color.copy(alpha = 0.16f), Offset(-6.dp.toPx(), -6.dp.toPx()), Size(size.width + 12.dp.toPx(), size.height + 12.dp.toPx()), CornerRadius(r + 6.dp.toPx()))
    }
}

/** A steady soft halo, e.g. for the item picked up in tap-then-tap mode. */
fun Modifier.halo(active: Boolean, color: Color = Palette.honey, corner: Dp = 40.dp): Modifier =
    if (!active) this else drawBehind {
        val g = 10.dp.toPx()
        drawRoundRect(color.copy(alpha = 0.35f), Offset(-g, -g), Size(size.width + 2 * g, size.height + 2 * g), CornerRadius(corner.toPx() + g))
    }

/** A small, slow bob so objects feel alive. [seed] desynchronises neighbours. Runs on the slow clock. */
fun Modifier.floaty(seed: Int = 0, amplitude: Dp = 4.dp, enabled: Boolean = true): Modifier = composed {
    if (!enabled) return@composed this
    val period = remember(seed) { 2600f + Random(seed).nextInt(1400) }
    val phase = remember(seed) { Random(seed * 7 + 1).nextFloat() * 6.28f }
    val t = rememberSlowClock()
    graphicsLayer { translationY = sin(t.value / period * 6.2832f + phase) * amplitude.toPx() }
}

/** A gentle "not quite" tilt, played each time [trigger] increases. Small angles, about half a second. */
fun Modifier.wobble(trigger: Int): Modifier = composed {
    val a = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger > 0) for (v in floatArrayOf(-5f, 4f, -2.5f, 1.2f, 0f)) a.animateTo(v, tween(95))
    }
    graphicsLayer { rotationZ = a.value }
}

/**
 * A puffy rounded card: lighter on top like soft clay under a window, with a soft shadow.
 * Drawn, not clipped: rounded clips cost a stencil pass per frame on the frame's GPU.
 */
@Composable
fun SoftCard(
    color: Color,
    modifier: Modifier = Modifier,
    corner: Dp = 40.dp,
    elevation: Dp = 6.dp,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .softShadow(corner, elevation)
            .drawWithCache {
                val brush = Brush.verticalGradient(listOf(color.lighten(0.22f), color, color.darken(0.06f)))
                val r = CornerRadius(minOf(corner.toPx(), size.minDimension / 2))
                val shine = Color.White.copy(alpha = 0.16f)
                onDrawBehind {
                    drawRoundRect(brush, cornerRadius = r)
                    // a soft highlight along the top edge
                    drawRoundRect(shine, Offset(size.width * 0.06f, size.height * 0.035f), Size(size.width * 0.88f, size.height * 0.07f), CornerRadius(size.height * 0.035f))
                }
            },
        contentAlignment = contentAlignment,
        content = content,
    )
}

/** A round button with a drawn icon. 84 dp by default: well above toddler finger size. */
@Composable
fun RoundButton(
    icon: Icon,
    modifier: Modifier = Modifier,
    size: Dp = 84.dp,
    color: Color = Palette.cream,
    iconColor: Color = Palette.ink,
    sound: Sound? = Sound.Tap,
    hint: Boolean = false,
    enabled: Boolean = true,
    onTap: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .hintPulse(hint, Palette.honey, size / 2)
            .pressable(enabled, sound, onTap = onTap)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .softShadow(size / 2, 4.dp)
            .drawWithCache {
                val brush = Brush.verticalGradient(listOf(color.lighten(0.3f), color, color.darken(0.05f)))
                onDrawBehind {
                    drawCircle(brush)
                    drawIcon(icon, iconColor)
                }
            },
    )
}

/** Dots at the top of an activity: how many rounds are done, a calm "how far" cue. */
@Composable
fun ProgressDots(done: Int, total: Int, modifier: Modifier = Modifier, color: Color = Palette.honey) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until total) {
            val on = i < done
            val s by animateFloatAsState(if (on) 1f else 0.7f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow), label = "dot")
            Box(
                Modifier
                    .size(26.dp)
                    .graphicsLayer { scaleX = s; scaleY = s }
                    .drawBehind {
                        if (on) {
                            drawCircle(Palette.ink.copy(alpha = 0.12f), center = center + Offset(0f, 2.dp.toPx()))
                            drawCircle(Brush.verticalGradient(listOf(color.lighten(0.3f), color)))
                        } else {
                            drawCircle(Color(0x33FFFFFF))
                            drawCircle(Palette.ink.copy(alpha = 0.18f), style = Stroke(2.5.dp.toPx()))
                        }
                    },
            )
        }
    }
}
