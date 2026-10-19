package dev.framio.meadow.worlds.numbers

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import dev.framio.meadow.play.ChoiceRow
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

private val BASKET_W = 380.dp
private val BASKET_H = 285.dp

/**
 * More or fewer: two baskets with different amounts of the same fruit. "Which basket has more
 * apples?" (from level 3 also "…fewer…", the harder word). The difference shrinks as the child gets
 * better; after the answer both amounts appear as numbers.
 */
@Composable
fun MoreOrFewer(s: PlayScope) {
    val total = 5
    var round by remember { mutableIntStateOf(0) }
    val last = remember { arrayOfNulls<String>(1) } // not state: only read when a round is built
    val spec = remember(round) { moreRound(s.level, Random(round * 131 + System.nanoTime().toInt()), last[0]).also { last[0] = it.thing } }
    var reveal by remember(round) { mutableStateOf(false) }
    val co = rememberCoroutineScope()

    LaunchedEffect(round) {
        s.rounds(round, total)
        if (round == 0) s.awaitQuiet() else delay(300)
        s.prompt("more_${if (spec.askMore) "more" else "fewer"}_${spec.thing}")
    }

    Box(Modifier.fillMaxSize().padding(start = 150.dp, bottom = 40.dp), contentAlignment = Alignment.Center) {
        key(round) {
            ChoiceRow(
                s, listOf(0, 1), isRight = { it == spec.answer },
                cardSize = 410.dp, spacing = 70.dp,
                onRight = { side, first ->
                    co.launch {
                        reveal = true
                        s.sayAndWait(if (spec.askMore) "more_this_more" else "more_this_fewer")
                        s.practised("compare:${if (spec.askMore) "more" else "fewer"}")
                        s.roundDone(first)
                        delay(900)
                        if (round + 1 >= total) {
                            s.rounds(total, total)
                            s.finish()
                        } else round++
                    }
                },
            ) { side -> Basket(spec.thing, if (side == 0) spec.left else spec.right, maxOf(spec.left, spec.right) <= 6, reveal) }
        }
    }
}

/** A basket with [n] things piled in it: back half, the pile, then the front rim over the bottom row. */
@Composable
private fun BoxScope.Basket(thing: String, n: Int, big: Boolean, reveal: Boolean) {
    val item = pileItemSize(big).dp
    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).size(BASKET_W, BASKET_H)) {
        Sprite("nu_basket_back", Modifier.fillMaxSize())
        pileSpots(n, big).asReversed().forEach { (fx, fy) -> // back row first
            Sprite("ob_$thing", Modifier.offset(BASKET_W * fx - item / 2, BASKET_H * fy - item / 2).size(item))
        }
        Sprite("nu_basket_front", Modifier.fillMaxSize())
    }
    if (reveal) {
        val pop = remember { Animatable(0f) }
        LaunchedEffect(Unit) { pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)) }
        SoftCard(
            Palette.honey,
            Modifier.align(Alignment.TopEnd).padding(14.dp).size(78.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value },
            corner = 39.dp, elevation = 3.dp,
        ) { BasicText("$n", style = Type.clay(40.sp, Palette.cream)) }
    }
}
