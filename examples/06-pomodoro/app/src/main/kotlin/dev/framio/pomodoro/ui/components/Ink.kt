package dev.framio.pomodoro.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.framio.pomodoro.core.model.InkCodec

/** Pads and thumbnails share this aspect (width / height), so a scribble looks the same in both. */
const val INK_ASPECT: Float = 2f

/**
 * A parked thought, drawn from its ink (InkCodec's compact strokes, normalized to the pad). Decoded once per [ink];
 * the paths are built once per size. Static: no animation.
 */
@Composable
fun InkThumb(ink: String, color: Color, description: String, modifier: Modifier = Modifier, width: Dp = 3.dp) {
    val strokes = remember(ink) { InkCodec.decode(ink).orEmpty() }
    Spacer(
        modifier
            .semantics { contentDescription = description }
            .drawWithCache {
                val path = Path()
                for (s in strokes) {
                    if (s.size == 0) continue
                    path.moveTo(s.points[0] * size.width, s.points[1] * size.height)
                    if (s.size == 1) path.lineTo(s.points[0] * size.width + 0.1f, s.points[1] * size.height)
                    for (p in 1 until s.size) path.lineTo(s.points[2 * p] * size.width, s.points[2 * p + 1] * size.height)
                }
                val style = Stroke(width.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                onDrawBehind { drawPath(path, color, style = style) }
            },
    )
}
