package dev.framio.meadow.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.framio.meadow.LocalApp
import dev.framio.meadow.core.Friend
import dev.framio.meadow.core.Pose
import kotlinx.coroutines.delay
import kotlin.random.Random

/** How a friend feels right now. Blinking and talking are added on top automatically. */
enum class Mood { Idle, Happy, Sleep, Wave }

/**
 * A living friend: breathes slowly, blinks now and then, moves its mouth while the narration
 * plays, and does one soft hop when it becomes [Mood.Happy]. Nothing shakes or flashes.
 *
 * [talking] defaults to "the voice is playing"; pass false for a friend who is only listening.
 */
@Composable
fun FriendView(
    friend: Friend,
    modifier: Modifier = Modifier,
    mood: Mood = Mood.Idle,
    talking: Boolean? = null,
    /** No breathing: next to a GL view every Compose redraw costs a full-screen composite on this firmware. */
    still: Boolean = false,
    onTap: (() -> Unit)? = null,
) {
    val app = LocalApp.current
    val reduced = app.settings.flow.collectAsState().value.reducedMotion
    val speaking by app.voice.speaking.collectAsState()
    val isTalking = talking ?: speaking

    // every pose decoded up front, so the first blink doesn't flash empty
    val sprites = Pose.entries.associateWith { rememberSprite(friend.sprite(it)) }

    var blink by remember { mutableStateOf(false) }
    var mouthOpen by remember { mutableStateOf(false) }
    LaunchedEffect(friend) {
        val r = Random(friend.ordinal * 31 + System.nanoTime().toInt())
        while (true) {
            delay(2600L + r.nextLong(3200))
            blink = true
            delay(140)
            blink = false
            if (r.nextInt(6) == 0) { // an occasional double blink
                delay(180)
                blink = true
                delay(120)
                blink = false
            }
        }
    }
    LaunchedEffect(isTalking) {
        if (!isTalking) {
            mouthOpen = false
            return@LaunchedEffect
        }
        val r = Random(System.nanoTime())
        while (true) {
            mouthOpen = !mouthOpen
            delay(if (mouthOpen) 150L + r.nextLong(90) else 110L + r.nextLong(110))
        }
    }

    val pose = when {
        mood == Mood.Sleep -> Pose.Sleep
        isTalking && mouthOpen -> Pose.Talk
        blink && mood != Mood.Happy -> Pose.Blink
        mood == Mood.Happy -> Pose.Happy
        mood == Mood.Wave -> Pose.Wave
        else -> Pose.Idle
    }
    val bmp = sprites[pose] ?: sprites[Pose.Idle]

    // breathing on the slow clock: a 1 % swell doesn't need 60 updates a second
    val clock = if (still) remember { mutableLongStateOf(0L) } else rememberSlowClock()
    val period = if (mood == Mood.Sleep) 8400f else 6400f
    val seed = remember(friend) { friend.ordinal * 1.7f }
    val hop = remember { Animatable(0f) }
    val hopPx = with(LocalDensity.current) { 22.dp.toPx() }
    LaunchedEffect(mood) {
        if (mood == Mood.Happy && !reduced) {
            hop.animateTo(-hopPx, tween(220, easing = FastOutSlowInEasing))
            hop.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow))
        }
    }
    var squish by remember { mutableStateOf(0) }
    val press = remember { Animatable(0f) }
    LaunchedEffect(squish) {
        if (squish > 0) {
            press.animateTo(1f, tween(90))
            press.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow))
        }
    }

    val tapModifier = if (onTap != null) Modifier.pressable(sound = null) { squish++; onTap() } else Modifier
    Canvas(
        modifier
            .then(tapModifier)
            .graphicsLayer {
                val amp = if (reduced) 0.006f else 0.014f
                val b = 0.5f - 0.5f * kotlin.math.cos(clock.value / period * 6.2832f + seed)
                scaleY = 1f + amp * b - 0.06f * press.value
                scaleX = 1f - amp * 0.5f * b + 0.04f * press.value
                translationY = hop.value
                transformOrigin = TransformOrigin(0.5f, 0.96f)
            },
    ) {
        bmp?.let { drawSpriteFit(it) }
    }
}
