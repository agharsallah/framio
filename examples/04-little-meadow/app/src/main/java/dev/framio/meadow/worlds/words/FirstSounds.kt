package dev.framio.meadow.worlds.words

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import dev.framio.meadow.ui.Sprites
import dev.framio.meadow.ui.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

private const val FIRSTSOUND_ROUNDS = 5

/**
 * First sounds: "Which one starts with B… like Bo?" Bo shows the letter on a tile and three
 * things (two for the youngest); tapping one says its name, and the right one gets "B… B is for
 * ball!". The things in a question always start with different sounds; from level 3 one of them
 * starts with a sound that's easy to mix up (b/d/p, m/n…).
 */
@Composable
fun FirstSounds(s: PlayScope) {
    var round by remember { mutableIntStateOf(0) }
    val random = remember { Random(System.nanoTime()) }
    val co = rememberCoroutineScope()
    val targets = remember { Alphabet.firstSoundTargets(s.lang, FIRSTSOUND_ROUNDS, random) }
    val target = targets[round]
    val options = remember(round) { Alphabet.firstSoundRound(s.lang, target, if (s.level == 0) 2 else 3, s.level, random) }

    LaunchedEffect(Unit) { Sprites.preload(Alphabet.of(s.lang).map { it.sprite }) }
    LaunchedEffect(round) {
        s.rounds(round, FIRSTSOUND_ROUNDS)
        if (round == 0) s.afterSpeech() else delay(300)
        s.prompt("firstsound_q_${target.id}")
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        LetterTile(target.letter, TILE_COLORS[(target.letter.code) % TILE_COLORS.size], round, Modifier.padding(top = 8.dp))
        Box(Modifier.fillMaxSize().padding(start = 180.dp, bottom = 90.dp), contentAlignment = Alignment.Center) {
            ChoiceRow(
                s, options, isRight = { it == target },
                cardSize = 230.dp, spacing = 40.dp,
                onTapSay = { "name_${it.thing}" },
                onRight = { _, first ->
                    s.practised("sound:${target.id}")
                    s.roundDone(first)
                    co.launch {
                        s.sayAndWait("abc_${target.id}") // "B… B is for ball!"
                        delay(300)
                        if (round + 1 >= FIRSTSOUND_ROUNDS) {
                            s.rounds(FIRSTSOUND_ROUNDS, FIRSTSOUND_ROUNDS)
                            s.finish()
                        } else round++
                    }
                },
            ) { Sprite(it.sprite, Modifier.size(180.dp)) }
        }
    }
}

/** The letter being asked about, on a tile that pops in for each new question. */
@Composable
private fun LetterTile(letter: Char, color: androidx.compose.ui.graphics.Color, key: Int, modifier: Modifier) {
    val pop = remember(key) { Animatable(0.6f) }
    LaunchedEffect(key) { pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)) }
    SoftCard(
        color,
        modifier.size(150.dp, 160.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = ((pop.value - 0.6f) / 0.4f).coerceIn(0f, 1f) },
        corner = 36.dp,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            BasicText(letter.toString(), style = Type.clay(88.sp, Palette.cream))
        }
    }
}
