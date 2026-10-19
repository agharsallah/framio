package dev.framio.meadow.worlds.puzzles

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.halo
import dev.framio.meadow.ui.hintPulse
import dev.framio.meadow.ui.pressable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Memory match: face-down clay cards; turn two at a time. A pair hops, glows and is named; two that
 * don't match wait a moment and turn back gently (no "wrong" sound). 4 cards for the youngest, up to
 * 12. Two boards per play.
 */
@Composable
fun MemoryMatch(s: PlayScope) {
    val total = 2
    var round by remember { mutableIntStateOf(0) }
    val co = rememberCoroutineScope()
    val (cols, rows) = remember(round) { memoryGrid(s.level) }
    val cards = remember(round) { memoryDeal(s.level, Random(System.nanoTime())) }
    val flips = remember(round) { List(cards.size) { Animatable(0f) } }
    val hops = remember(round) { List(cards.size) { Animatable(0f) } }
    val open = remember(round) { mutableStateListOf<Int>() }
    val matched = remember(round) { mutableStateListOf<Int>() }
    var busy by remember(round) { mutableStateOf(false) }
    var misses by remember(round) { mutableIntStateOf(0) } // in a row, for the hint
    var allMisses by remember(round) { mutableIntStateOf(0) }

    LaunchedEffect(round) {
        s.rounds(round, total)
        delay(if (round == 0) 2200 else 400)
        s.prompt("memory_prompt")
    }

    fun flip(i: Int, to: Float, ms: Int): Job = co.launch { flips[i].animateTo(to, tween(ms, easing = FastOutSlowInEasing)) }

    fun tap(i: Int) {
        if (busy || i in open || i in matched) return
        s.sfx(Sound.Whoosh, 0.35f, 1.3f)
        open += i
        val j = flip(i, 180f, 350)
        if (open.size < 2) return
        busy = true
        val (a, b) = open[0] to open[1]
        co.launch {
            j.join()
            if (cards[a] == cards[b]) {
                delay(150)
                matched += a
                matched += b
                open.clear()
                s.sfx(Sound.Sparkle, 0.6f)
                listOf(a, b).map { k ->
                    launch {
                        hops[k].animateTo(-22f, tween(200, easing = FastOutSlowInEasing))
                        hops[k].animateTo(0f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow))
                    }
                }
                s.practised("memory:${cards[a]}")
                s.sayAndWait("name_${cards[a]}")
                s.showHint(false)
                misses = 0
                busy = false
                if (matched.size == cards.size) {
                    delay(300)
                    s.praiseAndWait()
                    s.roundDone(firstTry = allMisses <= cards.size / 2)
                    delay(600)
                    if (round + 1 >= total) {
                        s.rounds(total, total)
                        s.finish()
                    } else round++
                }
            } else {
                misses++
                allMisses++
                delay(1200)
                listOf(flip(a, 0f, 420), flip(b, 0f, 420)).joinAll()
                open.clear()
                busy = false
                if (misses >= 3) s.showHint(true)
            }
        }
    }

    // when stuck: the partner of the open card (or the first unmatched pair) breathes
    val hintCard = if (!s.hint) -1 else {
        val first = open.firstOrNull() ?: cards.indices.firstOrNull { it !in matched } ?: -1
        if (first < 0) -1 else cards.indices.firstOrNull { it != first && it !in matched && cards[it] == cards[first] } ?: -1
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(start = 230.dp, end = 24.dp), contentAlignment = Alignment.Center) {
        val size = memoryCardSize(cols, rows, maxWidth.value, maxHeight.value).dp
        val gap = size * 0.13f
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            for (r in 0 until rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (c in 0 until cols) {
                        val i = r * cols + c
                        MemoryCard(
                            thing = cards[i],
                            flip = flips[i],
                            hop = hops[i],
                            matched = i in matched,
                            hint = i == hintCard,
                            enterDelay = i * 60L,
                            size = size,
                        ) { tap(i) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryCard(
    thing: String,
    flip: Animatable<Float, *>,
    hop: Animatable<Float, *>,
    matched: Boolean,
    hint: Boolean,
    enterDelay: Long,
    size: Dp,
    onTap: () -> Unit,
) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(enterDelay)
        enter.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                alpha = enter.value
                val sc = 0.85f + 0.15f * enter.value
                scaleX = sc
                scaleY = sc
                translationY = hop.value * density + (1f - enter.value) * 30f
            }
            .halo(matched, Palette.honey, size / 5)
            .hintPulse(hint, Palette.honey, size / 5)
            .pressable(sound = null, onTap = onTap),
    ) {
        val corner = size / 5
        // back: sage clay with the flower; hidden once the card has turned past its edge
        SoftCard(
            Palette.sage,
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationY = flip.value
                    cameraDistance = 14f * density
                    alpha = if (flip.value <= 90f) 1f else 0f
                },
            corner = corner,
        ) {
            Sprite("pz_card_back", Modifier.fillMaxSize(0.72f))
        }
        // front: the thing, drawn mirrored so it reads the right way round when turned
        SoftCard(
            if (matched) lerp(Palette.cream, Palette.honey, 0.28f) else Palette.cream,
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationY = flip.value - 180f
                    cameraDistance = 14f * density
                    alpha = if (flip.value > 90f) 1f else 0f
                },
            corner = corner,
        ) {
            Sprite("ob_$thing", Modifier.fillMaxSize(0.78f))
        }
    }
}
