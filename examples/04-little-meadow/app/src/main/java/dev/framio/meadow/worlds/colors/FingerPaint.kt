package dev.framio.meadow.worlds.colors

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.Icon
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.RoundButton
import dev.framio.meadow.ui.Sprite
import dev.framio.meadow.ui.Sprites
import dev.framio.meadow.ui.darken
import dev.framio.meadow.ui.drawIcon
import dev.framio.meadow.ui.lighten
import dev.framio.meadow.ui.pressable
import dev.framio.meadow.ui.softShadow
import dev.framio.meadow.worlds.habits.DRAWINGS_DIR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.hypot
import kotlin.random.Random

private val STAMPS = listOf("ob_star", "ob_heart", "ob_flower", "ch_pip_happy", "ch_tuka_happy", "ch_bo_happy", "ch_moss_happy", "ch_luma_happy")
private val BRUSH_PX = floatArrayOf(14f, 30f, 56f)
private val STAMP_PX = floatArrayOf(90f, 140f, 200f)
private const val RAINBOW = -1L
private const val ERASER = -2L
private const val INK_SCALE = 0.5f

/**
 * The painting itself: one offscreen bitmap that each finger draws into with smooth quadratic
 * segments, so a frame costs one bitmap draw however long the picture gets (no stroke list to
 * redraw on the Mali-400). Measured on the frame: one plain bitmap draw beats both tiles (each
 * extra mutable bitmap costs more in draw issue than it saves in upload) and drawing the ink
 * through a shader. The paper texture is a separate, static bitmap underneath (drawn through a
 * shader for its round corners); the ink is clipped to the same corners in software when painted,
 * and the eraser clears it back to the paper.
 */
