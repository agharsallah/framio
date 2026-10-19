package dev.framio.meadow.worlds.quiet

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.core.Friend
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.FriendView
import dev.framio.meadow.ui.Icon
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.RoundButton
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.Type
import dev.framio.meadow.ui.hintPulse
import dev.framio.meadow.ui.rememberSlowClock
import dev.framio.meadow.ui.softShadow
import dev.framio.meadow.ui.pressable
import kotlinx.coroutines.delay

/**
 * Stories: four original picture stories, read aloud by Luma. Pick a cover; each page is a little
 * scene (backdrop, friends with feelings, props) drifting very slowly, its narration plays, and a
 * moment later the page turns softly by itself (or with the big arrows). Little ones (level 0–1)
 * get the shorter telling. One story is one round: "The end. Sweet dreams."
 */
@Composable
fun Stories(s: PlayScope) {
    var story by remember { mutableStateOf<Story?>(null) }

    LaunchedEffect(Unit) {
        delay(s.app.voice.durationMs("stories_intro").toLong() + 300)
        if (story == null) s.prompt("stories_pick")
    }

    AnimatedContent(
        story,
        transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(500)) },
        label = "story",
    ) { st ->
        if (st == null) StoryPicker(s) { story = it } else Reader(s, st)
    }
}

@Composable
private fun StoryPicker(s: PlayScope, onPick: (Story) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .align(Alignment.Center)
                .offset(y = (-36).dp),
            horizontalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            StoryBook.all.forEachIndexed { i, st ->
                SoftCard(
                    Palette.cream,
                    Modifier
                        .size(270.dp, 316.dp)
                        .hintPulse(s.hint && i == 0)
                        .pressable { onPick(st) },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .padding(12.dp)
                                .size(246.dp, 230.dp)
                                .roundedPicture(28.dp, Palette.cream),
                        ) {
                            Sprite(st.coverBg, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            FeelingFriend(
                                st.cover.friend, st.cover.look,
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .offset(y = 6.dp)
                                    .size(190.dp),
                            )
                        }
                        BasicText(
                            st.title[s.lang], style = Type.label.copy(textAlign = TextAlign.Center),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp),
                        )
                    }
                }
            }
        }
        FriendView(
            Friend.Luma,
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = 4.dp)
                .size(190.dp),
            onTap = { s.repeatPrompt() },
        )
    }
}

@Composable
private fun Reader(s: PlayScope, st: Story) {
    val visible = remember(st) { st.pagesFor(s.level) }
    var pos by remember(st) { mutableIntStateOf(0) }
    var ended by remember(st) { mutableStateOf(false) }

    LaunchedEffect(st, pos) {
        s.rounds(pos + 1, visible.size)
        if (pos == 0) {
            s.prompt() // the story reads itself: no idle reminders
            s.sayAndWait(st.titleLine)
            delay(500)
        } else {
            s.sfx(Sound.Whoosh, 0.3f)
            delay(900)
        }
        s.sayAndWait(st.narration(visible[pos]))
        if (pos == visible.lastIndex) {
            delay(900)
            ended = true
            s.sayAndWait("stories_end")
            s.practised("story:${st.id}")
            s.roundDone(firstTry = true)
            delay(400)
            s.finish()
        } else {
            delay(1500)
            pos++
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 18.dp)
                .size(1040.dp, 610.dp)
                .softShadow(40.dp, 8.dp)
                .drawBehind { drawRoundRect(Palette.cream, cornerRadius = CornerRadius(40.dp.toPx())) }
                .padding(12.dp)
                .roundedPicture(30.dp, Palette.cream),
        ) {
            Crossfade(pos, animationSpec = tween(900), label = "page") { p ->
                PageView(st.pages[visible[p]], drift = if (p % 2 == 0) 1f else -1f, still = s.reducedMotion)
            }
        }
        RoundButton(
            Icon.Prev,
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 14.dp)
                .offset(y = (-26).dp),
            size = 100.dp, enabled = pos > 0 && !ended, sound = null,
        ) { if (pos > 0 && !ended) pos-- }
        RoundButton(
            Icon.Next,
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp)
                .offset(y = (-26).dp),
            size = 100.dp, enabled = pos < visible.lastIndex, sound = null,
        ) { if (pos < visible.lastIndex) pos++ }
    }
}

/** One picture, with a very slow, subtle Ken Burns drift. */
@Composable
private fun PageView(page: Page, drift: Float, still: Boolean) {
    // ~1 dp a second: a 10 Hz clock is plenty and keeps the frame mostly idle
    val clock = rememberSlowClock(100)
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val k = if (still) 0.5f else (clock.value / 26_000f).coerceAtMost(1f)
                val sc = 1.04f + 0.045f * k
                scaleX = sc
                scaleY = sc
                translationX = drift * (k - 0.5f) * 22.dp.toPx()
                translationY = -(k - 0.5f) * 8.dp.toPx()
            },
    ) {
        Sprite(page.bg, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Scene(
            page.actors, page.props,
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = (1f - enter.value) * 14.dp.toPx() },
        )
        if (page.rain) SoftRain(Modifier.fillMaxSize())
    }
}
