package dev.framio.meadow.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.framio.meadow.LocalApp
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.audio.Track
import dev.framio.meadow.core.Friend
import dev.framio.meadow.core.Gate
import dev.framio.meadow.core.Lang
import dev.framio.meadow.core.Txt
import dev.framio.meadow.ui.Backdrop
import dev.framio.meadow.ui.FallingPetals
import dev.framio.meadow.ui.FriendView
import dev.framio.meadow.ui.Mood
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.ProgressDots
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.Type
import dev.framio.meadow.ui.floaty
import kotlinx.coroutines.delay

/** Swallows every touch: nothing behind a gate screen can be reached. */
private fun Modifier.blockTouches() = pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }

/**
 * Locked or out of minutes: a sleepy, friendly scene, no buttons. A grown-up can come in through
 * the gear (or the dashboard). Tapping the friend gets a soft "see you later".
 */
@Composable
fun RestScreen(gate: Gate, onParent: () -> Unit) {
    val app = LocalApp.current
    val lang = app.settings.value.lang
    LaunchedEffect(gate) {
        app.music.play(Track.Calm)
        app.screen = "rest"
        delay(600)
        app.voice.say(if (gate == Gate.DailyLimit) "rest_limit" else "rest_locked")
    }
    Box(
        Modifier
            .fillMaxSize()
            .blockTouches(),
    ) {
        Backdrop("bg_meadow")
        Box(Modifier.fillMaxSize().background(Palette.lavender.copy(alpha = 0.35f)))
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            FriendView(Friend.Pip, Modifier.size(380.dp), mood = Mood.Sleep, talking = false, onTap = { app.voice.say("rest_tap") })
            Spacer(Modifier.width(30.dp))
            Column {
                val t = if (gate == Gate.DailyLimit) Txt("Time to play outside!", "Zeit, draußen zu spielen!") else Txt("Pip is resting", "Pip ruht sich aus")
                BasicText(t[lang], style = Type.title.copy(fontSize = 44.sp))
                Spacer(Modifier.height(12.dp))
                BasicText(Txt("See you later!", "Bis später!")[lang], style = Type.body.copy(fontSize = 28.sp, color = Palette.inkSoft))
            }
        }
        ParentGateButton(onOpen = onParent, modifier = Modifier.align(Alignment.TopEnd).padding(24.dp))
    }
}

private data class Move(val voice: String, val sprite: String, val title: Txt, val beats: Int)

private val MOVES = listOf(
    Move("wiggle_tree", "ob_tree", Txt("Stretch up tall like a tree", "Streck dich groß wie ein Baum"), 3),
    Move("wiggle_frog", "ob_duck", Txt("Hop like a little frog", "Hüpf wie ein kleiner Frosch"), 5),
    Move("wiggle_shake", "ob_star", Txt("Shake your hands like stars", "Schüttel die Hände wie Sterne"), 4),
    Move("wiggle_turn", "ob_balloon_blue", Txt("Turn around slowly", "Dreh dich langsam um"), 3),
    Move("wiggle_breathe", "ob_flower", Txt("Smell the flower… and blow", "Riech an der Blume… und puste"), 3),
    Move("wiggle_water", "ob_cup", Txt("Time for a sip of water", "Zeit für einen Schluck Wasser"), 2),
)

/**
 * The wiggle break, after a stretch of play: six short movements with Pip, about a minute, then
 * a sip of water. Getting up and moving is the point; there is nothing to tap.
 */
@Composable
fun BreakScreen() {
    val app = LocalApp.current
    val lang = app.settings.value.lang
    var step by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        app.screen = "break"
        app.music.play(Track.Meadow)
        app.voice.sayAndWait("wiggle_start")
        for (i in MOVES.indices) {
            step = i
            app.voice.sayAndWait(MOVES[i].voice)
            repeat(MOVES[i].beats) {
                delay(1100)
                app.sfx.play(Sound.Shaker, 0.6f)
            }
            delay(800)
        }
        step = MOVES.size
        app.sfx.play(Sound.Sparkle)
        app.voice.sayAndWait("wiggle_done")
        delay(800)
        app.session.breakDone()
    }
    Box(Modifier.fillMaxSize().blockTouches()) {
        Backdrop("bg_meadow")
        if (step >= MOVES.size) FallingPetals(Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.TopCenter).padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(Txt("Wiggle break!", "Bewegungspause!")[lang], style = Type.title.copy(fontSize = 44.sp))
            Spacer(Modifier.height(12.dp))
            ProgressDots(step.coerceIn(0, MOVES.size), MOVES.size, color = Palette.sage)
        }
        Row(Modifier.align(Alignment.Center).padding(top = 80.dp), verticalAlignment = Alignment.CenterVertically) {
            FriendView(Friend.Pip, Modifier.size(340.dp), mood = if (step >= MOVES.size) Mood.Happy else Mood.Wave)
            Spacer(Modifier.width(40.dp))
            AnimatedContent(step, transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(300)) }, label = "move") { s ->
                val m = MOVES.getOrNull(s)
                if (m != null) SoftCard(Palette.cream, Modifier.size(width = 480.dp, height = 380.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Sprite(m.sprite, Modifier.size(220.dp).floaty(s, 10.dp))
                        Spacer(Modifier.height(10.dp))
                        BasicText(m.title[lang], style = Type.label.copy(fontSize = 28.sp, textAlign = TextAlign.Center), modifier = Modifier.padding(horizontal = 20.dp))
                    }
                } else Spacer(Modifier.size(width = 480.dp, height = 380.dp))
            }
        }
    }
}

/** Shown for a moment when a grown-up sends a star from the dashboard. */
@Composable
fun StarFromAfar(onDone: () -> Unit) {
    val app = LocalApp.current
    LaunchedEffect(Unit) {
        app.sfx.play(Sound.Sparkle)
        app.voice.sayAndWait("star_from_afar")
        delay(1500)
        onDone()
    }
    Box(Modifier.fillMaxSize().background(Palette.paper.copy(alpha = 0.7f)).blockTouches(), contentAlignment = Alignment.Center) {
        FallingPetals(Modifier.fillMaxSize(), seed = 9)
        Sprite("ob_star", Modifier.size(320.dp).floaty(2, 8.dp))
    }
}

internal fun Lang.pick(en: String, de: String) = if (this == Lang.DE) de else en
