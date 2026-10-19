package dev.framio.meadow.worlds.quiet

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.framio.meadow.core.Friend
import dev.framio.meadow.ui.FriendView
import dev.framio.meadow.ui.Mood
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.drawSpriteFit
import dev.framio.meadow.ui.rememberSlowClock
import dev.framio.meadow.ui.rememberSprite
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

/** The host's top bar height (PlayScreen): layers that reach behind it are pulled up by this much. */
internal val TOP_BAR = 112.dp

/** Lets a full-screen layer (the night sky, the dark fade) reach up behind the host's top bar. */
internal fun Modifier.underTopBar(): Modifier = layout { m, c ->
    val bar = TOP_BAR.roundToPx()
    val p = m.measure(Constraints.fixed(c.maxWidth, c.maxHeight + bar))
    layout(c.maxWidth, c.maxHeight) { p.place(0, -bar) }
}

private fun moodOf(look: String) = when (look) {
    "happy" -> Mood.Happy
    "wave" -> Mood.Wave
    "sleep" -> Mood.Sleep
    else -> Mood.Idle
}

/**
 * A friend showing a feeling (qt_<friend>_<feeling>) or, for a plain look ("idle", "happy", "wave",
 * "sleep") or for Luma, the usual living [FriendView]. Feelings breathe slowly too and cross-fade
 * when they change, so a face never snaps.
 */
@Composable
fun FeelingFriend(friend: Friend, look: String, modifier: Modifier = Modifier, talking: Boolean? = false) {
    val feeling = Feeling.of(look)
    if (feeling == null || friend !in FEELING_FRIENDS) {
        val mood = if (feeling == null) moodOf(look) else when (feeling) {
            Feeling.Happy, Feeling.Proud -> Mood.Happy
            Feeling.Sleepy, Feeling.Calm -> Mood.Sleep
            else -> Mood.Idle
        }
        FriendView(friend, modifier, mood = mood, talking = talking)
        return
    }
    val clock = rememberSlowClock()
    val period = if (feeling == Feeling.Sleepy || feeling == Feeling.Calm) 8400f else 6600f
    val bmp = rememberSprite(feelingSprite(friend, feeling))
    Canvas(
        modifier.graphicsLayer {
            val b = (1f - cos(2 * PI.toFloat() * clock.value / period)) / 2
            scaleY = 1f + 0.014f * b
            scaleX = 1f - 0.007f * b
            transformOrigin = TransformOrigin(0.5f, 0.96f)
        },
    ) { bmp?.let { drawSpriteFit(it) } }
}

/** [FeelingFriend] that cross-fades between looks. */
@Composable
fun FeelingFriendFade(friend: Friend, look: String, modifier: Modifier = Modifier) {
    Crossfade(look, modifier, animationSpec = tween(520), label = "look") { l ->
        FeelingFriend(friend, l, Modifier.fillMaxSize())
    }
}

/**
 * Friends and props laid out by fractions of the area (feet / bottom at y), drawn in z order:
 * props behind the friends unless they are up in the air.
 */
@Composable
fun Scene(actors: List<Actor>, props: List<Prop>, modifier: Modifier = Modifier, onActor: ((Actor) -> Unit)? = null) {
    BoxWithConstraints(modifier) {
        val w = maxWidth
        val h = maxHeight
        props.filter { it.y > 0.7f }.forEach { PropAt(it, w, h) }
        actors.forEach { a ->
            val size = h * a.h
            Box(
                Modifier
                    .offset(w * a.x - size / 2, h * a.y - size)
                    .size(size),
            ) { FeelingFriend(a.friend, a.look, Modifier.fillMaxSize()) }
        }
        props.filter { it.y <= 0.7f }.forEach { PropAt(it, w, h) }
    }
}

@Composable
private fun PropAt(p: Prop, w: Dp, h: Dp) {
    val size = h * p.h
    Sprite(
        p.sprite,
        Modifier
            .offset(w * p.x - size / 2, h * p.y - size)
            .size(size)
            .graphicsLayer { rotationZ = p.tilt },
    )
}

/**
 * Soft rain: a few dozen pale streaks falling slowly. One canvas, ticking at about 30 fps,
 * nothing else recomposes.
 */
@Composable
fun SoftRain(modifier: Modifier = Modifier, drops: Int = 42, seed: Int = 3) {
    val spec = remember(seed) {
        val r = Random(seed)
        List(drops) { floatArrayOf(r.nextFloat(), r.nextFloat(), 0.7f + r.nextFloat() * 0.6f, 0.5f + r.nextFloat() * 0.5f) }
    }
    val clock = rememberSlowClock(40)
    Canvas(modifier) {
        val t = clock.value / 1000f
        val len = size.height * 0.05f
        for (d in spec) {
            val speed = 0.22f * d[2] // screen heights per second: slow, sleepy rain
            val y = ((d[1] + t * speed) % 1.1f) * size.height - len
            val x = d[0] * size.width - y * 0.06f
            drawLine(
                Color(0xFFEFF4FA).copy(alpha = 0.32f * d[3]), Offset(x, y), Offset(x - len * 0.12f, y + len * d[3]),
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * A picture with rounded corners that stays cheap on the frame: a plain rectangle clip (a scissor,
 * not a stencil) and the four corners painted over in [frame], the colour of the mat around it.
 */
fun Modifier.roundedPicture(radius: Dp, frame: Color): Modifier = clipToBounds().drawWithCache {
    val r = radius.toPx()
    val corners = Path().apply {
        op(
            Path().apply { addRect(Rect(0f, 0f, size.width, size.height)) },
            Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, r, r)) },
            PathOperation.Difference,
        )
    }
    onDrawWithContent {
        drawContent()
        drawPath(corners, frame)
    }
}
