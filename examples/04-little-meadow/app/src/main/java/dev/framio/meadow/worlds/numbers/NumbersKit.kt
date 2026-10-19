package dev.framio.meadow.worlds.numbers

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.framio.meadow.play.PlayScope
import dev.framio.meadow.ui.Palette
import dev.framio.meadow.ui.darken
import dev.framio.meadow.ui.lighten
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** Waits until the narration (e.g. the activity's intro) has finished, so a prompt doesn't cut it off. */
internal suspend fun PlayScope.awaitQuiet() {
    delay(250)
    app.voice.speaking.first { !it }
}

/**
 * Dots for [n] like a die (1–6) or a ten-frame (7–10, with the empty slots drawn faintly), so a
 * child who can't read numerals yet can match the amount at a glance.
 */
@Composable
internal fun DotPattern(n: Int, modifier: Modifier = Modifier, color: Color = Palette.terracotta) {
    Canvas(modifier) {
        val frame = n > 6
        val dotR = if (frame) size.width * 0.075f else size.width * 0.105f
        if (frame) {
            for ((x, y) in tenFrameSlots()) {
                drawCircle(Palette.ink.copy(alpha = 0.14f), dotR, Offset(x * size.width, y * size.height), style = Stroke(dotR * 0.22f))
            }
        }
        for ((x, y) in dotPattern(n)) {
            val c = Offset(x * size.width, y * size.height)
            drawCircle(
                Brush.radialGradient(listOf(color.lighten(0.3f), color, color.darken(0.08f)), center = c - Offset(dotR * 0.3f, dotR * 0.35f), radius = dotR * 1.3f),
                dotR, c,
            )
        }
    }
}
