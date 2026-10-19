package dev.framio.meadow.worlds.puzzles

import android.graphics.BitmapShader
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.SoftCard
import dev.framio.meadow.ui.rememberSlowClock
import dev.framio.meadow.ui.rememberSprite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Jigsaw: one of the friends' pictures, cut into 2 (youngest) up to 9 real jigsaw pieces. The whole
 * picture shows first, then the pieces hop out around the board; drag a piece (or tap it, then tap
 * its place) and it snaps in when it's close. A faint ghost of the picture helps. Done: the picture
 * glows and Pip says what it shows. Three pictures per play.
 */
@Composable
fun Jigsaw(s: PlayScope) {
    val total = 3
    var round by remember { mutableIntStateOf(0) }
    val pictures = remember { JIGSAW_PICTURES.shuffled(Random(System.nanoTime())).take(total) }
    val pic = pictures[round]
    val picture = rememberSprite("pz_pic_$pic")
    val (cols, rows) = remember(round) { jigsawGrid(s.level) }
    val layout = remember(round) { jigsawLayout(cols, rows) }
    val edges = remember(round) { jigsawEdges(cols, rows, Random(System.nanoTime())) }
    val density = LocalDensity.current.density
    val pieces by produceState<List<ImageBitmap>?>(null, picture, round) {
        value = null
        val bmp = picture ?: return@produceState
        value = withContext(Dispatchers.Default) { cutPieces(bmp, layout, edges, density) }
    }
    key(round) {
        JigsawBoard(s, pic, picture, pieces, layout, edges) { first ->
            s.roundDone(first)
            if (round + 1 >= total) {
                s.rounds(total, total)
                s.finish()
            } else round++
        }
    }
    LaunchedEffect(round) { s.rounds(round, total) }
}

private class Piece(start: Offset) {
    val pos = Animatable(start, Offset.VectorConverter)
    val scale = Animatable(1f)
    val tilt = Animatable(0f)
    var placed by mutableStateOf(true)
}

