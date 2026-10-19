package dev.framio.meadow.worlds.colors

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.DragItem
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.Sprites
import dev.framio.meadow.ui.dropZone
import dev.framio.meadow.ui.floaty
import dev.framio.meadow.ui.hintPulse
import dev.framio.meadow.ui.rememberDragArena
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Where the lid's hole line sits in the co_sorter sprite (fractions of its 2:1 box). */
private const val LID_Y = 0.315f
private const val LID_W = 0.86f

/**
 * Shape sorter: Tuka's clay box with holes in its lid. Drag a chunky shape (or tap it, then tap a
 * hole) into its home: it drops in with a soft plop and is named ("Star!"). The wrong hole sends it
 * floating back and Tuka helps: "That's the circle. Where's the star's home?" Three shapes for the
 * youngest up to six; rectangle, oval and diamond join at level 2, the hexagon at level 4.
 */
@Composable
fun ShapeSorter(s: PlayScope) {
    val total = 4
    var round by remember { mutableIntStateOf(0) }
    val spec = remember(round) { sorterRound(s.level, Random(System.nanoTime())) }
    val placed = remember(round) { mutableStateListOf<SortShape>() }
    var misses by remember(round) { mutableIntStateOf(0) }
    var hintShape by remember(round) { mutableStateOf<SortShape?>(null) }
    val arena = rememberDragArena()
    val co = rememberCoroutineScope()

    LaunchedEffect(round) {
        Sprites.preload(listOf("co_sorter") + spec.pieces.map { it.sprite } + spec.holes.map { it.hole })
        s.rounds(round, total)
        s.waitForIntro(round)
        s.prompt("sorter_go")
    }
    // name a shape when it's picked up (tap) or lifted (drag)
    val held = (arena.dragging ?: arena.selected) as? Int
    LaunchedEffect(held) {
        held?.let { spec.pieces.getOrNull(it)?.let { p -> s.say(p.shape.line) } }
    }

    fun drop(piece: Piece, zone: Any): Boolean {
        val hole = (zone as? String)?.removePrefix("hole:")?.let { id -> SortShape.entries.firstOrNull { it.id == id } } ?: return false
        if (hole == piece.shape) {
            placed += piece.shape
            hintShape = null
            s.sfx(Sound.Place)
            s.say(piece.shape.line)
            s.practised("shape:${piece.shape.id}")
            if (placed.size == spec.pieces.size) co.launch {
                delay(900)
                s.friendMood = dev.framio.meadow.ui.Mood.Happy
                s.sayAndWait("sorter_all")
                s.praiseAndWait()
                s.roundDone(firstTry = misses == 0)
                delay(300)
                if (round + 1 >= total) {
                    s.rounds(total, total)
                    s.finish()
                } else round++
            }
            return true
        }
        misses++
        s.sfx(Sound.Hmm)
        s.say("sorter_thats_${hole.id}", "sorter_where_${piece.shape.id}")
        if (misses >= 2) hintShape = piece.shape
        return false
    }

    val n = spec.pieces.size
    val pieceSize: Dp = if (n <= 4) 170.dp else 150.dp
    val holeSize: Dp = minOf(175.dp, (820.dp - 10.dp * (n - 1)) / n)
    // the idle hint points at the first shape still waiting, and its hole
    val idleShape = if (s.hint) spec.pieces.firstOrNull { it.shape !in placed }?.shape else null
    val pointAt = hintShape ?: idleShape

    Box(Modifier.fillMaxSize().padding(start = 236.dp, end = 20.dp)) {
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(if (n <= 4) 34.dp else 16.dp),
        ) {
            spec.pieces.forEachIndexed { i, piece ->
                key(round, i) {
                    if (piece.shape in placed) {
                        Spacer(Modifier.size(pieceSize))
                    } else {
                        PieceView(arena, i, piece, pieceSize, hint = pointAt == piece.shape) { zone -> drop(piece, zone) }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).size(960.dp, 480.dp)) {
            Sprite("co_sorter", Modifier.fillMaxSize())
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 480.dp * LID_Y - holeSize / 2),
                horizontalArrangement = Arrangement.spacedBy((960.dp * LID_W - holeSize * n) / maxOf(1, n - 1)),
            ) {
                spec.holes.forEach { shape ->
                    key(round, shape) {
                        val piece = spec.pieces.first { it.shape == shape }
                        HoleView(shape, piece, holeSize, filled = shape in placed, hint = pointAt == shape, hover = arena.hover == "hole:${shape.id}", modifier = Modifier.dropZone(arena, "hole:${shape.id}"))
                    }
                }
            }
        }
    }
}

@Composable
private fun PieceView(arena: dev.framio.meadow.ui.DragArena, index: Int, piece: Piece, size: Dp, hint: Boolean, onDrop: (Any) -> Boolean) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(100L * index)
        enter.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    DragItem(
        arena, index,
        Modifier
            .size(size)
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * -30f
            }
            .hintPulse(hint, Palette.honey, size / 2),
        corner = size / 2,
        onDrop = onDrop,
    ) {
        Sprite(piece.sprite, Modifier.fillMaxSize().floaty(index * 11 + 5, 3.dp, enabled = arena.dragging != index))
    }
}

@Composable
private fun HoleView(shape: SortShape, piece: Piece, size: Dp, filled: Boolean, hint: Boolean, hover: Boolean, modifier: Modifier) {
    // the shape sinks into its hole: smaller, lower, fading out
    val sink = remember { Animatable(0f) }
    LaunchedEffect(filled) { if (filled) sink.animateTo(1f, tween(750, easing = FastOutSlowInEasing)) }
    Box(
        modifier
            .size(size)
            .hintPulse(hint, Palette.honey, size / 2)
            .drawBehind {
                if (hover || filled) {
                    val a = if (hover) 0.35f else 0.22f * sink.value
                    drawOval(Palette.honey.copy(alpha = a), Offset(-size.toPx() * 0.04f, size.toPx() * 0.12f), Size(this.size.width * 1.08f, this.size.height * 0.76f))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Sprite(shape.hole, Modifier.fillMaxSize())
        if (filled && sink.value < 1f) {
            Sprite(
                piece.sprite,
                Modifier.fillMaxSize().graphicsLayer {
                    val sc = 1f - 0.55f * sink.value
                    scaleX = sc
                    scaleY = sc * (1f - 0.3f * sink.value)
                    translationY = (-50f + 60f * sink.value) * density
                    alpha = 1f - sink.value
                },
            )
        }
    }
}
