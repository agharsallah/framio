package dev.framio.meadow.worlds.quiet

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.framio.meadow.core.Friend
import dev.framio.meadow.play.ChoiceRow
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.Mood
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.halo
import dev.framio.meadow.ui.lighten
import dev.framio.meadow.ui.pressable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

private sealed interface FeelStep {
    data object Explore : FeelStep
    data class Ask(val round: Int) : FeelStep
    data class Help(val round: Int) : FeelStep
}

/**
 * Feelings: first explore, tap a face and that friend shows the feeling while Luma names it and says
 * what helps ("When I feel sad, a hug helps."). Then four little picture stories ("Tuka dropped her
 * ice cream. How does she feel?"): choose the face, and for hard feelings choose what can help
 * (every helper offered is a good one). All feelings are okay. Levels add subtler feelings
 * (surprised, sleepy, then proud and calm) and a third face to choose from.
 */
@Composable
fun Feelings(s: PlayScope) {
    val total = 4
    var step by remember { mutableStateOf<FeelStep>(FeelStep.Explore) }
    val scenarios = remember { pickScenarios(s.level, total, Random(System.nanoTime())) }

    LaunchedEffect(Unit) {
        delay(s.app.voice.durationMs("feelings_intro").toLong() + 300)
        if (step == FeelStep.Explore) s.prompt("feelings_explore")
    }

    AnimatedContent(
        step is FeelStep.Explore,
        transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(400)) },
        label = "part",
    ) { exploring ->
        if (exploring) {
            Explore(s, onDone = { step = FeelStep.Ask(0) })
        } else {
            Rounds(s, scenarios, step, onStep = { step = it })
        }
    }
}

@Composable
private fun Explore(s: PlayScope, onDone: () -> Unit) {
    val feelings = remember { exploreFeelings(s.level) }
    val need = remember { exploreNeeded(s.level) }
    var stageFriend by remember { mutableStateOf(Friend.Tuka) }
    var stageLook by remember { mutableStateOf("wave") }
    var seen by remember { mutableStateOf(setOf<Feeling>()) }
    var leaving by remember { mutableStateOf(false) }
    val co = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        // the stage: one big friend showing the feeling
        Box(
            Modifier
                .offset(220.dp, 230.dp)
                .size(400.dp),
        ) { FeelingFriendFade(stageFriend, stageLook, Modifier.fillMaxSize()) }

        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 48.dp)
                .offset(y = (-10).dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            feelings.chunked(if (feelings.size > 4) 3 else 2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    row.forEach { f ->
                        val i = feelings.indexOf(f)
                        val friend = FEELING_FRIENDS[i % FEELING_FRIENDS.size]
                        SoftCard(
                            if (f in seen) Palette.lavender.lighten(0.55f) else Palette.cream,
                            Modifier
                                .size(176.dp)
                                .halo(stageLook == f.id && stageFriend == friend, Palette.lavender)
                                .pressable(enabled = !leaving) {
                                    stageFriend = friend
                                    stageLook = f.id
                                    seen = seen + f
                                    s.practised("feeling:${f.id}")
                                    if (seen.size >= need && !leaving) {
                                        leaving = true
                                        co.launch {
                                            s.sayAndWait("feelings_about_${f.id}")
                                            delay(500)
                                            s.sayAndWait("feelings_ready")
                                            delay(300)
                                            onDone()
                                        }
                                    } else {
                                        s.say("feelings_about_${f.id}")
                                    }
                                },
                        ) {
                            Sprite(feelingSprite(friend, f), Modifier.size(156.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Rounds(s: PlayScope, scenarios: List<Scenario>, step: FeelStep, onStep: (FeelStep) -> Unit) {
    val round = when (step) {
        is FeelStep.Ask -> step.round
        is FeelStep.Help -> step.round
        else -> 0
    }
    val sc = scenarios[round.coerceAtMost(scenarios.lastIndex)]
    var look by remember(round) { mutableStateOf("idle") }
    val faces = remember(round) { feelingChoices(sc.feeling, s.level, Random(System.nanoTime())) }
    val co = rememberCoroutineScope()

    LaunchedEffect(step) {
        when (step) {
            is FeelStep.Ask -> {
                s.rounds(round, scenarios.size)
                delay(700)
                s.prompt("feelings_s_${sc.id}")
            }
            is FeelStep.Help -> s.prompt("feelings_whathelps")
            else -> Unit
        }
    }

    fun next() {
        if (round + 1 >= scenarios.size) {
            s.rounds(scenarios.size, scenarios.size)
            s.finish()
        } else onStep(FeelStep.Ask(round + 1))
    }

    Box(Modifier.fillMaxSize()) {
        StoryCard(sc, look, Modifier.align(Alignment.TopCenter).padding(top = 6.dp))
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp)) {
            AnimatedContent(
                step is FeelStep.Help,
                transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(300)) },
                label = "q",
            ) { helping ->
                if (!helping) {
                    ChoiceRow(
                        s, faces, isRight = { it == sc.feeling }, cardSize = 186.dp, spacing = 34.dp,
                        onTapSay = { "feelings_name_${it.id}" },
                        onRight = { f, first ->
                            look = f.id
                            s.roundDone(first)
                            s.practised("feeling:${f.id}")
                            co.launch {
                                s.sayAndWait("feelings_yes_${f.id}")
                                delay(400)
                                if (f.positive) {
                                    s.sayAndWait("feelings_share_${f.id}")
                                    delay(600)
                                    next()
                                } else onStep(FeelStep.Help(round))
                            }
                        },
                    ) { Sprite(feelingSprite(sc.friend, it), Modifier.size(166.dp)) }
                } else {
                    ChoiceRow(
                        s, helpersFor(sc.feeling), isRight = { true }, cardSize = 186.dp, spacing = 34.dp,
                        cardColor = { Palette.cream },
                        onTapSay = { "feelings_help_${it.id}" },
                        onRight = { h, _ ->
                            look = if (h == Helper.Rest) "calm" else "happy"
                            s.friendMood = Mood.Happy
                            co.launch {
                                s.sayAndWait("feelings_better_${sc.friend.id}")
                                delay(700)
                                next()
                            }
                        },
                    ) { Sprite(it.sprite, Modifier.size(140.dp)) }
                }
            }
        }
    }
}

/** The little picture story: the friend in the situation, on a soft card. */
@Composable
private fun StoryCard(sc: Scenario, look: String, modifier: Modifier) {
    val enter = remember(sc) { Animatable(0f) }
    LaunchedEffect(sc) { enter.animateTo(1f, tween(600, easing = FastOutSlowInEasing)) }
    SoftCard(
        if (sc.dark) Palette.night else Palette.cream,
        modifier
            .size(660.dp, 340.dp)
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 24f
            },
        corner = 36.dp,
    ) {
        Box(
            Modifier
                .padding(10.dp)
                .fillMaxSize()
                .roundedPicture(28.dp, if (sc.dark) Palette.night else Palette.cream),
        ) {
            Sprite(if (sc.dark) "bg_qt_sky" else "bg_meadow", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Scene(
                listOf(Actor(sc.friend, look, 0.34f, 0.97f, 0.8f)),
                sc.props,
                Modifier.fillMaxSize(),
            )
        }
    }
}
