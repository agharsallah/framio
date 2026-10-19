package dev.framio.meadow.worlds.puzzles

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.DragItem
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.dropZone
import dev.framio.meadow.ui.hintPulse
import dev.framio.meadow.ui.rememberDragArena
import dev.framio.meadow.ui.rememberSprite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Shadow match: soft dark outlines in a row on a cream mat, the things themselves below. Drag (or
 * tap, then tap) each thing onto its shadow; it settles in, takes its colours and is named. Three
 * very different things for the youngest, later four with look-alikes (apple, orange, tomato).
 */
@Composable
fun ShadowMatch(s: PlayScope) {
    val total = 4
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) { s.rounds(round, total) }
    key(round) {
        ShadowRound(s, first = round == 0) { firstTry ->
            s.roundDone(firstTry)
            if (round + 1 >= total) {
                s.rounds(total, total)
                s.finish()
            } else round++
        }
    }
}

@Composable
private fun ShadowRound(s: PlayScope, first: Boolean, onDone: (Boolean) -> Unit) {
    val things = remember { shadowRound(s.level, Random(System.nanoTime())) }
    val tray = remember { shadowTrayOrder(things.size, Random(System.nanoTime())) }
    val matched = remember { mutableStateListOf<String>() }
    var misses by remember { mutableIntStateOf(0) }
    var lastTried by remember { mutableStateOf<String?>(null) }
    val arena = rememberDragArena()
    val co = rememberCoroutineScope()
    val n = things.size
    val size: Dp = if (n <= 3) 210.dp else 180.dp
    val gap: Dp = if (n <= 3) 56.dp else 40.dp

    LaunchedEffect(Unit) {
        delay(if (first) 2400 else 500)
        s.prompt("shadows_prompt")
    }

    fun drop(thing: String, zone: Any): Boolean {
        lastTried = thing
        if (zone != thing) {
            misses++
            s.notQuite()
            return false
        }
        matched += thing
        s.sfx(Sound.Place)
        s.practised("shadow:$thing")
        s.showHint(false)
        co.launch {
            s.sayAndWait("name_$thing")
            if (matched.size == n) {
                delay(250)
                s.praiseAndWait()
                delay(500)
                onDone(misses == 0)
            }
        }
        return true
    }

    // the stuck child's thing (or the first one left) and its shadow both glow
    val hintThing = if (!s.hint) null else (lastTried?.takeIf { it !in matched } ?: things.firstOrNull { it !in matched })

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = 200.dp, end = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        SoftCard(Palette.cream, Modifier.padding(horizontal = 8.dp), corner = 48.dp) {
            Row(Modifier.padding(horizontal = 30.dp, vertical = 22.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
                things.forEachIndexed { i, t ->
                    ShadowSlot(
                        thing = t,
                        matched = t in matched,
                        hint = t == hintThing,
                        appearDelay = i * 110L,
                        modifier = Modifier.size(size).dropZone(arena, t),
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)) {
            tray.forEachIndexed { k, idx ->
                val t = things[idx]
                Box(Modifier.size(size)) {
                    if (t !in matched) {
                        TrayThing(t, appearDelay = 300L + k * 110L) {
                            DragItem(
                                arena, t,
                                Modifier
                                    .fillMaxSize()
                                    .hintPulse(t == hintThing, Palette.honey, size / 2),
                                corner = size / 2,
                                onDrop = { zone -> drop(t, zone) },
                            ) {
                                Sprite("ob_$t", Modifier.fillMaxSize(0.9f))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(36.dp))
    }
}

@Composable
private fun TrayThing(thing: String, appearDelay: Long, content: @Composable () -> Unit) {
    val enter = remember(thing) { Animatable(0f) }
    LaunchedEffect(thing) {
        delay(appearDelay)
        enter.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 50f }) { content() }
}

/**
 * The thing's outline as a soft dark shape, made once in software: colours replaced, the faint
 * floor shadow (alpha below ~0.5) cut away, edges kept smooth.
 */
private fun silhouette(src: ImageBitmap): ImageBitmap {
    val b = src.asAndroidBitmap()
    val px = IntArray(b.width * b.height)
    b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
    for (i in px.indices) {
        val a = px[i] ushr 24
        val na = ((a - 128) * 255 / 100).coerceIn(0, 255)
        px[i] = (na shl 24) or 0x4A4058
    }
    val out = android.graphics.Bitmap.createBitmap(b.width, b.height, android.graphics.Bitmap.Config.ARGB_8888)
    out.setPixels(px, 0, b.width, 0, 0, b.width, b.height)
    return out.asImageBitmap()
}

@Composable
private fun ShadowSlot(thing: String, matched: Boolean, hint: Boolean, appearDelay: Long, modifier: Modifier) {
    val bmp = rememberSprite("ob_$thing")
    val shade by produceState<ImageBitmap?>(null, bmp) {
        val src = bmp ?: return@produceState
        value = withContext(Dispatchers.Default) { silhouette(src) }
    }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(appearDelay)
        enter.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
    }
    val color = remember { Animatable(0f) }
    val settle = remember { Animatable(1f) }
    LaunchedEffect(matched) {
        if (matched) {
            launch { settle.snapTo(1.12f); settle.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow)) }
            color.animateTo(1f, tween(450))
        }
    }
    Box(modifier.hintPulse(hint, Palette.honey, 60.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize(0.9f)
                .graphicsLayer {
                    alpha = enter.value
                    scaleX = settle.value
                    scaleY = settle.value
                },
        ) {
            val b = bmp ?: return@Canvas
            val c = color.value
            val sh = shade
            if (c < 1f && sh != null) {
                // a soft edge: the outline a few times, slightly offset, under the main one
                val a = (1f - c)
                val d = 2.5f * density
                for (o in listOf(Offset(-d, 0f), Offset(d, 0f), Offset(0f, -d), Offset(0f, d))) {
                    drawContext.canvas.save()
                    drawContext.canvas.translate(o.x, o.y)
                    drawFit(sh, 0.1f * a)
                    drawContext.canvas.restore()
                }
                drawFit(sh, 0.55f * a)
            }
            if (c > 0f) drawFit(b, c)
        }
    }
}

/** Like drawSpriteFit, with a colour filter. */
internal fun DrawScope.drawFit(bmp: ImageBitmap, alpha: Float = 1f, filter: ColorFilter? = null) {
    val sc = minOf(size.width / bmp.width, size.height / bmp.height)
    val w = (bmp.width * sc).toInt()
    val h = (bmp.height * sc).toInt()
    drawImage(
        bmp, IntOffset.Zero, IntSize(bmp.width, bmp.height),
        IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()), IntSize(w, h),
        alpha = alpha, colorFilter = filter, filterQuality = FilterQuality.Medium,
    )
}
