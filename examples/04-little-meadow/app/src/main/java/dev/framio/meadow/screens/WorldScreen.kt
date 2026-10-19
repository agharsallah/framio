package dev.framio.meadow.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.framio.meadow.LocalApp
import dev.framio.meadow.audio.Track
import dev.framio.meadow.core.Gate
import dev.framio.meadow.play.Play
import dev.framio.meadow.play.World
import dev.framio.meadow.ui.Backdrop
import dev.framio.meadow.ui.FriendView
import dev.framio.meadow.ui.Icon
import dev.framio.meadow.ui.Mood
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.RoundButton
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.Type
import dev.framio.meadow.ui.floaty
import dev.framio.meadow.ui.pressable
import kotlinx.coroutines.delay

/** A friend's place: the friend on the left, at most six big activity cards on the right. */
@Composable
fun WorldScreen(world: World, onPlay: (Play) -> Unit, onBack: () -> Unit) {
    val app = LocalApp.current
    val settings by app.settings.flow.collectAsState()
    val gate by app.session.gate.collectAsState()
    val plays = world.plays.filter { it.minAge <= settings.age && it.id !in settings.hidden && (gate != Gate.Night || it.quiet) }

    LaunchedEffect(world.id) {
        app.music.play(if (world.quiet) Track.Calm else Track.Meadow)
        app.screen = "world:${world.id}"
        app.playId = null
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(world.backdrop)
        RoundButton(Icon.Home, Modifier.padding(24.dp), size = 80.dp, onTap = onBack)
        FriendView(
            world.friend,
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 30.dp, bottom = 20.dp)
                .size(360.dp),
            mood = Mood.Wave,
            onTap = { app.voice.say("${world.friend.id}_giggle_${(1..3).random()}") },
        )
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 48.dp, top = 40.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            plays.chunked(if (plays.size <= 4) 2 else 3).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    row.forEachIndexed { c, p ->
                        PlayCard(p, world, index = r * 3 + c, lang = settings.lang) {
                            app.voice.say("play_${p.id}")
                            onPlay(p)
                        }
                    }
                }
            }
            if (plays.isEmpty()) Spacer(Modifier.width(600.dp))
        }
    }
}

@Composable
private fun PlayCard(p: Play, world: World, index: Int, lang: dev.framio.meadow.core.Lang, onTap: () -> Unit) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(100L * index + 150)
        enter.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    SoftCard(
        Palette.cream,
        Modifier
            .size(236.dp)
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 50f
            }
            .pressable(onTap = onTap),
        corner = 44.dp,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Sprite(p.icon, Modifier.size(150.dp).floaty(index, 3.dp))
            Spacer(Modifier.height(4.dp))
            BasicText(p.title[lang], style = Type.label.copy(fontSize = 21.sp, textAlign = TextAlign.Center, color = Palette.ink))
        }
    }
}
