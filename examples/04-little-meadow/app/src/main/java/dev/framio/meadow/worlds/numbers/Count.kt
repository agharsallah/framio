package dev.framio.meadow.worlds.numbers

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.Type
import dev.framio.meadow.ui.floaty
import dev.framio.meadow.ui.hintPulse
import dev.framio.meadow.ui.pressable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.random.Random

/** Things to count: sprites ob_<name>. */
internal val COUNTABLES = listOf("apple", "strawberry", "orange", "duck", "ball", "flower", "star", "fish", "cupcake", "carrot", "balloon_blue", "teddy", "pear", "mushroom")

/** How many to count at each level (Difficulty 0..4). */
internal fun countRange(level: Int): IntRange = when (level) {
    0 -> 1..3
    1 -> 2..5
    2 -> 3..7
    3 -> 5..10
    else -> 6..10
}

/**
 * Where [n] things sit, as fractions of the play area: one row up to five, two rows above that,
 * nudged a little so it looks scattered by hand, never overlapping. Pure, unit-tested.
 */
internal fun scatter(n: Int, seed: Int): List<Pair<Float, Float>> {
    val r = Random(seed)
    val rows = if (n <= 5) listOf(n) else listOf((n + 1) / 2, n / 2)
    val ys = if (rows.size == 1) listOf(0.56f) else listOf(0.38f, 0.78f) // the total appears above
    val out = ArrayList<Pair<Float, Float>>(n)
    rows.forEachIndexed { row, count ->
        val left = 0.24f
        val right = 0.92f
        for (i in 0 until count) {
            val x = if (count == 1) (left + right) / 2 else left + (right - left) * i / (count - 1)
            val jx = (r.nextFloat() - 0.5f) * 0.03f
            val jy = (r.nextFloat() - 0.5f) * 0.06f
            out += (x + jx) to (ys[row] + jy)
        }
    }
    return out
}

/**
 * Count with me: a group of friendly things; tap each one, it hops and gets its number while Pip
 * counts out loud (one-to-one correspondence). At the end the total appears big: "That's five!"
 */
@Composable
fun CountWithMe(s: PlayScope) {
    val total = 5
    var round by remember { mutableIntStateOf(0) }
    val co = rememberCoroutineScope()
    val spec = remember(round) {
        val r = Random(round * 7919 + System.nanoTime().toInt())
        COUNTABLES[r.nextInt(COUNTABLES.size)] to countRange(s.level).random(r)
    }
    val (thing, n) = spec
    var counted by remember(round) { mutableStateOf(listOf<Int>()) }
    var done by remember(round) { mutableStateOf(false) }
    val spots = remember(round) { scatter(n, round + n * 31) }

    LaunchedEffect(round) {
        s.rounds(round, total)
        delay(if (round == 0) 1600 else 300)
        s.prompt("count_tap")
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val item = if (n <= 5) 180.dp else 150.dp
        spots.forEachIndexed { i, (fx, fy) ->
            val order = counted.indexOf(i)
            val isNext = order < 0 && (0 until n).firstOrNull { it !in counted } == i
            CountItem(
                sprite = "ob_$thing",
                label = if (order >= 0) order + 1 else null,
                appearDelay = i * 110L,
                hint = s.hint && isNext && !done,
                seed = i + round * 13,
                modifier = Modifier
                    .offset(maxWidth * fx - item / 2, maxHeight * fy - item / 2)
                    .size(item),
            ) {
                if (done) return@CountItem
                if (order >= 0) {
                    s.say("num_${order + 1}") // tapping a counted one again just repeats its number
                    return@CountItem
                }
                val k = counted.size + 1
                counted = counted + i
                s.sfx(Sound.Ding, 0.7f, 2.0.pow((k - 1).coerceAtMost(12) / 12.0).toFloat())
                s.say("num_$k")
                if (k == n) {
                    done = true
                    co.launch {
                        delay(900)
                        s.sayAndWait("thats_$n")
                        s.practised("count:$n")
                        s.praiseAndWait()
                        s.roundDone(firstTry = true)
                        delay(500)
                        if (round + 1 >= total) {
                            s.rounds(total, total)
                            s.finish()
                        } else round++
                    }
                }
            }
        }
        if (done) BigNumber(n, Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun CountItem(
    sprite: String,
    label: Int?,
    appearDelay: Long,
    hint: Boolean,
    seed: Int,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(appearDelay)
        enter.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    val hop = remember { Animatable(0f) }
    LaunchedEffect(label) {
        if (label != null) {
            hop.animateTo(-26f, tween(160, easing = FastOutSlowInEasing))
            hop.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow))
        }
    }
    Box(
        modifier
            .graphicsLayer {
                alpha = enter.value
                val sc = 0.8f + 0.2f * enter.value
                scaleX = sc
                scaleY = sc
                translationY = hop.value * density
            }
            .hintPulse(hint, Palette.honey, 90.dp)
            .pressable(sound = null, onTap = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Sprite(sprite, Modifier.fillMaxSize().floaty(seed, 3.dp, enabled = label == null))
        if (label != null) {
            SoftCard(Palette.honey, Modifier.align(Alignment.TopEnd).size(64.dp), corner = 32.dp, elevation = 4.dp) {
                BasicText("$label", style = Type.clay(34.sp, Palette.cream))
            }
        }
    }
}

@Composable
private fun BigNumber(n: Int, modifier: Modifier) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)) }
    SoftCard(
        Palette.cream,
        modifier
            .size(150.dp)
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = pop.value.coerceIn(0f, 1f) },
        corner = 75.dp,
    ) {
        BasicText("$n", style = Type.clay(96.sp, Palette.honey))
    }
}
