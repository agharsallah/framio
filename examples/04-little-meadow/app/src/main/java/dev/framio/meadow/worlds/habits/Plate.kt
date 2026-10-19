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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.DragArena
import dev.framio.meadow.ui.DragItem
import dev.framio.meadow.ui.Mood
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.dropZone
import dev.framio.meadow.ui.halo
import dev.framio.meadow.ui.hintPulse
import dev.framio.meadow.ui.rememberDragArena
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

private const val ROUNDS = 4
private const val EVERY = "every"
private const val SOME = "some"
private const val PLATE = "plate"

/**
 * Healthy plate: first sort foods into the everyday basket (fruit, vegetables, bread, milk…) and
 * the sometimes basket (treats for special days: nothing is "bad"), then fill Moss's plate with a
 * rainbow, one colour at a time. Sorting and rainbow rounds take turns; the level sets how many
 * foods and colours.
 */
@Composable
fun HealthyPlate(s: PlayScope) {
    var round by remember { mutableIntStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        awaitQuiet(s)
        ready = true
    }
    if (!ready) return
    key(round) {
        if (round % 2 == 0) SortRound(s, round) { first -> next(s, round, first) { round++ } }
        else RainbowRound(s, round) { first -> next(s, round, first) { round++ } }
    }
}

private fun next(s: PlayScope, round: Int, firstTry: Boolean, advance: () -> Unit) {
    s.roundDone(firstTry)
    if (round + 1 >= ROUNDS) {
        s.rounds(ROUNDS, ROUNDS)
        s.finish()
    } else advance()
}

/** Remembers the item the child picked up (tap or drag) and says its name. */
@Composable
private fun SayOnPick(s: PlayScope, arena: DragArena, name: (Any) -> String?) {
    LaunchedEffect(arena) {
        snapshotFlow { arena.selected ?: arena.dragging }.collect { id -> id?.let(name)?.let { s.say(it) } }
    }
}

@Composable
private fun SortRound(s: PlayScope, round: Int, onDone: (Boolean) -> Unit) {
    val foods = remember { sortRound(s.level, Random(System.nanoTime() + round)) }
    val placed = remember { mutableStateListOf<Food>() }
    var misses by remember { mutableIntStateOf(0) }
    var helpFor by remember { mutableStateOf<Food?>(null) }
    val arena = rememberDragArena()
    val co = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        s.rounds(round, ROUNDS)
        if (round == 0) {
            delay(500)
            s.sayAndWait("plate_intro")
        }
        s.prompt("plate_sort")
    }
    SayOnPick(s, arena) { id -> foods.firstOrNull { it.id == id }?.let { "name_${it.id}" } }

    fun drop(f: Food, zone: Any): Boolean {
        val right = if (f.kind == FoodKind.Everyday) EVERY else SOME
        if (zone == right) {
            placed += f
            helpFor = null
            s.sfx(Sound.Place)
            s.practised("food:${f.kind.name.lowercase()}")
            if (placed.size == foods.size) {
                co.launch {
                    s.praiseAndWait()
                    delay(400)
                    onDone(misses == 0)
                }
            } else s.say(if (f.kind == FoodKind.Everyday) "plate_yes_every" else "plate_yes_some")
            return true
        }
        misses++
        helpFor = f
        s.sfx(Sound.Hmm)
        s.say(if (f.kind == FoodKind.Everyday) "plate_hmm_every" else "plate_hmm_some")
        return false
    }

    // the two baskets
    val baskets = listOf(EVERY to 300f, SOME to 760f)
    for ((id, x) in baskets) {
        val hint = helpFor?.let { (if (it.kind == FoodKind.Everyday) EVERY else SOME) == id } == true
        Box(
            Modifier
                .offset(x.dp, 330.dp)
                .size(300.dp)
                .halo(arena.hover == id, Palette.honey, 120.dp)
                .hintPulse(hint, Palette.honey, 120.dp)
                .dropZone(arena, id),
        ) {
            Sprite(if (id == EVERY) "hb_basket_every" else "hb_basket_some", Modifier.size(300.dp))
            // what's already in it sits on top, fanned along the rim
            val inside = placed.filter { (if (it.kind == FoodKind.Everyday) EVERY else SOME) == id }
            inside.forEachIndexed { i, f ->
                val fx = 60f + (i % 4) * 46f
                Sprite(f.sprite, Modifier.offset(fx.dp, (40f - (i / 4) * 26f + (i % 2) * 8f).dp).size(96.dp))
            }
        }
    }
    // the foods waiting on a shelf at the top
    val left = foods.filter { it !in placed }
    val cell = 168f
    val startX = (1280f - foods.size * cell) / 2
    foods.forEachIndexed { i, f ->
        if (f in placed) return@forEachIndexed
        key(f.id) {
            FoodItem(arena, f, Modifier.offset((startX + i * cell + 8).dp, 40.dp), hint = helpFor == f || (s.hint && left.firstOrNull() == f), appear = i * 90L) { z -> drop(f, z) }
        }
    }
}

