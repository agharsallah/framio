package dev.framio.meadow.worlds.words

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.Icon
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.RoundButton
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.Sprites
import dev.framio.meadow.ui.Type
import dev.framio.meadow.ui.darken
import dev.framio.meadow.ui.floaty
import dev.framio.meadow.ui.hintPulse
import dev.framio.meadow.ui.lighten
import dev.framio.meadow.ui.pressable
import dev.framio.meadow.ui.wobble
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Muted rainbow for the letter tiles, repeating along the garden. */
internal val TILE_COLORS = listOf(Palette.rose, Palette.honey, Palette.sage, Palette.sky, Palette.lavender, Palette.terracotta, Palette.mint)

internal const val ABC_PER_ROUND = 5
internal const val ABC_ROUNDS = 3

/**
 * Seek mode (from level 3, in the last round): Bo names a letter and the child finds it among the
 * tiles in view. Picks one not yet grown when possible. Pure, unit-tested.
 */
internal fun seekTarget(visible: IntRange, explored: Set<Int>, r: Random): Int {
    val fresh = visible.filter { it !in explored }
    return if (fresh.isNotEmpty()) fresh.random(r) else visible.random(r)
}

/** Which tiles are fully in view for a scroll position (all in px). */
internal fun visibleTiles(scroll: Int, viewport: Int, start: Int, pitch: Int, tile: Int, count: Int): IntRange {
    val first = ((scroll - start + pitch - 1).coerceAtLeast(0) / pitch).coerceAtMost(count - 1)
    var last = first
    while (last + 1 < count && start + (last + 1) * pitch + tile - scroll <= viewport) last++
    return first..last
}

/**
 * ABC garden: a long garden bed of soft clay letter tiles, A to Z, to scroll through. Tapping a tile
 * makes it pop and the thing it stands for grows out of it ("A… A is for apple!"), and the garden
 * keeps everything that has grown. Five new letters make a round; three rounds and the garden is
 * done. From level 3 the last round turns around: Bo names a letter and the child finds it.
 */
@Composable
fun AbcGarden(s: PlayScope) {
    val words = remember(s.lang) { Alphabet.of(s.lang) }
    val explored = remember { mutableStateMapOf<Int, Int>() } // tile → order it grew in
    var newest by remember { mutableIntStateOf(-1) }
    var busy by remember { mutableStateOf(false) }
    var seek by remember { mutableStateOf<Int?>(null) }
    val wobbles = remember { mutableStateMapOf<Int, Int>() }
    val pops = remember { mutableStateMapOf<Int, Int>() }
    val scroll = rememberScrollState()
    val co = rememberCoroutineScope()
    val density = LocalDensity.current
    val random = remember { Random(System.nanoTime()) }

    val tileW = 150.dp
    val gap = 22.dp
    val padStart = 120.dp
    val pitchPx = with(density) { (tileW + gap).roundToPx() }
    val tilePx = with(density) { tileW.roundToPx() }
    val startPx = with(density) { padStart.roundToPx() }
    val viewportPx = with(density) { 1280.dp.roundToPx() }

    fun seekMode() = s.level >= 3 && explored.size >= ABC_PER_ROUND * (ABC_ROUNDS - 1)

    fun askSeek() {
        val vis = visibleTiles(scroll.value, viewportPx, startPx, pitchPx, tilePx, words.size)
        val t = seekTarget(vis, explored.keys, random)
        seek = t
        s.prompt("abc_find", "letter_${words[t].id}")
    }

    LaunchedEffect(Unit) {
        s.rounds(0, ABC_ROUNDS)
        Sprites.preload(words.map { it.sprite })
    }
    LaunchedEffect(Unit) {
        s.afterSpeech()
        s.prompt("abc_tap")
    }

    fun grow(i: Int) {
        val w = words[i]
        val first = i !in explored
        if (first) explored[i] = explored.size
        newest = i
        pops[i] = (pops[i] ?: 0) + 1
        s.sfx(Sound.Pop, 0.8f, 0.9f + (i % 7) * 0.05f)
        s.practised("letter:${w.id}")
        val n = explored.size
        val roundEnds = first && n % ABC_PER_ROUND == 0
        if (!roundEnds && !seekMode()) {
            s.say("abc_${w.id}") // a quick next tap simply takes over: no waiting in free play
            return
        }
        busy = true
        co.launch {
            s.sayAndWait("abc_${w.id}")
            if (roundEnds) {
                val round = n / ABC_PER_ROUND
                s.roundDone(firstTry = true)
                s.rounds(round, ABC_ROUNDS)
                if (round >= ABC_ROUNDS) {
                    delay(300)
                    s.finish()
                    return@launch
                }
                s.praiseAndWait()
                delay(200)
                if (seekMode()) askSeek() else s.prompt("abc_more")
            } else {
                delay(250)
                askSeek()
            }
            busy = false
        }
    }

    fun tap(i: Int) {
        if (busy) return
        val target = seek
        if (target == null) {
            grow(i)
            return
        }
        if (i == target) {
            seek = null
            grow(i)
        } else {
            wobbles[i] = (wobbles[i] ?: 0) + 1
            s.notQuite()
        }
    }

    // read the scroll position only through these, so scrolling doesn't recompose the garden
    val atStart by remember { derivedStateOf { scroll.value <= 0 } }
    val atEnd by remember { derivedStateOf { scroll.value >= scroll.maxValue } }
    val hintTile = when {
        !s.hint -> -1
        seek != null -> seek!!
        else -> firstFresh(explored.keys, scroll, startPx, pitchPx, words.size)
    }

    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(top = 96.dp)
                .horizontalScroll(scroll)
                .drawBehind {
                    // the garden bed the tiles stand in: a soft strip of earth and grass
                    val top = size.height - 30.dp.toPx()
                    val x0 = 60.dp.toPx()
                    drawRoundRect(Color(0xFFD9BC98), Offset(x0, top), Size(size.width - 2 * x0, 54.dp.toPx()), CornerRadius(27.dp.toPx()))
                    drawRoundRect(Color(0xFFC9A57E), Offset(x0 + 12.dp.toPx(), top + 30.dp.toPx()), Size(size.width - 2 * x0 - 24.dp.toPx(), 18.dp.toPx()), CornerRadius(9.dp.toPx()))
                    drawRoundRect(Palette.sage.lighten(0.3f), Offset(x0 - 6.dp.toPx(), top - 8.dp.toPx()), Size(size.width - 2 * x0 + 12.dp.toPx(), 20.dp.toPx()), CornerRadius(10.dp.toPx()))
                }
                .padding(start = padStart, end = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            words.forEachIndexed { i, w ->
                Plot(
                    word = w, color = TILE_COLORS[i % TILE_COLORS.size], grown = i in explored, newest = newest == i,
                    pop = pops[i] ?: 0, wobble = wobbles[i] ?: 0, hint = hintTile == i,
                    onTap = { tap(i) },
                )
            }
        }
        // page buttons for little hands that don't swipe yet (bottom right, clear of Bo)
        Row(Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            RoundButton(Icon.Prev, size = 96.dp, color = Palette.cream, enabled = !atStart) {
                co.launch { scroll.animateScrollBy(-pitchPx * 5f) }
            }
            RoundButton(Icon.Next, size = 96.dp, color = Palette.cream, enabled = !atEnd) {
                co.launch { scroll.animateScrollBy(pitchPx * 5f) }
            }
        }
    }
}

