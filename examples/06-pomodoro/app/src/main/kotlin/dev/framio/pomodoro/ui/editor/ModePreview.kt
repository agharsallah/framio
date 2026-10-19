package dev.framio.pomodoro.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.framio.pomodoro.R
import dev.framio.pomodoro.core.model.Display
import dev.framio.pomodoro.core.model.Face
import dev.framio.pomodoro.core.model.Mode
import dev.framio.pomodoro.core.model.Rhythm
import dev.framio.pomodoro.ui.components.QuietText
import dev.framio.pomodoro.ui.deep.CALM_ALPHA
import dev.framio.pomodoro.ui.deep.CalmBackground
import dev.framio.pomodoro.ui.faces.TimerFace
import dev.framio.pomodoro.ui.faces.digitsInside
import dev.framio.pomodoro.ui.theme.QuietTheme

/**
 * A small picture of the focus screen this mode gives (the frame at about a third of its size): Full with its [face] in
 * the theme, Calm's dim arc and minutes on near-black, or Blackout's black. It follows every edit at once. Static:
 * a fixed moment a third of the way through a block.
 */
@Composable
fun ModePreview(mode: Mode, accent: Color, face: Face, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val shape = RoundedCornerShape(20.dp)
    val minutes = when (val r = mode.rhythm) {
        is Rhythm.Fixed -> r.focus.inWholeMinutes.toInt()
        is Rhythm.Flow -> 0
    }
    val flow = mode.rhythm is Rhythm.Flow
    val left = (minutes * 2 / 3).coerceAtLeast(1)
    val digits = if (flow) "12:40" else if (mode.environment.showSeconds) "$left:00" else "$left"
    val description = stringResource(R.string.editor_preview, mode.name)
    Column(modifier) {
        Box(
            Modifier
                .aspectRatio(1280f / 800f)
                .clip(shape)
                .background(
                    when (mode.environment.display) {
                        Display.Full -> colors.background
                        Display.Calm -> CalmBackground
                        Display.Blackout -> Color.Black
                    },
                )
                .border(2.dp, colors.line, shape)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            when (mode.environment.display) {
                Display.Full -> FullPreview(face, accent, digits, flow)
                Display.Calm -> CalmPreview(accent, if (flow) "40" else "$left", colors.ink)
                Display.Blackout -> QuietText(
                    stringResource(if (mode.environment.knockToPeek) R.string.preview_blackout_knock else R.string.preview_blackout),
                    QuietTheme.type.caption, color = Color(0xFF6B6B6B), maxLines = 2,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        QuietText(
            stringResource(R.string.preview_caption, stringResource(displayName(mode.environment.display)), stringResource(faceName(face))),
            QuietTheme.type.caption, color = colors.inkDim, maxLines = 1,
        )
    }
}

@Composable
private fun FullPreview(face: Face, accent: Color, digits: String, flow: Boolean) {
    val colors = QuietTheme.colors
    val small = QuietTheme.type.title.copy(fontSize = 30.sp, lineHeight = 34.sp, fontFeatureSettings = "tnum")
    val progress = { if (flow) 0f else 0.33f }
    when {
        face == Face.Digits -> QuietText(digits, small.copy(fontSize = 64.sp, lineHeight = 70.sp), maxLines = 1)
        face.digitsInside -> Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
            TimerFace(face, progress, accent, colors.inkFaint, Modifier.fillMaxSize())
            QuietText(digits, small, maxLines = 1)
        }
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            TimerFace(face, progress, accent, colors.inkFaint, Modifier.size(140.dp))
            Spacer(Modifier.width(16.dp))
            QuietText(digits, small, maxLines = 1)
        }
    }
}

/** Calm: one thin arc and the minutes, at Calm's low alpha (as DeepFocusScreen draws them). */
@Composable
private fun CalmPreview(accent: Color, minutes: String, ink: Color) {
    Box(
        Modifier.size(150.dp).drawWithCache {
            val stroke = 3.dp.toPx()
            val r = size.minDimension / 2f - stroke
            val topLeft = Offset(size.width / 2f - r, size.height / 2f - r)
            onDrawBehind {
                drawArc(accent.copy(alpha = CALM_ALPHA), -90f, 240f, false, topLeft, Size(r * 2, r * 2), style = Stroke(stroke, cap = StrokeCap.Round))
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        QuietText(
            stringResource(R.string.preview_calm_minutes, minutes),
            QuietTheme.type.title.copy(fontSize = 26.sp), color = ink.copy(alpha = CALM_ALPHA), maxLines = 1,
            modifier = Modifier.padding(4.dp),
        )
    }
}
