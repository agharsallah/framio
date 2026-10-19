package dev.framio.meadow.worlds.colors

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.core.Friend
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.FriendView
import dev.framio.meadow.ui.Icon
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.RoundButton
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.darken
import dev.framio.meadow.ui.pressable
import dev.framio.meadow.worlds.habits.DRAWINGS_DIR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Coloring pages: six original line-art pictures (Pip, a flower pot, a house in the sun, a fish in
 * the waves, Moss under a rainbow, Bo with a balloon). Pick a colour, tap a part: it fills softly
 * from the finger outwards. One tap fills one part, so even a two-year-old can do it. When every
 * part has a colour (or the child taps "done") the picture is kept for the grown-ups' gallery.
 */
@Composable
fun ColoringPages(s: PlayScope) {
    val fills = remember { HashMap<String, SnapshotStateList<Long?>>() }
    var open by remember { mutableStateOf<Int?>(null) }
    fun fillsOf(p: Page) = fills.getOrPut(p.id) { mutableStateListOf<Long?>().apply { repeat(p.parts.size) { add(null) } } }

    val o = open
    if (o == null) {
        PagePicker(s, ::fillsOf) { i ->
            open = i
            s.say("coloring_page_${COLORING_PAGES[i].id}")
        }
    } else {
        ColoringSheet(s, COLORING_PAGES[o], fillsOf(COLORING_PAGES[o]), onBack = { open = null })
    }
}

