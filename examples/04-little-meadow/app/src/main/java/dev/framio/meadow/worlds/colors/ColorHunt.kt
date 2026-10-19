package dev.framio.meadow.worlds.colors

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.Sprites
import dev.framio.meadow.ui.floaty
import dev.framio.meadow.ui.halo
import dev.framio.meadow.ui.hintPulse
import dev.framio.meadow.ui.pressable
import dev.framio.meadow.ui.wobble
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.random.Random

/** On the first round, waits until the activity's intro has been said; later rounds just breathe. */
internal suspend fun PlayScope.waitForIntro(round: Int) {
    if (round == 0) delay((play.intro?.let { app.voice.durationMs(it) } ?: 0) + 450L) else delay(350)
}

/**
 * Color hunt: "Find the blue balloon!" Two to four things in different colours; tapping one says
 * its colour, the right balloon floats gently up into the sky. The youngest learn red, yellow,
 * blue and green with balloons; later orange, purple and pink, then brown, white and black, and
 * any coloured thing (cars, socks, cups): "Can you find something blue?"
 */
@Composable
fun ColorHunt(s: PlayScope) {
    val total = 5
    var round by remember { mutableIntStateOf(0) }
    var previous by remember { mutableStateOf<Hue?>(null) }
    val spec = remember(round) { huntRound(s.level, Random(System.nanoTime()), previous) }
    var solved by remember(round) { mutableStateOf(-1) }
    var tries by remember(round) { mutableIntStateOf(0) }
    val wobbles = remember(round) { mutableStateMapOf<Int, Int>() }
    val co = rememberCoroutineScope()

    LaunchedEffect(round) {
        Sprites.preload(spec.options.map { it.sprite })
        s.rounds(round, total)
        s.waitForIntro(round)
        s.prompt(spec.promptLine)
    }

    val n = spec.options.size
    val item: Dp = when (n) {
        2 -> 290.dp
        3 -> 260.dp
        else -> 226.dp
    }
    Box(Modifier.fillMaxSize().padding(start = 220.dp, end = 24.dp)) {
        Row(
            Modifier.align(Alignment.Center).offset(y = (-20).dp),
            horizontalArrangement = Arrangement.spacedBy(if (n <= 3) 44.dp else 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            spec.options.forEachIndexed { i, it ->
                androidx.compose.runtime.key(round, i) {
                    HuntThing(
                        sprite = it.sprite,
                        balloon = it.thing == "balloon",
                        index = i,
                        size = item,
                        solved = solved == i,
                        dimmed = solved >= 0 && solved != i,
                        hint = s.hint && it == spec.target && solved < 0,
                        wobble = wobbles[i] ?: 0,
                    ) {
                        if (solved >= 0) return@HuntThing
                        s.say(it.hue.line)
                        if (it == spec.target) {
                            solved = i
                            s.practised("color:${it.hue.id}")
                            co.launch {
                                delay(750)
                                s.praiseAndWait()
                                delay(if (it.thing == "balloon") 700 else 300)
                                s.roundDone(firstTry = tries == 0)
                                if (round + 1 >= total) {
                                    s.rounds(total, total)
                                    s.finish()
                                } else {
                                    previous = spec.target.hue
                                    round++
                                }
                            }
                        } else {
                            tries++
                            wobbles[i] = (wobbles[i] ?: 0) + 1
                            co.launch {
                                delay(850)
                                s.notQuite()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HuntThing(
    sprite: String,
    balloon: Boolean,
    index: Int,
    size: Dp,
    solved: Boolean,
    dimmed: Boolean,
    hint: Boolean,
    wobble: Int,
    onTap: () -> Unit,
) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(110L * index)
        enter.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
    }
    // the right balloon lets go and drifts up; other things do one happy hop
    val rise = remember { Animatable(0f) }
    LaunchedEffect(solved) {
        if (!solved) return@LaunchedEffect
        if (balloon) {
            delay(500)
            rise.animateTo(1f, tween(2200, easing = LinearOutSlowInEasing))
        } else {
            rise.animateTo(-0.06f, tween(220, easing = FastOutSlowInEasing))
            rise.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow))
        }
    }
    val dim by animateFloatAsState(if (dimmed) 0.45f else 1f, tween(400), label = "dim")
    val stagger = if (index % 2 == 0) 0.dp else 36.dp
    Box(
        Modifier
            .offset(y = stagger)
            .size(size)
            .graphicsLayer {
                alpha = enter.value * dim
                val sc = 0.85f + 0.15f * enter.value
                scaleX = sc
                scaleY = sc
                val up = if (balloon) rise.value * 760f else rise.value * 400f
                translationY = (1f - enter.value) * 40f - up * density
                rotationZ = if (balloon) sin(rise.value * 7f) * 6f * rise.value.coerceAtMost(0.5f) * 2f else 0f
            }
            .wobble(wobble)
            .halo(solved && !balloon, Palette.honey, size / 2)
            .hintPulse(hint, Palette.honey, size / 2)
            .pressable(enabled = !solved, onTap = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Sprite(sprite, Modifier.fillMaxSize().floaty(index * 17 + 3, if (balloon) 6.dp else 3.dp, enabled = !solved))
    }
}
