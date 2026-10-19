package dev.framio.meadow.worlds.habits

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.DragItem
import dev.framio.meadow.ui.Mood
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.dropZone
import dev.framio.meadow.ui.floaty
import dev.framio.meadow.ui.halo
import dev.framio.meadow.ui.hintPulse
import dev.framio.meadow.ui.rememberDragArena
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

private const val ROUNDS = 3
private const val TOY = 132f

/** Where each home stands in the room (dp, content area) and how big. */
private data class HomeSpot(val x: Float, val y: Float, val size: Float)

private val HOME_SPOTS = mapOf(
    Home.Shelf to HomeSpot(250f, 96f, 270f),
    Home.Bath to HomeSpot(560f, 196f, 210f),
    Home.ToyBox to HomeSpot(950f, 150f, 310f),
)
private val SPINES = listOf(Color(0xFFE2705F), Color(0xFF73A6DB), Color(0xFF82C074), Color(0xFFF3D266), Color(0xFFA98DCC))

/**
 * Tidy up time: a cosy room with toys all over the rug. Each toy has a home (the toy box, the
 * bookshelf, the bath basket): Moss names one at a time, the child carries it home by dragging or
 * tap-then-tap. When everything is put away the room sparkles softly. Higher levels: more toys,
 * more homes.
 */
@Composable
fun TidyUp(s: PlayScope) {
    var round by remember { mutableIntStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        awaitQuiet(s)
        ready = true
    }
    if (!ready) return
    key(round) {
        TidyRound(s, round) { first ->
            s.roundDone(first)
            if (round + 1 >= ROUNDS) {
                s.rounds(ROUNDS, ROUNDS)
                s.finish()
            } else round++
        }
    }
}

@Composable
private fun TidyRound(s: PlayScope, round: Int, onDone: (Boolean) -> Unit) {
    val level = remember { s.level }
    val homes = remember { tidyHomes(level) }
    val toys = remember { tidyRound(level, Random(System.nanoTime() + round)) }
    val spots = remember { tidySpots(toys.size, round * 31 + toys.size) }
    val put = remember { mutableStateListOf<Toy>() }
    var helpFor by remember { mutableStateOf<Toy?>(null) }
    var misses by remember { mutableIntStateOf(0) }
    var tidy by remember { mutableStateOf(false) }
    val arena = rememberDragArena()
    val co = rememberCoroutineScope()
    val next = toys.firstOrNull { it !in put }

    LaunchedEffect(Unit) {
        s.rounds(round, ROUNDS)
        delay(400)
        s.sayAndWait(if (round == 0) "tidy_start" else "tidy_again")
        next?.let { s.prompt("tidy_${it.id}") }
    }
    LaunchedEffect(arena) {
        snapshotFlow { arena.selected ?: arena.dragging }.collect { id -> toys.firstOrNull { it.id == id }?.let { s.say("tidy_${it.id}") } }
    }

    fun drop(t: Toy, zone: Any): Boolean {
        if (zone != t.home) {
            misses++
            helpFor = t
            s.sfx(Sound.Hmm)
            s.say("tidy_${t.id}")
            return false
        }
        put += t
        helpFor = null
        s.sfx(Sound.Place)
        s.practised("habit:tidy")
        val left = toys.firstOrNull { it !in put }
        if (left == null) {
            tidy = true
            s.prompt()
            co.launch {
                delay(400)
                s.sfx(Sound.Sparkle, 0.6f)
                s.friendMood = Mood.Happy
                s.sayAndWait("tidy_done_${round % 3 + 1}")
                delay(800)
                onDone(misses == 0)
            }
        } else {
            co.launch {
                s.sfx(Sound.Good, 0.6f)
                delay(500)
                s.prompt("tidy_${left.id}")
            }
        }
        return true
    }

    // homes
    for (h in homes) {
        val spot = HOME_SPOTS.getValue(h)
        val hint = helpFor?.home == h || (s.hint && next?.home == h && helpFor == null)
        Box(
            Modifier
                .offset(spot.x.dp, spot.y.dp)
                .size(spot.size.dp)
                .halo(arena.hover == h, Palette.honey, (spot.size / 2.4f).dp)
                .hintPulse(hint, Palette.honey, (spot.size / 2.4f).dp)
                .dropZone(arena, h),
        ) {
            Sprite(h.sprite, Modifier.size(spot.size.dp))
            HomeContents(h, put.filter { it.home == h }, spot.size)
        }
    }
    // toys on the rug
    toys.forEachIndexed { i, t ->
        if (t in put) return@forEachIndexed
        val (fx, fy) = spots[i]
        key(t.id) {
            val enter = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                delay(120L * i)
                enter.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
            }
            Box(
                Modifier
                    .offset((fx * 1280f - TOY / 2).dp, (fy * 688f - TOY / 2).dp)
                    .graphicsLayer { alpha = enter.value; scaleX = 0.8f + 0.2f * enter.value; scaleY = 0.8f + 0.2f * enter.value },
            ) {
                DragItem(
                    arena, t.id,
                    Modifier.size(TOY.dp).hintPulse(helpFor == t || (s.hint && next == t && helpFor == null), Palette.honey, 70.dp),
                    corner = 70.dp, onDrop = { z -> drop(t, z) },
                ) {
                    Sprite(t.sprite, Modifier.size(TOY.dp).graphicsLayer { rotationZ = ((i * 37) % 21 - 10).toFloat() })
                }
            }
        }
    }
    if (tidy) Sparkles(TIDY_SPARKLES, Modifier.size(1280.dp, 688.dp), size = 26f)
}

private val TIDY_SPARKLES = listOf(0.3f to 0.55f, 0.48f to 0.7f, 0.62f to 0.5f, 0.82f to 0.3f, 0.24f to 0.2f, 0.55f to 0.2f, 0.72f to 0.78f)

/** What's been put away: toys peek out of the boxes, books stand on the shelves as spines. */
@Composable
private fun HomeContents(h: Home, inside: List<Toy>, size: Float) {
    if (h == Home.Shelf) {
        inside.forEachIndexed { i, _ ->
            val row = i % 2
            val col = i / 2
            Box(
                Modifier
                    .offset((size * 0.22f + col * size * 0.09f).dp, (size * (if (row == 0) 0.27f else 0.56f)).dp)
                    .size((size * 0.075f).dp, (size * 0.19f).dp)
                    .drawBehind { drawRoundRect(SPINES[i % SPINES.size], cornerRadius = CornerRadius(6.dp.toPx())) },
            )
        }
        return
    }
    inside.forEachIndexed { i, t ->
        val n = inside.size.coerceAtLeast(1)
        val fx = 0.2f + 0.6f * (if (n == 1) 0.5f else i / (n - 1f))
        val s = size * 0.36f
        Sprite(
            t.sprite,
            Modifier
                .offset((fx * size - s / 2).dp, (size * (if (h == Home.Bath) 0.2f else 0.19f) + (i % 2) * 8f).dp)
                .size(s.dp)
                .floaty(i + 5, 2.dp),
        )
    }
}
