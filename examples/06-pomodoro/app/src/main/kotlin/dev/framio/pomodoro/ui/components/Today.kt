package dev.framio.pomodoro.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.framio.pomodoro.R
import dev.framio.pomodoro.ui.faces.TargetRing
import dev.framio.pomodoro.ui.theme.QuietTheme

/** "◔ 3 / 6 today": sessions against the daily target, never a streak. */
@Composable
fun TodayProgress(done: Int, target: Int, accent: Color, modifier: Modifier = Modifier) {
    val description = pluralStringResource(R.plurals.today_description, target, done, target)
    Row(modifier.clearAndSetSemantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
        TargetRing(
            fraction = if (target > 0) done.toFloat() / target else 0f,
            accent = accent,
            track = QuietTheme.colors.line,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.width(14.dp))
        QuietText(stringResource(R.string.today_progress, done, target), QuietTheme.type.label, color = QuietTheme.colors.inkDim, maxLines = 1)
    }
}
