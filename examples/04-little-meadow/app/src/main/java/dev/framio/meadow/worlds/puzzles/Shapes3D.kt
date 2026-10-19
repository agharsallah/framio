package dev.framio.meadow.worlds.puzzles

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.framio.meadow.audio.Instrument
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.play.ChoiceRow
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.Icon
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.RoundButton
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.Sprites
import dev.framio.meadow.ui.halo
import dev.framio.meadow.ui.pressable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Spin the shapes: the frame's GPU at play. One big soft clay shape at a time, drawn live in OpenGL:
 * drag to spin it, tap and it does its thing (the ball rolls, the cube tumbles, the cone spins).
 * Pip names it with an everyday twin ("A cup is a cylinder!"); then a quick "Which one is the
 * cylinder?" with three shape cards. Three or four shapes per play.
 */
@Composable
fun SpinShapes(s: PlayScope) {
    val order = remember { solidOrder(Random(System.nanoTime())) }
    val total = remember { solidRounds(s.level) }
    var round by remember { mutableIntStateOf(0) }
    var quiz by remember { mutableStateOf(false) }
    val solid = order[round]
    val co = rememberCoroutineScope()

    LaunchedEffect(Unit) { Sprites.preload(Solid.entries.map { "pz_shape_${it.id}" } + Solid.entries.map { it.example }) }
    LaunchedEffect(round, quiz) { s.rounds(round, total) }

    if (!quiz) {
        MeetShape(s, solid, first = round == 0, onNext = { quiz = true })
    } else {
        // level 2+: sometimes ask about a shape met earlier
        val target = remember(round) {
            val r = Random(System.nanoTime())
            if (s.level >= 2 && round > 0 && r.nextInt(3) == 0) order[r.nextInt(round)] else solid
        }
        val choices = remember(round) { solidChoices(target, s.level, Random(System.nanoTime())) }
        LaunchedEffect(round) {
            delay(250)
            s.prompt("shapes3d_which_${target.id}")
        }
        Box(Modifier.fillMaxSize().padding(start = 200.dp), contentAlignment = Alignment.Center) {
            ChoiceRow(s, choices, isRight = { it == target }, cardSize = 230.dp, spacing = 44.dp, onRight = { _, first ->
                s.practised("shape3d:${target.id}")
                s.roundDone(first)
                co.launch {
                    delay(300)
                    if (round + 1 >= total) {
                        s.rounds(total, total)
                        s.finish()
                    } else {
                        round++
                        quiz = false
                    }
                }
            }) {
                Sprite("pz_shape_${it.id}", Modifier.fillMaxSize(0.82f))
            }
        }
    }
}

@Composable
private fun MeetShape(s: PlayScope, solid: Solid, first: Boolean, onNext: () -> Unit) {
    var view by remember { mutableStateOf<ShapeView?>(null) }
    var said by remember(solid) { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val co = rememberCoroutineScope()
    val enter = remember(solid) { Animatable(0f) }
    var tricks by remember(solid) { mutableIntStateOf(0) }

    LaunchedEffect(solid) {
        delay(if (first) 1800 else 300)
        enter.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
        val line = "shapes3d_meet_${solid.id}"
        s.prompt(line)
        delay(s.app.voice.durationMs(line) + 400L)
        if (first) s.sayAndWait("shapes3d_try")
        said = true
    }
    // GL objects go before the view: fade the shape out, free buffers on the GL thread, then leave
    fun next() {
        if (leaving) return
        leaving = true
        co.launch {
            view?.vanish()
            delay(320)
            view?.release()
            delay(60)
            onNext()
        }
    }
    DisposableEffect(Unit) { onDispose { view?.let { it.release(); it.onPause() } } }

    Box(Modifier.fillMaxSize()) {
        // the GL surface paints its piece of the backdrop itself, so wait until that is decoded
        val bg by produceState<ImageBitmap?>(null) { value = Sprites.load(s.play.backdrop ?: PuzzlesWorld.world.backdrop) }
        val backdrop = bg
        if (backdrop != null) {
            AndroidView(
                factory = { ctx ->
                    ShapeView(ctx, backdrop.asAndroidBitmap()) {
                        tricks++
                        s.sfx(Sound.Whoosh, 0.5f)
                        s.app.sfx.note(Instrument.Marimba, 72 + (tricks % 3) * 4, 0.5f)
                    }.also { view = it; it.show(solid) }
                },
                // clipped: the SurfaceView punches its hole only here, not through the whole screen
                modifier = Modifier.offset(250.dp, 0.dp).size(560.dp, 560.dp).clipToBounds(),
            )
        }
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 70.dp, end = 70.dp)
                .graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 40f },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SoftCard(
                Palette.cream,
                Modifier
                    .size(250.dp)
                    .pressable { s.say("shapes3d_meet_${solid.id}") },
                corner = 48.dp,
            ) {
                Sprite(solid.example, Modifier.fillMaxSize(0.8f))
            }
            Spacer(Modifier.height(60.dp))
            // no endless pulse here: every Compose frame competes with the GL view for the Mali-400
            val pop = remember { Animatable(1f) }
            LaunchedEffect(said) {
                if (said) {
                    pop.animateTo(1.15f, tween(260, easing = FastOutSlowInEasing))
                    pop.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
                }
            }
            RoundButton(
                Icon.Next,
                Modifier
                    .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                    .halo(said && !leaving, Palette.honey, 60.dp),
                size = 120.dp, color = Palette.rose, iconColor = Palette.cream,
                enabled = said && !leaving, onTap = ::next,
            )
        }
    }
}
