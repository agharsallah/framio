package dev.framio.pomodoro.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.framio.pomodoro.R
import dev.framio.pomodoro.ui.theme.QuietTheme

/**
 * "↳ Last time: …": the last resume note as words, handwriting, or both (DESIGN-history §5.6). Nothing when neither.
 */
@Composable
fun ResumeLine(note: String?, ink: String?, modifier: Modifier = Modifier, maxLines: Int = 2) {
    if (note == null && ink == null) return
    val colors = QuietTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (note != null) {
            QuietText(stringResource(R.string.last_time, note), QuietTheme.type.body, color = colors.inkDim, maxLines = maxLines, modifier = Modifier.weight(1f, fill = false))
        } else {
            QuietText(stringResource(R.string.last_time_ink), QuietTheme.type.body, color = colors.inkDim, maxLines = 1)
        }
        if (ink != null) {
            Spacer(Modifier.width(16.dp))
            InkThumb(ink, colors.inkDim, stringResource(R.string.note_title), Modifier.size(140.dp, (140 / INK_ASPECT).dp))
        }
    }
}