private class PaintBoard(val w: Int, val h: Int, private val corner: Float) {
    // the ink at INK_SCALE resolution: the frame re-uploads the whole bitmap to the GPU whenever it
    // changes, and a quarter of the pixels is the difference between smooth and sticky strokes
    private val ink: Bitmap = Bitmap.createBitmap((w * INK_SCALE).toInt(), (h * INK_SCALE).toInt(), Bitmap.Config.ARGB_8888)
    private val paper: Bitmap = makePaper(w, h)
    private val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
    private val canvas = Canvas(ink).apply {
        scale(INK_SCALE, INK_SCALE)
        clipPath(android.graphics.Path().apply { addRoundRect(rect, corner, corner, android.graphics.Path.Direction.CW) })
    }
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(paper, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    /** Bumped after every change; the composable reads it in its draw phase only. */
    var tick by mutableIntStateOf(0)
    var dirty = false
        private set

    private class Stroke(var lx: Float, var ly: Float, var mx: Float, var my: Float, var dist: Float, val color: Long, val width: Float)
    private val strokes = HashMap<PointerId, Stroke>()
    private val path = android.graphics.Path()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clear = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    private val stampPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val smooth = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun setup(p: Paint, color: Long, dist: Float) {
        if (color == ERASER) {
            p.xfermode = clear
        } else {
            p.xfermode = null
            p.color = (if (color == RAINBOW) rainbowArgb(dist) else color).toInt()
        }
    }

    fun down(id: PointerId, x: Float, y: Float, color: Long, width: Float) {
        val wd = if (color == ERASER) width * 1.8f else width
        strokes[id] = Stroke(x, y, x, y, 0f, color, wd)
        setup(dot, color, 0f)
        canvas.drawCircle(x, y, wd / 2, dot)
        dirty = true
        tick++
    }

    fun move(id: PointerId, x: Float, y: Float) {
        val s = strokes[id] ?: return
        val d = hypot(x - s.lx, y - s.ly)
        if (d < 1.5f) return
        val nx = (s.lx + x) / 2
        val ny = (s.ly + y) / 2
        path.rewind()
        path.moveTo(s.mx, s.my)
        path.quadTo(s.lx, s.ly, nx, ny)
        setup(line, s.color, s.dist)
        line.strokeWidth = s.width
        canvas.drawPath(path, line)
        s.dist += d
        s.mx = nx
        s.my = ny
        s.lx = x
        s.ly = y
    }

    fun up(id: PointerId) {
        val s = strokes.remove(id) ?: return
        setup(line, s.color, s.dist)
        line.strokeWidth = s.width
        canvas.drawLine(s.mx, s.my, s.lx, s.ly, line)
        tick++
    }

    fun stamp(bmp: Bitmap, x: Float, y: Float, size: Float) {
        val sc = size / maxOf(bmp.width, bmp.height)
        val dst = RectF(x - bmp.width * sc / 2, y - bmp.height * sc / 2, x + bmp.width * sc / 2, y + bmp.height * sc / 2)
        canvas.drawBitmap(bmp, null, dst, stampPaint)
        dirty = true
        tick++
    }

    fun clear() {
        strokes.clear()
        ink.eraseColor(0)
        dirty = false
        tick++
    }

    fun draw(c: Canvas) {
        c.drawRoundRect(rect, corner, corner, paperPaint)
        c.drawBitmap(ink, null, rect, smooth)
    }

    /** Paper and paint together, for saving. */
    fun snapshot(): Bitmap {
        val out = paper.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(ink, null, rect, smooth)
        return out
    }

    private companion object {
        /** Warm paper with a faint grain of fibres, drawn once. */
        fun makePaper(w: Int, h: Int): Bitmap {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawColor(0xFFFCF8F0.toInt())
            val r = Random(7)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            repeat(w * h / 260) {
                p.color = if (r.nextBoolean()) 0x0F8A6A4E else 0x12FFFFFF
                c.drawCircle(r.nextFloat() * w, r.nextFloat() * h, 0.6f + r.nextFloat() * 1.4f, p)
            }
            p.strokeWidth = 1f
            repeat(w * h / 4000) {
                p.color = 0x0C8A6A4E
                val x = r.nextFloat() * w
                val y = r.nextFloat() * h
                c.drawLine(x, y, x + (r.nextFloat() - 0.5f) * 14f, y + (r.nextFloat() - 0.5f) * 14f, p)
            }
            return b
        }
    }
}

/**
 * Finger paint: a big sheet of paper, ten calm colours and a rainbow brush, three brush sizes,
 * stamps (stars, hearts, flowers, the friends), an eraser, "new page" (held down, so it never
 * happens by accident) and "done", which keeps the picture for the grown-ups' gallery. Several
 * fingers can paint at once. No questions and no nagging: just making.
 */
@Composable
fun FingerPaint(s: PlayScope) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val board = remember(size) { if (size.width > 0 && size.height > 0) PaintBoard(size.width, size.height, 28f * density) else null }
    var color by remember { mutableStateOf(PAINT_COLORS[5].second) }
    var stampMode by remember { mutableStateOf(false) }
    var stamp by remember { mutableIntStateOf(0) }
    var sizeIdx by remember { mutableIntStateOf(1) }
    var saving by remember { mutableStateOf(false) }
    var stampsShown by remember { mutableStateOf(false) }
    val co = rememberCoroutineScope()
    val stampBitmaps by androidx.compose.runtime.rememberUpdatedState(STAMPS.map { dev.framio.meadow.ui.rememberSprite(it) })
    val lastBrushSound = remember { longArrayOf(0L) }

    LaunchedEffect(Unit) {
        Sprites.preload(STAMPS)
        s.waitForIntro(0)
        s.prompt("paint_intro")
    }

