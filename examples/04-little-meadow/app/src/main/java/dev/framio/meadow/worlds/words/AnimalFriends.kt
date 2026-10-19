package dev.framio.meadow.worlds.words

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.Mood
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.Sprites
import dev.framio.meadow.ui.hintPulse
import dev.framio.meadow.ui.pressable
import dev.framio.meadow.ui.wobble
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

private const val QUIZ_ROUNDS = 5

/**
 * Animal friends: a meadow on the farm with a few chibi animals. First the child greets each
 * one: it hops, smiles, and Bo says (and makes) its sound, "The cow says moo! Mooo!". Then a
 * little game: "Who says baa?" with two or three animals on the grass. The animals in a question
 * always say different things.
 */
@Composable
fun AnimalFriends(s: PlayScope) {
    val random = remember { Random(System.nanoTime()) }
    val meadow = remember { Farm.meadow(s.lang, Farm.exploreCount(s.level), random) }
    val met = remember { mutableStateListOf<Animal>() }
    var quiz by remember { mutableIntStateOf(-1) } // -1 = greeting the animals
    var targets by remember { mutableStateOf<List<Animal>>(emptyList()) }
    val co = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        Sprites.preload(Farm.animals.flatMap { listOf(it.idle, it.happy) })
    }
    LaunchedEffect(Unit) {
        s.rounds(0, QUIZ_ROUNDS)
        s.afterSpeech()
        if (quiz < 0 && met.isEmpty()) s.prompt("animals_tap")
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        if (quiz < 0) {
            var busy by remember { mutableStateOf(false) }
            val spots = remember { Farm.spots(meadow.size) }
            val nextToMeet = meadow.firstOrNull { it !in met }
            meadow.forEachIndexed { i, a ->
                val (fx, fy, k) = spots[i]
                val size = 200.dp * k
                FarmAnimal(
                    a, Modifier.offset(w * fx - size / 2, h * fy - size).size(size),
                    appearDelay = 140L * i, hint = s.hint && a == nextToMeet,
                ) { hop ->
                    if (busy) return@FarmAnimal
                    hop()
                    s.sfx(Sound.Pop, 0.6f, 0.8f + 0.06f * i)
                    s.practised("animal:${a.id}")
                    val first = a !in met
                    if (first) met += a
                    if (met.size < meadow.size) {
                        s.say("animals_says_${a.id}")
                        return@FarmAnimal
                    }
                    busy = true
                    co.launch {
                        s.sayAndWait("animals_says_${a.id}")
                        s.friendMood = Mood.Happy
                        delay(400)
                        s.sayAndWait("animals_quiz")
                        targets = Farm.quizTargets(met.toList(), QUIZ_ROUNDS, random)
                        quiz = 0
                    }
                }
            }
        } else {
            key(quiz) {
                val target = targets[quiz]
                val options = remember { Farm.quizRound(s.lang, target, Farm.choiceCount(s.level), random) }
                val spots = remember { Farm.spots(options.size) }
                var solved by remember { mutableStateOf(false) }
                var tries by remember { mutableIntStateOf(0) }
                val wobbles = remember { mutableStateMapOf<Int, Int>() }
                LaunchedEffect(Unit) {
                    s.rounds(quiz, QUIZ_ROUNDS)
                    delay(500)
                    s.prompt("animals_who_${target.id}")
                }
                options.forEachIndexed { i, a ->
                    val (fx, fy, k) = spots[i]
                    val size = 210.dp * k
                    FarmAnimal(
                        a, Modifier.offset(w * fx - size / 2, h * fy - size).size(size).wobble(wobbles[i] ?: 0),
                        appearDelay = 120L * i, hint = s.hint && a == target && !solved,
                        dim = solved && a != target,
                    ) { hop ->
                        if (solved) return@FarmAnimal
                        if (a == target) {
                            solved = true
                            hop()
                            s.practised("animal:${a.id}")
                            co.launch {
                                s.praiseAndWait()
                                s.sayAndWait("animals_says_${a.id}")
                                s.roundDone(tries == 0)
                                delay(400)
                                if (quiz + 1 >= QUIZ_ROUNDS) {
                                    s.rounds(QUIZ_ROUNDS, QUIZ_ROUNDS)
                                    s.finish()
                                } else quiz++
                            }
                        } else {
                            tries++
                            wobbles[i] = (wobbles[i] ?: 0) + 1
                            s.notQuite()
                        }
                    }
                }
            }
        }
    }
}

/** One animal on the grass: fades in, hops and smiles when tapped. */
@Composable
private fun FarmAnimal(
    a: Animal,
    modifier: Modifier,
    appearDelay: Long,
    hint: Boolean,
    dim: Boolean = false,
    onTap: (hop: () -> Unit) -> Unit,
) {
    val enter = remember { Animatable(0f) }
    val hopY = remember { Animatable(0f) }
    var happy by remember { mutableStateOf(false) }
    var hops by remember { mutableIntStateOf(0) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        delay(appearDelay)
        enter.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(hops) {
        if (hops == 0) return@LaunchedEffect
        happy = true
        hopY.animateTo(-34f, tween(200, easing = FastOutSlowInEasing))
        hopY.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow))
        delay(1400)
        happy = false
    }
    LaunchedEffect(dim) { fade.animateTo(if (dim) 0.45f else 1f, tween(400)) }
    Box(
        modifier
            .graphicsLayer {
                alpha = enter.value * fade.value
                val sc = 0.85f + 0.15f * enter.value
                scaleX = sc
                scaleY = sc
                translationY = hopY.value * density + (1f - enter.value) * 30f
            }
            .hintPulse(hint, Palette.honey, 100.dp)
            .pressable(sound = null) { onTap { hops++ } },
    ) {
        // the shared duck is framed like an object, a little larger than the farm friends
        val shrink = if (a.id == "duck") 0.8f else 1f
        Sprite(
            if (happy) a.happy else a.idle,
            Modifier.fillMaxSize().graphicsLayer { scaleX = shrink; scaleY = shrink; transformOrigin = TransformOrigin(0.5f, 0.92f) },
        )
    }
}
