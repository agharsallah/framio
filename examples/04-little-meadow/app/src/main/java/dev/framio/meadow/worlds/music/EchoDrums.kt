package dev.framio.meadow.worlds.music

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.framio.meadow.audio.Instrument
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.Icon
import dev.framio.meadow.ui.Mood
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.RoundButton
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.hintPulse
import dev.framio.meadow.ui.rememberSprite
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

private class Drum(val sprite: String, val size: Dp, val glow: androidx.compose.ui.graphics.Color)

/** Big drum, small drum, bell: three clearly different voices (low tom, high tom, a ting). */
private val DRUMS = listOf(
    Drum("mu_drum_big", 300.dp, Palette.terracotta),
    Drum("mu_drum_small", 250.dp, Palette.sky),
    Drum("mu_bell", 250.dp, Palette.honey),
)

private enum class Phase { Listening, YourTurn, Waiting }

/**
 * Echo drums: Bo plays a short pattern on two or three friendly drums (they bounce and glow as
 * they sound), then "Your turn!" and the child plays it back. Only the order matters, never the
 * timing. A slip is fine: "Let's listen again" and Bo plays it once more. Patterns grow from two
 * hits to five, and the bell joins as a third drum.
 */
@Composable
fun EchoDrums(s: PlayScope) {
    val total = 5
    var round by remember { mutableIntStateOf(0) }
    val spec = remember(round) {
        val r = Random(round * 977 + System.nanoTime().toInt())
        val drums = echoDrums(s.level)
        drums to echoPattern(echoLength(s.level), drums, r)
    }
    val (drumCount, pattern) = spec
    var phase by remember(round) { mutableStateOf(Phase.Listening) }
    var pos by remember(round) { mutableIntStateOf(0) }
    var misses by remember(round) { mutableIntStateOf(0) }
    var listen by remember(round) { mutableIntStateOf(0) }  // bumps to replay the pattern
    var sayListen by remember(round) { mutableStateOf(true) }
    val hits = remember { List(3) { mutableIntStateOf(0) } }
    val co = rememberCoroutineScope()

    fun strike(d: Int) {
        when (d) {
            0 -> s.sfx(Sound.Drum, 1f, 0.78f)
            1 -> s.sfx(Sound.Drum, 0.9f, 1.45f)
            else -> s.app.sfx.note(Instrument.Bell, 84, 0.9f)
        }
        hits[d].intValue++
    }

    LaunchedEffect(round, listen) {
        phase = Phase.Listening
        pos = 0
        if (round == 0 && listen == 0) {
            s.rounds(0, total)
            s.awaitQuiet()
        } else delay(450)
        if (sayListen) s.sayAndWait("echo_listen")
        sayListen = true
        s.friendMood = Mood.Happy
        delay(300)
        val gap = if (pattern.size <= 3) 720L else 620L
        for (d in pattern) {
            strike(d)
            delay(gap)
        }
        delay(200)
        phase = Phase.YourTurn
        s.prompt("echo_your_turn")
    }

    fun tap(d: Int) {
        if (phase != Phase.YourTurn) return
        strike(d)
        when (echoCheck(pattern, pos, d)) {
            EchoStep.Next -> pos++
            EchoStep.Done -> {
                pos++
                phase = Phase.Waiting
                co.launch {
                    delay(450)
                    s.praiseAndWait()
                    s.practised("pattern:${pattern.size}")
                    s.roundDone(firstTry = misses == 0)
                    delay(400)
                    if (round + 1 >= total) {
                        s.rounds(total, total)
                        s.finish()
                    } else {
                        s.rounds(round + 1, total)
                        round++
                    }
                }
            }
            EchoStep.Wrong -> {
                misses++
                phase = Phase.Waiting
                co.launch {
                    delay(350)
                    s.sfx(Sound.Hmm, 0.6f)
                    s.sayAndWait("echo_again")
                    if (misses >= 2) s.showHint(true)
                    sayListen = false
                    listen++
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxSize().padding(start = 150.dp, bottom = 70.dp),
            horizontalArrangement = Arrangement.spacedBy(56.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // with two drums: the big and the small one; the bell joins in the middle
            val order = if (drumCount == 2) listOf(0, 1) else listOf(0, 2, 1)
            for (d in order) {
                DrumView(
                    DRUMS[d], hits[d].intValue,
                    hint = s.hint && phase == Phase.YourTurn && pattern.getOrNull(pos) == d,
                    enabled = phase == Phase.YourTurn,
                ) { tap(d) }
            }
        }
        RoundButton(
            Icon.Replay, Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 32.dp), size = 100.dp,
            enabled = phase == Phase.YourTurn,
        ) {
            sayListen = true
            listen++
        }
    }
}

/** A drum that bounces, glows and smiles wide each time it sounds ([hit] increases). */
@Composable
private fun DrumView(drum: Drum, hit: Int, hint: Boolean, enabled: Boolean, onTap: () -> Unit) {
    rememberSprite("${drum.sprite}_happy") // decoded up front, so the first smile doesn't blink empty
    val bounce = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }
    var happy by remember { mutableStateOf(false) }
    LaunchedEffect(hit) {
        if (hit == 0) return@LaunchedEffect
        happy = true
        launch {
            glow.snapTo(1f)
            glow.animateTo(0f, tween(600, easing = FastOutSlowInEasing))
        }
        bounce.animateTo(1f, tween(90))
        bounce.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
        delay(150)
        happy = false
    }
    val tap by rememberUpdatedState(onTap)
    Box(
        Modifier
            .size(drum.size)
            .drawBehind {
                val g = glow.value
                if (g > 0.01f) {
                    drawCircle(
                        Brush.radialGradient(listOf(drum.glow.copy(alpha = 0.5f * g), drum.glow.copy(alpha = 0f)), center = Offset(size.width / 2, size.height * 0.55f), radius = size.minDimension * 0.62f),
                        size.minDimension * 0.62f, Offset(size.width / 2, size.height * 0.55f),
                    )
                }
            }
            .hintPulse(hint, Palette.honey, drum.size / 2)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var last = 0L
                awaitEachGesture {
                    awaitFirstDown()
                    val now = SystemClock.uptimeMillis()
                    if (now - last > 120) { // answers on touch-down, like a real drum
                        last = now
                        tap()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Sprite(
            if (happy) "${drum.sprite}_happy" else drum.sprite,
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val b = bounce.value
                    scaleY = 1f - 0.08f * b
                    scaleX = 1f + 0.05f * b
                    transformOrigin = TransformOrigin(0.5f, 0.9f)
                },
        )
    }
}
