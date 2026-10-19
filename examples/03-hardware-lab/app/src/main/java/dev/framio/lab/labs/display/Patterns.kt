package dev.framio.lab.labs.display

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Full-screen test patterns for dead pixels, uniformity, banding and sharpness. */
object Patterns {

    class Pattern(val name: String, val hint: String, val draw: DrawScope.() -> Unit)

    val all = listOf(
        solid("White", "Dark spots = dead pixels; check uniformity at the edges", Color.White),
        solid("Black", "Lit dots = stuck pixels; corners show backlight bleed", Color.Black),
        solid("Red", "Sub-pixel check", Color.Red),
        solid("Green", "Sub-pixel check", Color.Green),
        solid("Blue", "Sub-pixel check", Color.Blue),
        solid("Gray 20%", "Uniformity / clouding", Color(0xFF333333)),
        Pattern("Gray ramp", "Steps instead of a smooth ramp = 6-bit panel or dithering") {
            drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.White)))
        },
        Pattern("RGB ramps", "Each channel 0 → 255") {
            val h = size.height / 3
            listOf(Color.Red, Color.Green, Color.Blue).forEachIndexed { i, c ->
                drawRect(Brush.horizontalGradient(listOf(Color.Black, c)), Offset(0f, i * h), Size(size.width, h))
            }
        },
        Pattern("Colour bars", "Saturation and hue") {
            val bars = listOf(Color.White, Color.Yellow, Color.Cyan, Color.Green, Color.Magenta, Color.Red, Color.Blue, Color.Black)
            val w = size.width / bars.size
            bars.forEachIndexed { i, c -> drawRect(c, Offset(i * w, 0f), Size(w + 1, size.height)) }
        },
        Pattern("1 px checkerboard", "Should look flat grey: any moiré = scaling, the frame is 1:1") {
            // a 2×2 tile repeated by the GPU: one draw call instead of 512k rectangles
            drawRect(checker)
        },
        Pattern("Grid", "10 px grid: lines should be straight and even") {
            drawRect(Color.Black)
            var x = 0f
            while (x < size.width) { drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 1f); x += 10f }
            var y = 0f
            while (y < size.height) { drawLine(Color.White, Offset(0f, y), Offset(size.width, y), 1f); y += 10f }
        },
    )

    private fun solid(name: String, hint: String, color: Color) = Pattern(name, hint) { drawRect(color) }

    private val checker: ShaderBrush by lazy {
        val tile = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, android.graphics.Color.WHITE)
            setPixel(1, 1, android.graphics.Color.WHITE)
            setPixel(1, 0, android.graphics.Color.BLACK)
            setPixel(0, 1, android.graphics.Color.BLACK)
        }
        ShaderBrush(ImageShader(tile.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
    }

    /** Tap = next pattern, long-press = close. The name shows for 2 s on each pattern. */
    @Composable
    fun Overlay(onClose: () -> Unit) {
        var index by remember { mutableIntStateOf(0) }
        var showLabel by remember { mutableStateOf(true) }
        val pattern = all[index]
        LaunchedEffect(index) {
            showLabel = true
            delay(2000)
            showLabel = false
        }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { index = (index + 1) % all.size },
                        onLongPress = { onClose() },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) { pattern.draw(this) }
            if (showLabel) {
                Text(
                    "${index + 1}/${all.size} · ${pattern.name} — ${pattern.hint}\nTap: next · long-press: close",
                    Modifier.align(Alignment.BottomCenter).padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFFFF00FF)),
                )
            }
        }
    }
}
