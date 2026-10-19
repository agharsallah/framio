package dev.framio.meadow.worlds.puzzles

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.framio.meadow.audio.Instrument
import dev.framio.meadow.play.ChoiceRow
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
import kotlin.random.Random

// Train geometry, from the rendered sprites (pz_train_*, 400×280 px, both sharing one crop) at 0.55 dp/px
private const val K = 0.55f
private const val BOX_W = 400 * K
private const val BOX_H = 280 * K
private const val ENGINE_FRONT = BOX_W - 334 * K // the engine is drawn mirrored, facing left
private const val ENGINE_BACK = BOX_W - 46 * K
private const val WAGON_LEFT = 52 * K
private const val WAGON_STEP = 142f
private const val CARGO_X = 180 * K           // middle of the tub
private const val CARGO_BOTTOM = 128 * K      // just under the rim: the wagon's front wall hides the bottom
private const val RAIL_Y = 262 * K
private const val CARGO = 92f

/**
 * What comes next? A little clay train carries things in a pattern (AB AB, then AAB, ABC, ABB,
 * AABB as the level rises); the last wagon is empty. Pick what goes there from three cards: it hops
 * into the wagon, the train toots and chugs off, and the next one rolls in. Five trains.
 */
@Composable
fun Patterns(s: PlayScope) {
    val total = 5
    var round by remember { mutableIntStateOf(0) }
    val co = rememberCoroutineScope()
    val r = remember(round) { patternRound(s.level, Random(System.nanoTime())) }
    var answered by remember(round) { mutableStateOf(false) }
    val trainX = remember(round) { Animatable(1400f) }
    val bob = remember(round) { Animatable(0f) }
    val drop = remember(round) { Animatable(-170f) }

    LaunchedEffect(round) {
        s.rounds(round, total)
        if (round == 0) delay(1600)
        trainX.animateTo(0f, tween(1300, easing = LinearOutSlowInEasing))
        s.prompt("patterns_prompt")
    }

    Box(Modifier.fillMaxSize()) {
        // rails across the whole screen
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            val y = (TRAIN_TOP + RAIL_Y) * density
            val sleeper = Color(0xFFC9A887)
            var x = -10f
            while (x < size.width) {
                drawRoundRect(sleeper.copy(alpha = 0.7f), Offset(x, y - 3 * density), Size(30 * density, 12 * density), CornerRadius(4 * density))
                x += 46 * density
            }
            drawRoundRect(Color(0xFF9C8C94), Offset(0f, y), Size(size.width, 5 * density), CornerRadius(3 * density))
        }
        val cars = r.cars.size
        val trainW = (ENGINE_BACK - ENGINE_FRONT) + cars * WAGON_STEP
        val left = (1280f - trainW) / 2f - ENGINE_FRONT
        Box(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .graphicsLayer {
                    translationX = trainX.value * density
                    translationY = bob.value * density
                },
        ) {
            // wagons: the cargo first, so the wagon's front wall hides its bottom
            for (i in 0 until cars) {
                val boxX = left + ENGINE_BACK + 2f + i * WAGON_STEP - WAGON_LEFT
                val last = i == cars - 1
                val cx = boxX + CARGO_X - CARGO / 2
                val cy = TRAIN_TOP + CARGO_BOTTOM - CARGO
                if (!last) {
                    Sprite(
                        "ob_${r.cars[i]}",
                        Modifier
                            .offset(cx.dp, cy.dp)
                            .size(CARGO.dp)
                            .pressable(sound = null) { s.say("name_${r.cars[i]}") },
                    )
                } else if (answered) {
                    Sprite(
                        "ob_${r.answer}",
                        Modifier
                            .offset(cx.dp, cy.dp)
                            .size(CARGO.dp)
                            .graphicsLayer { translationY = drop.value * density; alpha = ((drop.value + 170f) / 80f).coerceIn(0f, 1f) },
                    )
                }
                Sprite("pz_train_wagon", Modifier.offset(boxX.dp, TRAIN_TOP.dp).size(BOX_W.dp, BOX_H.dp))
                if (last && !answered) {
                    SoftCard(
                        Palette.cream,
                        Modifier
                            .offset((cx + 6).dp, (cy + 8).dp)
                            .size(80.dp)
                            .floaty(7, 3.dp)
                            .hintPulse(s.hint, Palette.honey, 40.dp),
                        corner = 40.dp,
                    ) { BasicText("?", style = Type.clay(50.sp, Palette.lavender)) }
                }
            }
            Sprite(
                "pz_train_engine",
                Modifier
                    .offset(left.dp, TRAIN_TOP.dp)
                    .size(BOX_W.dp, BOX_H.dp)
                    .graphicsLayer { scaleX = -1f },
            )
        }

        ChoiceRow(
            s, r.choices, isRight = { it == r.answer },
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 200.dp, bottom = 70.dp),
            cardSize = 190.dp, spacing = 44.dp,
            onRight = { _, first ->
                answered = true
                s.practised("pattern:${r.kind.unit.lowercase()}")
                co.launch {
                    drop.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow))
                    // toot toot, and a gentle chug
                    s.app.sfx.note(Instrument.Bell, 79, 0.6f)
                    delay(260)
                    s.app.sfx.note(Instrument.Bell, 76, 0.6f)
                    s.say("patterns_toot")
                    repeat(3) {
                        bob.animateTo(-4f, tween(170, easing = FastOutSlowInEasing))
                        bob.animateTo(0f, tween(170, easing = FastOutSlowInEasing))
                    }
                    s.roundDone(first)
                    trainX.animateTo(-1500f, tween(1300, easing = FastOutLinearInEasing))
                    if (round + 1 >= total) {
                        s.rounds(total, total)
                        s.finish()
                    } else round++
                }
            },
        ) {
            Sprite("ob_$it", Modifier.fillMaxSize(0.78f))
        }
    }
}

private const val TRAIN_TOP = 34f
