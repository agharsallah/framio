package dev.framio.meadow.worlds.numbers

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.core.Friend
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.DragItem
import dev.framio.meadow.ui.FriendView
import dev.framio.meadow.ui.Mood
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.dropZone
import dev.framio.meadow.ui.halo
import dev.framio.meadow.ui.hintPulse
import dev.framio.meadow.ui.pressable
import dev.framio.meadow.ui.rememberDragArena
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

private const val NEST = "nest"

/**
 * Feed Pip: Pip sits in the nest and asks for an exact number of berries ("I'd like three
 * berries, please!"). The child drags (or taps, then taps the nest) berries from the bush; each
 * one is counted aloud as Pip munches. Too many? Pip kindly asks to tap one to take it back.
 * Counting out a set of N is a step beyond counting what's there.
 */
@Composable
fun FeedPip(s: PlayScope) {
    val total = 5
    var round by remember { mutableIntStateOf(0) }
    val want = remember(round) { feedRange(s.level).random(Random(round * 31 + System.nanoTime().toInt())) }
    val nest = remember(round) { mutableStateListOf<Int>() }
    var over by remember(round) { mutableStateOf(false) }   // went too many at least once this round
    var locked by remember(round) { mutableStateOf(false) }
    var eating by remember(round) { mutableStateOf(false) }
    var pipMood by remember { mutableStateOf(Mood.Wave) }
    val arena = rememberDragArena()
    val co = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var moodJob by remember { mutableStateOf<Job?>(null) }

    fun munch() {
        pipMood = Mood.Happy
        moodJob?.cancel()
        moodJob = co.launch { delay(1200); pipMood = Mood.Idle }
    }

    fun done() {
        locked = true
        co.launch {
            eating = true
            s.sfx(Sound.Pop, 0.7f)
            munch()
            s.sayAndWait("feed_yum_${1 + Random.nextInt(3)}")
            s.practised("count:$want")
            s.roundDone(firstTry = !over)
            delay(500)
            if (round + 1 >= total) {
                s.rounds(total, total)
                s.finish()
            } else round++
        }
    }

    // exactly right: wait a moment (the child may still be adding), then Pip eats
    fun settle() {
        settleJob?.cancel()
        settleJob = co.launch {
            delay(1500)
            if (feedStatus(nest.size, want) == FeedStatus.Exact && !locked) done()
        }
    }

    fun add(i: Int) {
        nest += i
        val c = nest.size
        s.sfx(Sound.Place)
        munch()
        when (feedStatus(c, want)) {
            FeedStatus.Under -> s.say("num_$c")
            FeedStatus.Exact -> {
                s.say("num_$c")
                settle()
            }
            FeedStatus.TooMany -> {
                over = true
                settleJob?.cancel()
                s.say("num_$c", "feed_too_many")
            }
        }
    }

    fun takeBack(i: Int) {
        if (locked) return
        nest.remove(i)
        s.sfx(Sound.Pick)
        val c = nest.size
        if (c > 0) s.say("num_$c")
        if (feedStatus(c, want) == FeedStatus.Exact) settle() else settleJob?.cancel()
    }

    LaunchedEffect(round) {
        s.rounds(round, total)
        pipMood = Mood.Wave
        if (round == 0) {
            s.awaitQuiet()
            s.prompt("feed_want_$want", "feed_how")
        } else {
            delay(400)
            s.prompt("feed_want_$want")
        }
        delay(1500)
        if (pipMood == Mood.Wave) pipMood = Mood.Idle
    }

    Box(Modifier.fillMaxSize()) {
        // the berry bush
        Box(Modifier.offset(40.dp, 70.dp).size(600.dp)) {
            Sprite("nu_bush", Modifier.fillMaxSize())
            val firstOnBush = (0 until BUSH_BERRIES).firstOrNull { it !in nest }
            bushSpots().forEachIndexed { i, (fx, fy) ->
                if (i in nest) return@forEachIndexed
                androidx.compose.runtime.key(round, i) {
                    BushBerry(
                        appearDelay = 60L * i,
                        modifier = Modifier.offset(600.dp * fx - 70.dp, 600.dp * fy - 70.dp + 14.dp).size(140.dp),
                    ) {
                        DragItem(
                            arena, id = i, enabled = !locked, corner = 70.dp,
                            modifier = Modifier
                                .fillMaxSize()
                                .hintPulse(s.hint && i == firstOnBush && feedStatus(nest.size, want) == FeedStatus.Under && !locked, Palette.honey, 70.dp),
                            onDrop = { zone -> if (zone == NEST && !locked) { add(i); true } else false },
                        ) { Sprite("nu_berry", Modifier.fillMaxSize().padding(16.dp)) }
                    }
                }
            }
        }

        // Pip in the nest; the whole corner takes the berries
        Box(
            Modifier
                .offset(690.dp, 30.dp)
                .size(560.dp, 640.dp)
                .halo(arena.hover == NEST || (arena.selected != null && !locked), Palette.honey, 120.dp)
                .dropZone(arena, NEST),
        ) {
            val nestTop = 300.dp
            Sprite("nu_nest_back", Modifier.offset(0.dp, nestTop).size(560.dp, 350.dp))
            FriendView(
                Friend.Pip,
                Modifier.offset(95.dp, 166.dp).size(370.dp),
                mood = pipMood,
                onTap = { if (arena.selected != null) arena.dropSelectedOn(NEST) else s.repeatPrompt() },
            )
            // the back row (6th berry on) first, so the front row sits in front of it
            val order = (5 until nest.size) + (0 until minOf(5, nest.size))
            for (k in order) {
                val i = nest[k]
                androidx.compose.runtime.key(i) {
                    val (fx, fy) = nestSpot(k)
                    NestBerry(
                        eating = eating,
                        hint = feedStatus(nest.size, want) == FeedStatus.TooMany,
                        modifier = Modifier.offset(560.dp * fx - 44.dp, nestTop + 350.dp * fy - 56.dp).size(88.dp),
                    ) { takeBack(i) }
                }
            }
            Sprite("nu_nest_front", Modifier.offset(0.dp, nestTop).size(560.dp, 350.dp))
        }
    }
}

@Composable
private fun BushBerry(appearDelay: Long, modifier: Modifier, content: @Composable () -> Unit) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(appearDelay)
        enter.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }
    Box(modifier.graphicsLayer { alpha = enter.value; scaleX = 0.7f + 0.3f * enter.value; scaleY = 0.7f + 0.3f * enter.value }) { content() }
}

/** A berry in the nest: drops in softly; tap it to put it back on the bush; shrinks away when eaten. */
@Composable
private fun NestBerry(eating: Boolean, hint: Boolean, modifier: Modifier, onTap: () -> Unit) {
    val drop = remember { Animatable(-40f) }
    LaunchedEffect(Unit) { drop.animateTo(0f, tween(300, easing = FastOutSlowInEasing)) }
    val gone = remember { Animatable(0f) }
    LaunchedEffect(eating) { if (eating) gone.animateTo(1f, tween(550, easing = FastOutSlowInEasing)) }
    Box(
        modifier
            .graphicsLayer {
                translationY = (drop.value - gone.value * 120f) * density
                val sc = 1f - gone.value
                scaleX = sc
                scaleY = sc
                alpha = 1f - gone.value
            }
            .hintPulse(hint && !eating, Palette.rose, 48.dp)
            .pressable(enabled = !eating, sound = null, onTap = onTap),
    ) { Sprite("nu_berry", Modifier.fillMaxSize()) }
}
