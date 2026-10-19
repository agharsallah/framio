package dev.framio.meadow.ui

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToInt

/*
 * Drawing that stays cheap on the Mali-400. This firmware redraws the whole screen for any change
 * (debug.hwui.render_dirty_regions=false), so every per-frame cost multiplies. Measured on the frame:
 * stencil clips for rounded shapes and RenderNode elevation shadows were most of the GL time.
 */

/**
 * A soft shadow under a rounded rectangle, blurred once in software per size and then drawn as a
 * single texture. Use it instead of Modifier.shadow on anything that is on screen while things move.
 */
fun Modifier.softShadow(corner: Dp, elevation: Dp, color: Color = Color(0x553E3346)): Modifier =
    if (elevation.value <= 0f) this else drawBehind {
        val blur = elevation.toPx() * 1.6f
        val bmp = ShadowCache.get(size.width.roundToInt(), size.height.roundToInt(), corner.toPx(), blur, color)
        val pad = ceil(blur * 2).toInt()
        drawImage(bmp, Offset(-pad.toFloat(), -pad + elevation.toPx() * 0.7f))
    }

private object ShadowCache {
    private data class Key(val w: Int, val h: Int, val r: Int, val blur: Int, val color: Int)
    private val cache = object : android.util.LruCache<Key, ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: ImageBitmap) = value.width * value.height * 4
    }

    fun get(w: Int, h: Int, radius: Float, blur: Float, color: Color): ImageBitmap {
        val key = Key(w, h, radius.roundToInt(), blur.roundToInt(), color.toArgb())
        cache.get(key)?.let { return it }
        val pad = ceil(blur * 2).toInt()
        val bmp = android.graphics.Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, android.graphics.Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            if (blur >= 1f) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        val r = minOf(radius, w / 2f, h / 2f)
        android.graphics.Canvas(bmp).drawRoundRect(pad.toFloat(), pad.toFloat(), (pad + w).toFloat(), (pad + h).toFloat(), r, r, paint)
        return bmp.asImageBitmap().also { cache.put(key, it) }
    }
}

/**
 * Milliseconds since this composable appeared, updated about [stepMs] apart instead of every frame.
 * For idle motion of a few pixels (breathing, bobbing) the steps are invisible, and the screen gets
 * redrawn a fraction as often.
 */
@Composable
fun rememberSlowClock(stepMs: Long = 83): State<Long> {
    val t = remember { mutableLongStateOf(0L) }
    LaunchedEffect(stepMs) {
        val start = SystemClock.uptimeMillis()
        while (true) {
            t.longValue = SystemClock.uptimeMillis() - start
            delay(stepMs)
        }
    }
    return t
}
