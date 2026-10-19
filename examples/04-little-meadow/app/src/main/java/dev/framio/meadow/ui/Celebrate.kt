package dev.framio.meadow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * A calm celebration: a handful of petals, leaves and little stars drifting down slowly, swaying,
 * fading in and out. No bursts, no flashing. Only the canvas redraws; nothing recomposes per frame.
 */
@Composable
fun FallingPetals(
    modifier: Modifier = Modifier,
    seed: Int = 0,
    count: Int = 22,
    durationMs: Float = 5200f,
    colors: List<Color> = listOf(Palette.honey, Palette.rose, Palette.sage, Palette.lavender, Palette.sky, Palette.moon),
) {
    data class Petal(val x: Float, val delay: Float, val speed: Float, val sway: Float, val phase: Float, val size: Float, val color: Color, val kind: Int, val spin: Float)

    val petals = remember(seed) {
        val r = Random(seed)
        List(count) {
            Petal(
                x = r.nextFloat(), delay = r.nextFloat() * 1800f, speed = 0.09f + r.nextFloat() * 0.08f,
                sway = 18f + r.nextFloat() * 26f, phase = r.nextFloat() * 6.28f, size = 14f + r.nextFloat() * 16f,
                color = colors[r.nextInt(colors.size)], kind = r.nextInt(3), spin = (r.nextFloat() - 0.5f) * 90f,
            )
        }
    }
    var t by remember(seed) { mutableFloatStateOf(0f) }
    LaunchedEffect(seed) {
        val start = withFrameNanos { it }
        while (t < durationMs) withFrameNanos { t = (it - start) / 1_000_000f }
    }
    Canvas(modifier) {
        for (p in petals) {
            val life = t - p.delay
            if (life <= 0f) continue
            val y = -40f + life * p.speed * size.height / 1000f * 2.2f
            if (y > size.height + 40f) continue
            val x = p.x * size.width + sin(life / 900f + p.phase) * p.sway
            val fade = (life / 400f).coerceAtMost(1f) * ((durationMs - t) / 900f).coerceIn(0f, 1f)
            val c = p.color.copy(alpha = 0.9f * fade)
            rotate(p.spin * life / 1000f + p.phase * 57f, Offset(x, y)) {
                when (p.kind) {
                    0 -> drawPath(starPath(Offset(x, y), p.size * 0.75f, p.size * 0.34f), c)
                    1 -> drawOval(c, Offset(x - p.size * 0.35f, y - p.size * 0.6f), Size(p.size * 0.7f, p.size * 1.2f))
                    else -> drawCircle(c, p.size * 0.42f, Offset(x, y))
                }
            }
        }
    }
}

/** Sine ease 0→1→0 over [period] ms, for things that should breathe in sync with a clock. */
fun breath(tMs: Float, period: Float): Float = (0.5f - 0.5f * kotlin.math.cos(2 * PI * tMs / period)).toFloat()
