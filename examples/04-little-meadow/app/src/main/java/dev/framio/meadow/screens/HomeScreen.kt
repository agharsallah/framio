package dev.framio.meadow.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.framio.meadow.LocalApp
import dev.framio.meadow.audio.Track
import dev.framio.meadow.core.Clock
import dev.framio.meadow.core.Gate
import dev.framio.meadow.core.PartOfDay
import dev.framio.meadow.core.Pose
import dev.framio.meadow.play.World
import dev.framio.meadow.ui.Backdrop
import dev.framio.meadow.ui.FriendView
import dev.framio.meadow.ui.Icon
import dev.framio.meadow.ui.Mood
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.RoundButton
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.Sprites
import dev.framio.meadow.ui.Type
import dev.framio.meadow.ui.lighten
import dev.framio.meadow.ui.pressable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The meadow: one big card per friend's place, in a row the child can swipe. Pip greets by the
 * time of day (and by name, if a grown-up recorded it). At bedtime only the quiet tree is awake;
 * the other friends are asleep on their cards.
 */
@Composable
fun HomeScreen(
    worlds: List<World>,
    onWorld: (World) -> Unit,
    onStickers: () -> Unit,
    onMailbox: () -> Unit,
    onParent: () -> Unit,
) {
    val app = LocalApp.current
    val settings by app.settings.flow.collectAsState()
    val gate by app.session.gate.collectAsState()
    val family by app.family.items.collectAsState()
    val night = gate == Gate.Night
    val unseen = family.count { !it.seen }

    LaunchedEffect(Unit) {
        // decode every card's sprites up front, so none pops in during a swipe
        launch { Sprites.preload(worlds.flatMap { w -> w.props.take(2) + w.friend.sprite(Pose.Idle) + w.friend.sprite(Pose.Sleep) }) }
        app.music.play(if (night) Track.Night else Track.Meadow)
        delay(500)
        val greet = when {
            night -> "home_night"
            !Clock.plausible() -> "home_hello"
            else -> when (Clock.partOfDay(Clock.minuteOfDay())) {
                PartOfDay.Morning -> "home_morning"
                PartOfDay.Afternoon -> "home_afternoon"
                PartOfDay.Evening, PartOfDay.Night -> "home_evening"
            }
        }
        if (app.family.nameClip() != null) app.voice.say(greet, "@name", "home_what") else app.voice.say(greet, "home_what")
    }

    Box(Modifier.fillMaxSize()) {
        Backdrop(if (night) "bg_night" else "bg_meadow")
        FriendView(
            if (night) dev.framio.meadow.core.Friend.Luma else dev.framio.meadow.core.Friend.Pip,
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp)
                .size(230.dp),
            mood = Mood.Wave,
            onTap = { app.voice.say(if (night) "luma_giggle_1" else "pip_giggle_${(1..3).random()}") },
        )
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, end = 24.dp, top = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val hello = if (settings.childName.isNotBlank()) "${if (settings.lang == dev.framio.meadow.core.Lang.DE) "Hallo" else "Hi"}, ${settings.childName}!" else "Little Meadow"
                BasicText(hello, style = Type.title.copy(color = if (night) Palette.moon else Palette.ink))
                Spacer(Modifier.weight(1f))
                if (unseen > 0 || family.isNotEmpty()) {
                    Box {
                        RoundButton(Icon.Mail, size = 76.dp, color = Palette.sky.lighten(0.3f), hint = unseen > 0, onTap = onMailbox)
                        if (unseen > 0) Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(Palette.red),
                            contentAlignment = Alignment.Center,
                        ) { BasicText("$unseen", style = Type.small.copy(color = Palette.cream, fontSize = 15.sp)) }
                    }
                    Spacer(Modifier.width(18.dp))
                }
                RoundButton(Icon.Star, size = 76.dp, color = Palette.honey.lighten(0.25f), iconColor = Palette.cream, onTap = onStickers)
                Spacer(Modifier.width(18.dp))
                ParentGateButton(onOpen = onParent)
            }
            // a plain scrolling Row, not a LazyRow: seven cards composed once, so a swipe only moves
            // finished layers (composing and uploading sprites mid-swipe cost ~70 ms frames on the frame)
            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 250.dp, end = 60.dp, top = 24.dp, bottom = 70.dp),
                horizontalArrangement = Arrangement.spacedBy(34.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                worlds.forEachIndexed { i, w ->
                    val asleep = night && !w.quiet
                    WorldCard(w, i, asleep) {
                        if (asleep) app.voice.say("home_asleep") else {
                            app.voice.say(w.intro)
                            onWorld(w)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorldCard(w: World, index: Int, asleep: Boolean, onTap: () -> Unit) {
    val app = LocalApp.current
    val lang = app.settings.flow.collectAsState().value.lang
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(120L * index)
        enter.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
    }
    SoftCard(
        w.color.lighten(0.35f),
        Modifier
            .width(300.dp)
            .height(440.dp)
            .graphicsLayer {
                alpha = enter.value * if (asleep) 0.7f else 1f
                translationY = (1f - enter.value) * 60f
            }
            .pressable(onTap = onTap),
        corner = 48.dp,
        contentAlignment = Alignment.TopCenter,
    ) {
        // two little props behind the friend
        w.props.take(2).forEachIndexed { i, p ->
            Sprite(
                p,
                Modifier
                    .size(96.dp)
                    .offset(x = if (i == 0) (-86).dp else 86.dp, y = 150.dp),
                alpha = if (asleep) 0.5f else 1f,
            )
        }
        Sprite(w.friend.sprite(if (asleep) Pose.Sleep else Pose.Idle), Modifier.padding(top = 30.dp).size(250.dp))
        BasicText(
            w.title[lang],
            style = Type.label.copy(fontSize = 26.sp, textAlign = TextAlign.Center),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 30.dp),
        )
    }
}