@Composable
private fun JigsawBoard(
    s: PlayScope,
    pic: String,
    picture: ImageBitmap?,
    images: List<ImageBitmap>?,
    layout: JigsawLayout,
    edges: List<PieceEdges>,
    onDone: (firstTry: Boolean) -> Unit,
) {
    val density = LocalDensity.current.density
    val n = layout.cols * layout.rows
    val co = rememberCoroutineScope()
    fun px(p: Pt) = Offset(p.x * density, p.y * density)
    val pieces = remember { List(n) { Piece(px(layout.slotCenter(it))) } }
    val order = remember { mutableStateListOf<Int>().apply { addAll(0 until n) } } // drawing order, last on top
    val spots = remember { (0 until n).shuffled(Random(System.nanoTime())).map { layout.spots[it] } }
    var selected by remember { mutableIntStateOf(-1) }
    var ready by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var misses by remember { mutableIntStateOf(0) }
    val reveal = remember { Animatable(1f) } // the whole picture over everything: at the start and at the end
    val outlines = remember(density) {
        List(n) { i ->
            pieceOutline(edges[i], layout.cellW * density, layout.cellH * density).toComposePath()
        }
    }

    // show the whole picture, then the pieces hop out to their places around the board
    LaunchedEffect(images) {
        if (images == null) return@LaunchedEffect
        delay(1300)
        s.prompt("jigsaw_prompt")
        reveal.animateTo(0f, tween(350))
        s.sfx(Sound.Whoosh, 0.5f)
        pieces.forEachIndexed { i, p ->
            p.placed = false
            co.launch {
                delay(i * 90L)
                launch { p.scale.animateTo(layout.looseScale, tween(600, easing = FastOutSlowInEasing)) }
                p.pos.animateTo(px(spots[i]), tween(650, easing = FastOutSlowInEasing))
            }
        }
        delay(650L + n * 90L)
        ready = true
    }

    fun toTop(i: Int) {
        order.remove(i)
        order.add(i)
    }

    fun goHome(i: Int) = co.launch {
        val p = pieces[i]
        launch { p.scale.animateTo(layout.looseScale, tween(350, easing = FastOutSlowInEasing)) }
        p.pos.animateTo(px(spots[i]), spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow))
    }

    fun miss(i: Int) {
        misses++
        co.launch {
            val t = pieces[i].tilt
            for (v in floatArrayOf(-5f, 4f, -2f, 0f)) t.animateTo(v, tween(100))
        }
        if (misses >= 2) s.showHint(true)
    }

    fun place(i: Int) {
        val p = pieces[i]
        if (selected == i) selected = -1
        co.launch {
            launch { p.scale.animateTo(1f, tween(260, easing = FastOutSlowInEasing)) }
            p.pos.animateTo(px(layout.slotCenter(i)), tween(280, easing = FastOutSlowInEasing))
            p.placed = true
            order.remove(i)
            order.add(0, i) // placed pieces sit under the loose ones
            s.sfx(Sound.Place)
            s.showHint(false)
            if (pieces.all { it.placed } && !done) {
                done = true
                delay(250)
                reveal.animateTo(1f, tween(600))
                s.sfx(Sound.Sparkle, 0.7f)
                s.practised("jigsaw:${layout.cols * layout.rows}")
                s.sayAndWait("jigsaw_pic_$pic")
                s.praiseAndWait()
                delay(500)
                onDone(misses <= 1)
            }
        }
    }

    fun pieceAt(p: Offset): Int {
        for (k in order.indices.reversed()) {
            val i = order[k]
            val pc = pieces[i]
            if (pc.placed) continue
            val c = pc.pos.value
            val hw = (layout.cellW / 2 + layout.margin * 0.4f) * density * pc.scale.value
            val hh = (layout.cellH / 2 + layout.margin * 0.4f) * density * pc.scale.value
            if (p.x in c.x - hw..c.x + hw && p.y in c.y - hh..c.y + hh) return i
        }
        return -1
    }

    val hintOn = s.hint && ready && !done
    val hintPiece = if (!hintOn) -1 else if (selected >= 0) selected else pieces.indices.firstOrNull { !pieces[it].placed } ?: -1
    val clock = rememberSlowClock()
    val boardGlow = remember { Animatable(0f) }
    LaunchedEffect(done) { if (done) boardGlow.animateTo(1f, tween(700)) }

    Box(Modifier.fillMaxSize()) {
        SoftCard(
            Palette.cream,
            Modifier
                .offset((layout.boardX - 14).dp, (layout.boardY - 14).dp)
                .size((layout.boardW + 28).dp, (layout.boardH + 28).dp),
            corner = 28.dp,
        ) {}
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(ready, done) {
                    if (!ready || done) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val start = down.position
                        val hit = pieceAt(start)
                        var dragging = false
                        var last = start
                        var center = Offset.Zero
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            if (hit >= 0) {
                                if (!dragging && (ch.position - start).getDistance() > viewConfiguration.touchSlop) {
                                    dragging = true
                                    selected = -1
                                    toTop(hit)
                                    s.sfx(Sound.Pick)
                                    center = pieces[hit].pos.value
                                    co.launch { pieces[hit].scale.animateTo(1f, tween(220)) }
                                }
                                if (dragging) {
                                    ch.consume()
                                    center += ch.position - last
                                    val to = center
                                    co.launch { pieces[hit].pos.snapTo(to) }
                                }
                            }
                            last = ch.position
                        }
                        if (dragging) {
                            val at = Pt(center.x / density, center.y / density)
                            when {
                                layout.snaps(hit, at) -> place(hit)
                                layout.slotAt(at) >= 0 -> { miss(hit); goHome(hit) }
                                else -> goHome(hit)
                            }
                        } else if (hit >= 0) {
                            selected = if (selected == hit) -1 else hit
                            toTop(hit)
                            s.sfx(Sound.Pick)
                        } else if (selected >= 0) {
                            val slot = layout.slotAt(Pt(start.x / density, start.y / density))
                            when {
                                slot == selected -> place(selected)
                                slot >= 0 -> miss(selected)
                                else -> selected = -1
                            }
                        }
                    }
                },
        ) {
            val bx = layout.boardX * density
            val by = layout.boardY * density
            val bw = layout.boardW * density
            val bh = layout.boardH * density
            val m = layout.margin * density
            val cw = layout.cellW * density
            val ch = layout.cellH * density
            // the faint ghost and the cut lines
            picture?.let { drawImageInto(it, bx, by, bw, bh, 0.24f) }
            for (i in 0 until n) {
                val x = bx + (i % layout.cols) * cw - m
                val y = by + (i / layout.cols) * ch - m
                translate(x, y) { drawPath(outlines[i], Palette.ink.copy(alpha = 0.1f), style = Stroke(1.5f * density)) }
            }
            // the hint: the right place breathes
            if (hintPiece >= 0) {
                val x = bx + (hintPiece % layout.cols) * cw - m
                val y = by + (hintPiece / layout.cols) * ch - m
                val p = clock.value / 1500f
                translate(x, y) {
                    drawPath(outlines[hintPiece], Palette.honey.copy(alpha = 0.08f + 0.14f * sin(p * PI.toFloat()).let { it * it }))
                    drawPath(outlines[hintPiece], Palette.honey.copy(alpha = 0.7f), style = Stroke(4f * density))
                }
            }
            images?.let { imgs ->
                for (i in order) {
                    val pc = pieces[i]
                    drawPiece(imgs[i], pc, selected == i || (hintPiece == i && !pc.placed), lifted = !pc.placed)
                }
            }
            if (reveal.value > 0f) picture?.let { drawImageInto(it, bx, by, bw, bh, reveal.value) }
            if (boardGlow.value > 0f) {
                val g = 14f * density
                drawRoundRect(
                    Palette.honey.copy(alpha = 0.55f * boardGlow.value), Offset(bx - g, by - g), Size(bw + 2 * g, bh + 2 * g),
                    CornerRadius(30f * density), style = Stroke(8f * density),
                )
            }
        }
    }
}

