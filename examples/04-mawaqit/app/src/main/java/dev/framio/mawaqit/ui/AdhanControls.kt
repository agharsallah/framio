package dev.framio.mawaqit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.framio.mawaqit.core.PhaseKind
import dev.framio.mawaqit.engine.DisplayState

/** The adhan's own controls: on its screen, or wherever an adhan (or a test) is heard without it. */
fun showsAdhanControls(st: DisplayState): Boolean =
    !st.screen.black && st.overlay == null &&
        (st.phase.kind == PhaseKind.ADHAN || st.audio?.what?.let { it.startsWith("adhan:") || it.startsWith("test:") } == true)

/** Mute, − volume +, and Skip (stops the audio, ends the adhan screen). Volume steps of 10. */
@Composable
fun AdhanControls(st: DisplayState, volume: (Int?, Boolean?) -> Unit, skip: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val audio = st.audio
    Row(
        modifier.clip(RoundedCornerShape(50)).background(p.card).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (audio != null) {
            AdRoundButton({ volume(null, !audio.muted) }, size = 60.dp, selected = audio.muted) {
                SpeakerGlyph(if (audio.muted) p.accent else p.ink, audio.muted, Modifier.size(34.dp))
            }
            AdRoundButton({ volume(audio.volume - 10, null) }, size = 60.dp) {
                BasicText("−", style = Base.copy(color = p.ink, fontSize = 34.sp))
            }
            VolumeBar(if (audio.muted) 0 else audio.volume, Modifier.width(160.dp).height(10.dp))
            BasicText(
                if (audio.muted) "0" else "${audio.volume}",
                Modifier.width(48.dp),
                style = digits(24.sp, p.inkDim),
            )
            AdRoundButton({ volume(audio.volume + 10, null) }, size = 60.dp) {
                BasicText("+", style = Base.copy(color = p.ink, fontSize = 32.sp))
            }
            Spacer(Modifier.width(4.dp))
        }
        AdPill(st.strings["adhan_skip"], skip, primary = true) { SkipGlyph(p.onAccent, Modifier.size(22.dp)) }
    }
}

@Composable
private fun VolumeBar(percent: Int, modifier: Modifier) {
    val p = LocalPalette.current
    Box(modifier.clip(RoundedCornerShape(50)).background(p.hairline)) {
        if (percent > 0) Box(Modifier.fillMaxHeight().fillMaxWidth(percent / 100f).clip(RoundedCornerShape(50)).background(p.accent))
    }
}

/** A loudspeaker, with its waves or crossed out. */
@Composable
private fun SpeakerGlyph(color: Color, muted: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val body = Path().apply {
            moveTo(w * 0.12f, w * 0.38f)
            lineTo(w * 0.28f, w * 0.38f)
            lineTo(w * 0.5f, w * 0.18f)
            lineTo(w * 0.5f, w * 0.82f)
            lineTo(w * 0.28f, w * 0.62f)
            lineTo(w * 0.12f, w * 0.62f)
            close()
        }
        drawPath(body, color)
        val s = w * 0.07f
        if (muted) {
            drawLine(color, Offset(w * 0.62f, w * 0.36f), Offset(w * 0.9f, w * 0.64f), s, StrokeCap.Round)
            drawLine(color, Offset(w * 0.9f, w * 0.36f), Offset(w * 0.62f, w * 0.64f), s, StrokeCap.Round)
        } else {
            for (r in listOf(0.18f, 0.32f)) {
                drawArc(color, -45f, 90f, false, Offset(w * (0.5f - r), w * (0.5f - r)), Size(w * 2 * r, w * 2 * r), style = Stroke(s, cap = StrokeCap.Round))
            }
        }
    }
}

/** ▶▏: skip to what follows. */
@Composable
private fun SkipGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val tri = Path().apply {
            moveTo(w * 0.15f, w * 0.15f)
            lineTo(w * 0.7f, w * 0.5f)
            lineTo(w * 0.15f, w * 0.85f)
            close()
        }
        drawPath(tri, color)
        drawLine(color, Offset(w * 0.82f, w * 0.15f), Offset(w * 0.82f, w * 0.85f), w * 0.12f, StrokeCap.Round)
    }
}
