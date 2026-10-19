package dev.framio.meadow.worlds.habits

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.rememberSlowClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.sin

/**
 * Waits until the activity's title (said when its card was tapped) has finished, so the first line
 * of the activity doesn't cut it off.
 */
internal suspend fun awaitQuiet(s: PlayScope, maxMs: Long = 2600) {
    delay(250)
    withTimeoutOrNull(maxMs) { s.app.voice.speaking.first { !it } }
}

/** A soft four-point twinkle. */
internal fun twinkle(c: Offset, r: Float): Path = Path().apply {
    val k = r * 0.22f
    moveTo(c.x, c.y - r)
    quadraticTo(c.x + k, c.y - k, c.x + r, c.y)
    quadraticTo(c.x + k, c.y + k, c.x, c.y + r)
    quadraticTo(c.x - k, c.y + k, c.x - r, c.y)
    quadraticTo(c.x - k, c.y - k, c.x, c.y - r)
    close()
}

/**
 * Little stars that slowly swell and fade at [spots] (fractions of the canvas), each on its own
 * phase: a calm "sparkly clean", never a flash. One infinite animation, read in the draw phase.
 */
@Composable
internal fun Sparkles(
    spots: List<Pair<Float, Float>>, modifier: Modifier = Modifier, color: Color = Palette.cream, size: Float = 22f,
    clock: State<Long> = rememberSlowClock(),
) {
    Canvas(modifier) {
        val p = (clock.value % 2400L) / 2400f
        spots.forEachIndexed { i, (fx, fy) ->
            val ph = (p + i * 0.37f) % 1f
            val a = sin(ph * PI).toFloat()
            val r = size * density * (0.45f + 0.55f * a)
            val c = Offset(fx * this.size.width, fy * this.size.height)
            drawPath(twinkle(c, r), color.copy(alpha = 0.85f * a))
            drawPath(twinkle(c, r * 0.45f), Color.White.copy(alpha = 0.9f * a))
        }
    }
}