    Box(Modifier.fillMaxSize()) {
        // the paper
        Box(
            Modifier
                .offset(x = 24.dp, y = 0.dp)
                .size(1040.dp, 572.dp)
                .softShadow(28.dp, 6.dp)
                .onSizeChanged { size = it }
                .pointerInput(board) {
                    val b = board ?: return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent()
                            var changed = false
                            for (ch in ev.changes) {
                                when {
                                    ch.changedToDownIgnoreConsumed() -> {
                                        if (stampMode) {
                                            stampBitmaps[stamp]?.let {
                                                b.stamp(it.asAndroidBitmap(), ch.position.x, ch.position.y, STAMP_PX[sizeIdx])
                                                s.sfx(Sound.Pop, 0.6f, 0.9f + Random.nextFloat() * 0.3f)
                                            }
                                        } else {
                                            b.down(ch.id, ch.position.x, ch.position.y, color, BRUSH_PX[sizeIdx])
                                            val now = SystemClock.uptimeMillis()
                                            if (now - lastBrushSound[0] > 700) {
                                                lastBrushSound[0] = now
                                                s.sfx(Sound.Brush, 0.25f)
                                            }
                                        }
                                    }
                                    ch.changedToUpIgnoreConsumed() -> b.up(ch.id)
                                    ch.pressed && !stampMode -> {
                                        for (hst in ch.historical) b.move(ch.id, hst.position.x, hst.position.y)
                                        b.move(ch.id, ch.position.x, ch.position.y)
                                        changed = true
                                    }
                                }
                                ch.consume()
                            }
                            if (changed) b.tick++
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val b = board ?: return@Canvas
                b.tick // redraw when the painting changes (draw phase only)
                val r = 28.dp.toPx()
                drawIntoCanvas { b.draw(it.nativeCanvas) }
                drawRoundRect(Palette.paperDeep.darken(0.08f), style = Stroke(3.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
            }
        }

        // colours (or stamps) along the bottom
        Row(
            // starts right of the firmware's floating button in the bottom-left corner
            Modifier.offset(x = 92.dp, y = 592.dp),
            horizontalArrangement = Arrangement.spacedBy(if (stampMode) 26.dp else 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!stampMode) {
                PAINT_COLORS.forEach { (name, argb) ->
                    Swatch(Color(argb), selected = color == argb) {
                        color = argb
                        s.say("colors_$name")
                    }
                }
                Swatch(null, selected = color == RAINBOW) { color = RAINBOW }
                RoundButton(Icon.Eraser, size = 80.dp, color = if (color == ERASER) Palette.honey else Palette.cream) { color = ERASER }
            } else {
                STAMPS.forEachIndexed { i, name ->
                    Box(
                        Modifier
                            .size(80.dp)
                            .drawBehind {
                                drawCircle(if (stamp == i) Palette.honey.lighten(0.35f) else Palette.cream)
                                if (stamp == i) drawCircle(Palette.honey, style = Stroke(5.dp.toPx()))
                            }
                            .pressable { stamp = i },
                        contentAlignment = Alignment.Center,
                    ) { Sprite(name, Modifier.size(62.dp)) }
                }
            }
        }

        // tools on the right
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 36.dp, top = 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RoundButton(Icon.Brush, color = if (!stampMode) Palette.honey else Palette.cream) {
                stampMode = false
                if (color == ERASER) color = PAINT_COLORS[5].second
            }
            RoundButton(Icon.Star, color = if (stampMode) Palette.honey else Palette.cream) {
                stampMode = true
                if (!stampsShown) {
                    stampsShown = true
                    s.say("paint_stamps")
                }
            }
            Spacer(Modifier.height(6.dp))
            for (i in 0..2) SizeButton(i, selected = sizeIdx == i, color = if (stampMode || color < 0) Palette.inkSoft else Color(color)) { sizeIdx = i }
            Spacer(Modifier.height(6.dp))
            HoldButton(onHint = { s.say("paint_hold") }) {
                board?.clear()
                s.sfx(Sound.Whoosh)
                s.say("paint_new")
            }
            RoundButton(Icon.Check, color = Palette.sage, iconColor = Palette.cream, enabled = !saving) {
                val b = board ?: return@RoundButton
                if (!b.dirty) {
                    s.repeatPrompt()
                    return@RoundButton
                }
                saving = true
                co.launch {
                    val pic = b.snapshot()
                    withContext(Dispatchers.IO) {
                        try {
                            val dir = File(s.app.context.filesDir, DRAWINGS_DIR).apply { mkdirs() }
                            File(dir, drawingName(System.currentTimeMillis())).outputStream().use { pic.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        } catch (e: Exception) {
                            android.util.Log.w(dev.framio.meadow.core.TAG, "saving drawing failed", e)
                        } finally {
                            pic.recycle()
                        }
                    }
                    s.app.progress.log("Painted a picture")
                    s.sfx(Sound.Sparkle, 0.6f)
                    s.sayAndWait("paint_done")
                    s.finish()
                }
            }
        }
    }
}

/** A paint dab; null = the rainbow brush. */
@Composable
private fun Swatch(c: Color?, selected: Boolean, onTap: () -> Unit) {
    Box(
        Modifier
            .size(80.dp)
            .drawBehind {
                val r = size.minDimension / 2
                if (selected) drawCircle(Palette.ink.copy(alpha = 0.55f), r, style = Stroke(4.dp.toPx()))
                val inner = r - 9.dp.toPx()
                if (c != null) {
                    drawCircle(c.darken(0.1f), inner)
                    drawCircle(c, inner - 2.dp.toPx(), center - Offset(0f, 1.5f.dp.toPx()))
                } else {
                    drawCircle(Brush.sweepGradient(listOf(Color(Hue.Red.argb), Color(Hue.Orange.argb), Color(Hue.Yellow.argb), Color(Hue.Green.argb), Color(Hue.Blue.argb), Color(Hue.Purple.argb), Color(Hue.Pink.argb), Color(Hue.Red.argb))), inner)
                }
                drawCircle(Color.White.copy(alpha = 0.45f), inner * 0.22f, center - Offset(inner * 0.35f, inner * 0.38f))
            }
            .pressable(sound = Sound.Pick, onTap = onTap),
    )
}

@Composable
private fun SizeButton(i: Int, selected: Boolean, color: Color, onTap: () -> Unit) {
    Box(
        Modifier
            .size(84.dp)
            .softShadow(42.dp, 3.dp)
            .drawBehind {
                drawCircle(if (selected) Palette.honey.lighten(0.45f) else Palette.cream)
                if (selected) drawCircle(Palette.honey, style = Stroke(4.dp.toPx()))
                drawCircle(color, (BRUSH_PX[i] / 2f).dp.toPx().coerceAtMost(size.minDimension * 0.36f))
            }
            .pressable(sound = Sound.Tap, onTap = onTap),
    )
}

/** "New page": press and hold for a second (a ring fills up); a quick tap only explains. */
@Composable
private fun HoldButton(onHint: () -> Unit, onDone: () -> Unit) {
    val hold = remember { Animatable(0f) }
    val co = rememberCoroutineScope()
    Box(
        Modifier
            .size(84.dp)
            .softShadow(42.dp, 4.dp)
            .drawBehind {
                drawCircle(Palette.cream)
                drawIcon(Icon.Plus, Palette.ink)
                if (hold.value > 0f) {
                    val st = 7.dp.toPx()
                    drawArc(Palette.terracotta, -90f, 360f * hold.value, false, Offset(st / 2, st / 2), Size(size.width - st, size.height - st), style = Stroke(st, cap = StrokeCap.Round))
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    val start = SystemClock.uptimeMillis()
                    var job: Job? = co.launch {
                        hold.animateTo(1f, tween(1100))
                        onDone()
                        hold.snapTo(0f)
                    }
                    waitForUpOrCancellation()
                    if (hold.value < 1f && job?.isActive == true) {
                        job.cancel()
                        job = null
                        co.launch { hold.animateTo(0f, tween(250)) }
                        if (SystemClock.uptimeMillis() - start < 400) onHint()
                    }
                }
            },
    )
}