@Composable
private fun PagePicker(s: PlayScope, fillsOf: (Page) -> SnapshotStateList<Long?>, onPick: (Int) -> Unit) {
    LaunchedEffect(Unit) {
        s.waitForIntro(0)
        s.prompt("coloring_pick")
    }
    Box(Modifier.fillMaxSize()) {
        FriendView(Friend.Tuka, Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 4.dp).size(210.dp), mood = s.friendMood, onTap = { s.repeatPrompt() })
        Column(
            Modifier.align(Alignment.Center).padding(start = 210.dp).offset(y = (-16).dp),
            verticalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            for (row in 0..1) Row(horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                for (col in 0..2) {
                    val i = row * 3 + col
                    val page = COLORING_PAGES[i]
                    val art = remember(page.id) { PageArt(page, outline = 22f) }
                    val f = fillsOf(page)
                    val enter = remember { Animatable(0f) }
                    LaunchedEffect(Unit) {
                        delay(70L * i)
                        enter.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
                    }
                    SoftCard(
                        Palette.cream,
                        Modifier
                            .size(300.dp, 222.dp)
                            .graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 30f }
                            .pressable(sound = Sound.Whoosh) { onPick(i) },
                        corner = 30.dp,
                    ) {
                        Canvas(Modifier.fillMaxSize().padding(14.dp)) {
                            val sc = minOf(size.width / PAGE_W, size.height / PAGE_H)
                            drawIntoCanvas { c ->
                                val nc = c.nativeCanvas
                                nc.save()
                                nc.translate((size.width - PAGE_W * sc) / 2, (size.height - PAGE_H * sc) / 2)
                                nc.scale(sc, sc)
                                art.draw(nc, { k -> (f[k] ?: 0xFFFFFDF8).toInt() })
                                nc.restore()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColoringSheet(s: PlayScope, page: Page, fills: SnapshotStateList<Long?>, onBack: () -> Unit) {
    val art = remember(page.id) { PageArt(page) }
    var color by remember { mutableStateOf(COLORING_COLORS[0].second) }
    var revealPart by remember { mutableIntStateOf(-1) }
    var revealFrom by remember { mutableStateOf(0L) }
    var revealAt by remember { mutableStateOf(Offset.Zero) }
    val reveal = remember { Animatable(1f) }
    var done by remember { mutableStateOf(false) }
    val co = rememberCoroutineScope()

    LaunchedEffect(page.id) {
        delay(1300)
        s.prompt("coloring_go")
    }

    fun save() {
        val snapshot = fills.toList()
        co.launch(Dispatchers.Default) {
            val bmp = art.render({ k -> (snapshot[k] ?: 0xFFFFFDF8).toInt() })
            withContext(Dispatchers.IO) {
                try {
                    val dir = File(s.app.context.filesDir, DRAWINGS_DIR).apply { mkdirs() }
                    File(dir, drawingName(System.currentTimeMillis())).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } catch (e: Exception) {
                    android.util.Log.w(dev.framio.meadow.core.TAG, "saving coloring page failed", e)
                } finally {
                    bmp.recycle()
                }
            }
        }
    }

    fun finishPage() {
        if (done) return
        done = true
        save()
        s.app.progress.log("Colored the ${page.id} page")
        co.launch {
            delay(600)
            s.sfx(Sound.Sparkle, 0.7f)
            s.sayAndWait("coloring_done")
            s.finish()
        }
    }

    fun tap(at: Offset, scale: Float, ox: Float, oy: Float) {
        if (done) return
        val x = (at.x - ox) / scale
        val y = (at.y - oy) / scale
        val i = art.hit(x, y)
        if (i < 0) return
        if (fills[i] == color) return
        co.launch {
            reveal.snapTo(1f) // finish any fill still spreading
            revealFrom = fills[i] ?: 0xFFFFFDF8
            revealPart = i
            revealAt = Offset(x, y)
            fills[i] = color
            s.sfx(Sound.Brush, 0.45f)
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        }
        s.practised("coloring")
        if (page.parts.indices.all { k -> !page.parts[k].required || fills[k] != null }) finishPage()
    }

    Box(Modifier.fillMaxSize()) {
        // the page
        val geo = remember { floatArrayOf(1f, 0f, 0f) } // scale and offset of the page, set while drawing
        SoftCard(
            Palette.cream,
            Modifier
                .offset(x = 22.dp, y = 0.dp)
                .size(884.dp, 630.dp)
                .pointerInput(page.id) { detectTapGestures { tap(it - Offset(12.dp.toPx(), 12.dp.toPx()), geo[0], geo[1], geo[2]) } },
            corner = 30.dp,
        ) {
            Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                val sc = minOf(size.width / PAGE_W, size.height / PAGE_H)
                val ox = (size.width - PAGE_W * sc) / 2
                val oy = (size.height - PAGE_H * sc) / 2
                geo[0] = sc; geo[1] = ox; geo[2] = oy
                val r = reveal.value
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    nc.save()
                    nc.translate(ox, oy)
                    nc.scale(sc, sc)
                    val rv = if (r < 1f && revealPart >= 0) Reveal(revealPart, revealFrom.toInt(), revealAt.x, revealAt.y, r) else null
                    art.draw(nc, { k -> (fills[k] ?: 0xFFFFFDF8).toInt() }, rv)
                    nc.restore()
                }
            }
        }

        // colours, back to the pictures, done
        Column(
            Modifier.align(Alignment.TopEnd).padding(end = 30.dp, top = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (row in 0 until 5) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (col in 0..1) {
                    val (name, argb) = COLORING_COLORS[row * 2 + col]
                    PaintPot(Color(argb), selected = color == argb) {
                        color = argb
                        s.say("colors_$name")
                    }
                }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                RoundButton(Icon.Book, size = 84.dp) { onBack() }
                RoundButton(Icon.Check, size = 84.dp, color = Palette.sage, iconColor = Palette.cream, enabled = fills.any { it != null } && !done) { finishPage() }
            }
        }
    }
}

/** A big round dab of colour to choose; the chosen one sits in a soft ring. */
@Composable
private fun PaintPot(c: Color, selected: Boolean, onTap: () -> Unit) {
    Box(
        Modifier
            .size(94.dp)
            .drawBehind {
                val r = size.minDimension / 2
                if (selected) {
                    drawCircle(Palette.honey.copy(alpha = 0.35f), r)
                    drawCircle(Palette.ink.copy(alpha = 0.55f), r - 2.dp.toPx(), style = Stroke(4.dp.toPx()))
                }
                val inner = r - 11.dp.toPx()
                drawCircle(c.darken(0.12f), inner)
                drawCircle(c, inner - 2.5f.dp.toPx(), center - Offset(0f, 2.dp.toPx()))
                drawCircle(Color.White.copy(alpha = 0.45f), inner * 0.2f, center - Offset(inner * 0.36f, inner * 0.4f))
            }
            .pressable(sound = Sound.Pick, onTap = onTap),
    )
}
