package dev.framio.meadow.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.FilterQuality

/**
 * A full-screen pre-rendered scene (assets/sprites/bg_*.webp, 1280×800). While it decodes, a soft
 * paper gradient shows, and the scene fades in over it. Static: it never redraws on its own.
 */
@Composable
fun Backdrop(name: String, modifier: Modifier = Modifier, night: Boolean = name.startsWith("bg_night")) {
    val bmp = rememberSprite(name)
    val a by animateFloatAsState(if (bmp != null) 1f else 0f, tween(450), label = "bg")
    val top = if (night) Palette.night else Palette.cream
    val bottom = if (night) Palette.nightDeep else Palette.paperDeep
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(top, bottom)))
        val b = bmp ?: return@Canvas
        // cover: fill the screen, crop what doesn't fit
        val s = maxOf(size.width / b.width, size.height / b.height)
        val w = (b.width * s).toInt()
        val h = (b.height * s).toInt()
        drawImage(
            b, IntOffset.Zero, IntSize(b.width, b.height),
            IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()), IntSize(w, h),
            alpha = a, filterQuality = FilterQuality.Low,
        )
    }
}
