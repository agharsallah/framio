package dev.framio.pomodoro.ui.deep

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.framio.pomodoro.R
import dev.framio.pomodoro.core.model.InkCodec
import dev.framio.pomodoro.core.model.Stroke
import dev.framio.pomodoro.ui.LocalLandscape
import dev.framio.pomodoro.ui.components.INK_ASPECT
import dev.framio.pomodoro.ui.components.QuietButton
import dev.framio.pomodoro.ui.components.QuietText
import dev.framio.pomodoro.ui.components.QuietTextButton
import dev.framio.pomodoro.ui.theme.QuietTheme

/** The strokes on the pad, in pad pixels. Mutated by the gesture; [version] tells the draw phase to look again. */
class InkBuffer {
    val strokes = ArrayList<ArrayList<Offset>>()
    var points = 0
        private set

    fun start(p: Offset) {
        strokes += arrayListOf(p)
        points++
    }

    fun add(p: Offset): Boolean {
        val s = strokes.lastOrNull() ?: return false
        if (points >= MAX_POINTS || (p - s.last()).getDistanceSquared() < MIN_STEP_SQ) return false
        s += p
        points++
        return true
    }

    fun clear() {
        strokes.clear()
        points = 0
    }

    /** InkCodec text, normalized to [size]; null when the pad is empty. */
    fun encode(size: Size): String? {
        if (strokes.isEmpty() || size.width <= 0f || size.height <= 0f) return null
        return InkCodec.encode(
            strokes.map { s ->
                Stroke(FloatArray(s.size * 2) { i -> if (i % 2 == 0) s[i / 2].x / size.width else s[i / 2].y / size.height })
            },
        )
    }

    private companion object {
        const val MAX_POINTS = 2_000
        const val MIN_STEP_SQ = 9f // 3 px
    }
}

/**
 * The handwriting pad: the parking lot's, and (with its own [title], [hint] and [saveLabel]) notes and resume notes.
 * The parking lot's handwriting pad (PLAN §5: no keyboard during focus, ever). A finger draws; strokes are kept as
 * points and drawn as line strips (tessellated: no path textures to rebuild on every move). "Park it" hands back
 * the ink as InkCodec text, normalized to the pad, whose aspect matches the thumbnails ([INK_ASPECT]).
 */
@Composable
fun ParkPad(
    accent: androidx.compose.ui.graphics.Color,
    onSave: (String?) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.park_title),
    hint: String = stringResource(R.string.park_hint),
    saveLabel: String = stringResource(R.string.park_save),
) {
    val colors = QuietTheme.colors
    val ink = remember { InkBuffer() }
    var version by remember { mutableIntStateOf(0) }
    var padSize by remember { mutableIntStateOf(0) } // width; height follows the aspect
    val landscape = LocalLandscape.current
    val padDescription = stringResource(R.string.park_pad_description)
    Column(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = QuietTheme.spacing.gutter, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                QuietText(title, QuietTheme.type.title, maxLines = 1)
                QuietText(hint, QuietTheme.type.body, color = colors.inkDim, maxLines = 2)
            }
            QuietTextButton(stringResource(R.string.cancel), onCancel)
        }
        Spacer(Modifier.height(20.dp))
        val shape = RoundedCornerShape(28.dp)
        Box(
            Modifier
                .width(if (landscape) 1000.dp else 704.dp)
                .aspectRatio(INK_ASPECT)
                .clip(shape)
                .background(colors.surface)
                .border(2.dp, colors.line, shape)
                .semantics { contentDescription = padDescription }
                .pointerInput(Unit) {
                    padSize = size.width
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        ink.start(down.position)
                        version++
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val p = change.position
                            if (ink.add(Offset(p.x.coerceIn(0f, size.width.toFloat()), p.y.coerceIn(0f, size.height.toFloat())))) version++
                            change.consume()
                        }
                    }
                }
                .drawBehind {
                    if (version < 0) return@drawBehind // read: a new point redraws (draw phase only)
                    val w = 5.dp.toPx()
                    for (s in ink.strokes) {
                        if (s.size == 1) {
                            drawCircle(colors.ink, w / 2, s[0])
                        } else {
                            drawPoints(s, PointMode.Polygon, colors.ink, strokeWidth = w, cap = StrokeCap.Round)
                        }
                    }
                },
        )
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietButton(stringResource(R.string.clear), { ink.clear(); version++ }, modifier = Modifier.width(220.dp))
            QuietButton(
                saveLabel,
                {
                    val w = padSize.toFloat()
                    onSave(ink.encode(Size(w, w / INK_ASPECT)))
                },
                fill = accent,
                modifier = Modifier.width(260.dp),
            )
        }
    }
}