private fun DrawScope.drawImageInto(img: ImageBitmap, x: Float, y: Float, w: Float, h: Float, alpha: Float) {
    drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset(x.toInt(), y.toInt()), IntSize(w.toInt(), h.toInt()), alpha = alpha)
}

private fun DrawScope.drawPiece(img: ImageBitmap, pc: Piece, glow: Boolean, lifted: Boolean) {
    val c = pc.pos.value
    val sc = pc.scale.value
    translate(c.x - img.width / 2f, c.y - img.height / 2f) {
        scale(sc, sc, Offset(img.width / 2f, img.height / 2f)) {
            rotateZ(pc.tilt.value, Offset(img.width / 2f, img.height / 2f)) {
                if (lifted) drawImage(img, Offset(0f, 7f * density), alpha = 0.22f, colorFilter = SHADOW_TINT)
                if (glow) {
                    scale(1.05f, 1.05f, Offset(img.width / 2f, img.height / 2f)) {
                        drawImage(img, alpha = 0.6f, colorFilter = GLOW_TINT)
                    }
                }
                drawImage(img)
            }
        }
    }
}

private inline fun DrawScope.rotateZ(deg: Float, pivot: Offset, block: DrawScope.() -> Unit) =
    if (deg == 0f) block() else rotate(deg, pivot, block)

private val SHADOW_TINT = ColorFilter.tint(Color(0xFF3A2E44), BlendMode.SrcIn)
private val GLOW_TINT = ColorFilter.tint(Palette.honey, BlendMode.SrcIn)

private fun List<Seg>.toComposePath() = Path().also { p ->
    for (s in this) when (s) {
        is Seg.Move -> p.moveTo(s.x, s.y)
        is Seg.Line -> p.lineTo(s.x, s.y)
        is Seg.Cubic -> p.cubicTo(s.x1, s.y1, s.x2, s.y2, s.x, s.y)
    }
    p.close()
}

private fun List<Seg>.toAndroidPath() = android.graphics.Path().also { p ->
    for (s in this) when (s) {
        is Seg.Move -> p.moveTo(s.x, s.y)
        is Seg.Line -> p.lineTo(s.x, s.y)
        is Seg.Cubic -> p.cubicTo(s.x1, s.y1, s.x2, s.y2, s.x, s.y)
    }
    p.close()
}

/**
 * Cuts the picture into piece bitmaps once per round (antialiased edges, a soft clay bevel), so
 * every frame only draws images.
 */
private fun cutPieces(picture: ImageBitmap, layout: JigsawLayout, edges: List<PieceEdges>, density: Float): List<ImageBitmap> {
    val src = picture.asAndroidBitmap()
    val cw = layout.cellW * density
    val ch = layout.cellH * density
    val m = layout.margin * density
    val sx = layout.boardW * density / src.width
    val sy = layout.boardH * density / src.height
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    return edges.mapIndexed { i, e ->
        val w = (cw + 2 * m).toInt() + 1
        val h = (ch + 2 * m).toInt() + 1
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val path = pieceOutline(e, cw, ch).toAndroidPath()
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(android.graphics.Matrix().apply {
            setScale(sx, sy)
            postTranslate(m - (i % layout.cols) * cw, m - (i / layout.cols) * ch)
        })
        fill.shader = shader
        cv.drawPath(path, fill)
        // bevel: light on the upper-left inside edge, shade on the lower-right
        cv.save()
        cv.clipPath(path)
        line.strokeWidth = 5f * density
        line.color = 0x55FFFFFF
        cv.save(); cv.translate(2f * density, 2.5f * density); cv.drawPath(path, line); cv.restore()
        line.color = 0x30302438
        cv.save(); cv.translate(-2f * density, -2.5f * density); cv.drawPath(path, line); cv.restore()
        cv.restore()
        line.strokeWidth = 1.6f * density
        line.color = 0x40302438
        cv.drawPath(path, line)
        bmp.asImageBitmap()
    }
}
