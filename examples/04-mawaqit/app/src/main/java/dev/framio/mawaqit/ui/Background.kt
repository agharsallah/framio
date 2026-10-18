package dev.framio.mawaqit.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import dev.framio.mawaqit.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The themed background: a vertical gradient under a lattice of eight-pointed stars (khatam). The pattern is drawn
 * once into a bitmap the size of the screen: on the Mali-400, one texture per frame is far cheaper than hundreds of
 * paths. With the "mosque" theme, the mosque's own picture from its Mawaqit page, under a dark veil.
 */
@Composable
fun Background(palette: Palette, mosquePicture: String?, content: @Composable () -> Unit) {
    val cacheDir = LocalContext.current.cacheDir
    BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(palette.top, palette.bottom)))) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.roundToPx() }
        val h = with(density) { maxHeight.roundToPx() }
        val picture by produceState<ImageBitmap?>(null, mosquePicture, w, h) {
            value = mosquePicture?.let { loadPicture(it, cacheDir, w, h) }
        }
        val p = picture
        if (p != null) {
            Image(p, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xB3000000), Color(0xE6000000)))))
        } else {
            val pattern = remember(palette.pattern, w, h) { starLattice(w, h, palette.pattern) }
            Image(pattern, null, Modifier.fillMaxSize())
        }
        // Soft light from the top: the eye goes to the clock first
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.radialGradient(
                    listOf(palette.accent.copy(alpha = if (palette.light) 0.10f else 0.07f), Color.Transparent),
                    center = Offset(size.width * 0.3f, 0f),
                    radius = size.width * 0.7f,
                ),
            )
        }
        content()
    }
}

/** An announcement's picture, downloaded once and kept in the cache. */
@Composable
fun RemoteImage(url: String, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val dir = LocalContext.current.cacheDir
    val image by produceState<ImageBitmap?>(null, url) { value = loadPicture(url, dir, 1200, 760) }
    image?.let { Image(it, null, modifier, contentScale = contentScale) }
}

/** Eight-pointed stars on a square grid, joined by their points: the classic khatam lattice. */
private fun starLattice(w: Int, h: Int, color: Color): ImageBitmap {
    val bmp = ImageBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1))
    val canvas = Canvas(bmp)
    val paint = Paint().apply {
        this.color = color
        style = PaintingStyle.Stroke
        strokeWidth = 1.5f
        isAntiAlias = true
    }
    val cell = 120f
    val outer = cell * 0.36f
    val inner = outer * 0.62f
    var y = -cell / 2
    while (y < h + cell) {
        var x = -cell / 2
        while (x < w + cell) {
            val star = Path()
            for (i in 0 until 16) {
                val r = if (i % 2 == 0) outer else inner
                val a = i * PI / 8 - PI / 2
                val px = x + (r * cos(a)).toFloat()
                val py = y + (r * sin(a)).toFloat()
                if (i == 0) star.moveTo(px, py) else star.lineTo(px, py)
            }
            star.close()
            canvas.drawPath(star, paint)
            // Join the points to the neighbours: the crosses between the stars
            canvas.drawLine(Offset(x + outer, y), Offset(x + cell - outer, y), paint)
            canvas.drawLine(Offset(x, y + outer), Offset(x, y + cell - outer), paint)
            val d = (outer * 0.7071f)
            canvas.drawLine(Offset(x + d, y + d), Offset(x + cell / 2, y + cell / 2), paint)
            canvas.drawLine(Offset(x - d, y + d), Offset(x - cell / 2, y + cell / 2), paint)
            x += cell
        }
        y += cell
    }
    return bmp
}

/** The picture, downsampled to the screen and cached on disk (1 GB of RAM: never decode a 4000 px photo whole). */
private suspend fun loadPicture(url: String, cacheDir: File, w: Int, h: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    try {
        val file = File(cacheDir, "bg-" + Integer.toHexString(url.hashCode()) + ".img")
        if (!file.exists()) Net.download(url, file)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}