private suspend fun ScrollState.animateScrollBy(px: Float) =
    animateScrollTo((value + px).toInt().coerceIn(0, maxValue), tween(600, easing = FastOutSlowInEasing))

/** The first tile in view that hasn't grown yet: what the idle hint points at. */
private fun firstFresh(explored: Set<Int>, scroll: ScrollState, start: Int, pitch: Int, count: Int): Int {
    val first = ((scroll.value - start).coerceAtLeast(0) / pitch).coerceAtMost(count - 1)
    return (first until count).firstOrNull { it !in explored } ?: -1
}

/** One letter in the garden: the tile, and above it what has grown from it. */
@Composable
private fun Plot(word: LetterWord, color: Color, grown: Boolean, newest: Boolean, pop: Int, wobble: Int, hint: Boolean, onTap: () -> Unit) {
    val growT = remember { Animatable(if (grown) 1f else 0f) }
    LaunchedEffect(grown) {
        if (grown && growT.value < 1f) growT.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessVeryLow))
    }
    val popT = remember { Animatable(1f) }
    LaunchedEffect(pop) {
        if (pop > 0) {
            popT.animateTo(1.1f, tween(160, easing = FastOutSlowInEasing))
            popT.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow))
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(150.dp, 170.dp), contentAlignment = Alignment.BottomCenter) {
            if (grown) {
                Sprite(
                    word.sprite,
                    Modifier
                        .size(150.dp)
                        .graphicsLayer {
                            val g = growT.value
                            scaleX = 0.3f + 0.7f * g
                            scaleY = 0.3f + 0.7f * g
                            alpha = g.coerceIn(0f, 1f)
                            translationY = (1f - g) * 90.dp.toPx()
                        }
                        .floaty(word.letter.code, 3.dp, enabled = newest)
                        .pressable(sound = null, onTap = onTap),
                )
            }
        }
        SoftCard(
            color,
            Modifier
                .offset(y = (-4).dp)
                .size(150.dp, 166.dp)
                .graphicsLayer { scaleX = popT.value; scaleY = popT.value }
                .wobble(wobble)
                .hintPulse(hint, Palette.honey, 36.dp)
                .pressable(onTap = onTap),
            corner = 36.dp,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(word.letter.toString(), style = Type.clay(92.sp, Palette.cream))
                BasicText(word.letter.lowercase(), style = Type.clay(34.sp, color.darken(0.5f)), modifier = Modifier.offset(y = (-14).dp))
            }
        }
    }
}