@Composable
private fun FoodItem(arena: DragArena, f: Food, modifier: Modifier, hint: Boolean, appear: Long, size: Float = 150f, onDrop: (Any) -> Boolean) {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(appear)
        enter.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }
    Box(modifier.graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * -30f * density }) {
        DragItem(arena, f.id, Modifier.size(size.dp).hintPulse(hint, Palette.honey, (size / 2).dp), corner = (size / 2).dp, onDrop = onDrop) {
            SoftCard(Palette.cream, Modifier.size(size.dp), corner = (size / 2).dp, elevation = 4.dp) {
                Sprite(f.sprite, Modifier.size((size * 0.8f).dp))
            }
        }
    }
}

/** Where foods sit on the plate, as fractions of it: a ring, like a rainbow wreath. */
private val PLATE_SLOTS = listOf(0.3f to 0.38f, 0.5f to 0.28f, 0.7f to 0.38f, 0.62f to 0.62f, 0.38f to 0.62f)

@Composable
private fun RainbowRound(s: PlayScope, round: Int, onDone: (Boolean) -> Unit) {
    val colors = remember { rainbowColors(s.level) }
    var ci by remember { mutableIntStateOf(0) }
    val onPlate = remember { mutableStateListOf<Food>() }
    var firstTry by remember { mutableStateOf(true) }
    var finished by remember { mutableStateOf(false) }
    val arena = rememberDragArena()
    val co = rememberCoroutineScope()
    val color = colors.getOrNull(ci)
    val choices = remember(ci) { color?.let { rainbowChoices(it, s.level, Random(System.nanoTime() + ci)) } ?: emptyList() }

    LaunchedEffect(ci) {
        s.rounds(round, ROUNDS)
        if (ci == 0) {
            delay(300)
            s.sayAndWait("plate_rainbow_intro")
        }
        color?.let { s.prompt("plate_find_$it") }
    }
    SayOnPick(s, arena) { id -> choices.firstOrNull { it.id == id }?.let { "name_${it.id}" } }

    fun drop(f: Food, zone: Any): Boolean {
        if (zone != PLATE || finished) return false
        if (f.color != color) {
            firstTry = false
            s.notQuite()
            return false
        }
        onPlate += f
        s.sfx(Sound.Place)
        s.practised("color:${f.color}")
        co.launch {
            if (ci + 1 >= colors.size) {
                finished = true
                s.prompt()
                s.friendMood = Mood.Happy
                s.sfx(Sound.Sparkle, 0.6f)
                s.sayAndWait("plate_rainbow_done")
                delay(600)
                onDone(firstTry)
            } else {
                s.praiseAndWait()
                s.showHint(false)
                ci++
            }
        }
        return true
    }

    val plateW = 600f
    val plateH = 428f
    val px = (1280f - plateW) / 2 + 40f
    val py = 248f
    Box(
        Modifier
            .offset(px.dp, py.dp)
            .size(plateW.dp, plateH.dp)
            .halo(arena.hover == PLATE, Palette.honey, 160.dp)
            .dropZone(arena, PLATE),
    ) {
        Sprite("hb_plate", Modifier.size(plateW.dp, plateH.dp))
        onPlate.forEachIndexed { i, f ->
            val (fx, fy) = PLATE_SLOTS[i % PLATE_SLOTS.size]
            Sprite(f.sprite, Modifier.offset((fx * plateW - 60).dp, (fy * plateH - 60).dp).size(120.dp))
        }
        if (finished) Sparkles(listOf(0.25f to 0.3f, 0.5f to 0.18f, 0.76f to 0.32f, 0.7f to 0.72f, 0.3f to 0.7f), Modifier.size(plateW.dp, plateH.dp))
    }
    if (!finished) {
        val cell = 196f
        val startX = (1280f - choices.size * cell) / 2 + 40f
        choices.forEachIndexed { i, f ->
            key(ci, f.id) {
                FoodItem(arena, f, Modifier.offset((startX + i * cell + 8).dp, 44.dp), hint = s.hint && f.color == color, appear = i * 90L, size = 170f) { z -> drop(f, z) }
            }
        }
    }
}
