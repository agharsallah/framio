package dev.framio.pomodoro.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.framio.pomodoro.R
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.ui.components.QuietText
import dev.framio.pomodoro.ui.components.QuietTextButton
import dev.framio.pomodoro.ui.theme.QuietTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration

/** "25 min", "2 h 05 min": whole minutes, never seconds (no fake precision, DESIGN-history §8). */
@Composable
@ReadOnlyComposable
fun durationText(d: Duration): String {
    val m = d.inWholeMinutes.toInt()
    return if (m < 60) pluralStringResource(R.plurals.minutes_short, m, m) else stringResource(R.string.duration_hm, m / 60, m % 60)
}

@Composable
@ReadOnlyComposable
fun outcomeText(o: Outcome): String = stringResource(
    when (o) {
        Outcome.Done -> R.string.outcome_done
        Outcome.Partly -> R.string.outcome_partly
        Outcome.NotYet -> R.string.outcome_not_yet
        Outcome.Unrated -> R.string.outcome_unrated
        Outcome.EndedEarly -> R.string.outcome_ended_early
        Outcome.Interrupted -> R.string.outcome_interrupted
    },
)

/** "Today · Sun 18 Oct", "Yesterday · Sat 17 Oct", "Thu 15 Oct", "Date unknown". */
@Composable
fun dayText(date: LocalDate?, today: LocalDate): String {
    val format = remember { DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()) }
    val year = remember { DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.getDefault()) }
    return when {
        date == null -> stringResource(R.string.date_unknown)
        date == today -> stringResource(R.string.day_today, date.format(format))
        date == today.minusDays(1) -> stringResource(R.string.day_yesterday, date.format(format))
        date.year != today.year -> date.format(year)
        else -> date.format(format)
    }
}

/**
 * A chooser over the screen: a scrim (a tap on it closes), and a panel at the bottom with a title and Done. The panel
 * leaves the bottom-left 88 px free.
 */
@Composable
fun ChooserSheet(title: String, onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = QuietTheme.colors
    Box(
        modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = 0.7f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp))
                .background(colors.surfaceHigh)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .padding(start = QuietTheme.spacing.gutter, end = QuietTheme.spacing.gutter, top = 24.dp, bottom = 104.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                QuietText(title, QuietTheme.type.title, maxLines = 1, modifier = Modifier.weight(1f))
                QuietTextButton(stringResource(R.string.done), onClose, color = colors.ink)
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

/** "Deleted · Undo", bottom centre (clear of the hot corner), for a few seconds. Never a confirm dialog. */
@Composable
fun UndoBar(message: String, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(36.dp))
            .background(colors.ink)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 32.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuietText(message, QuietTheme.type.label, color = colors.background, maxLines = 1)
        Spacer(Modifier.width(16.dp))
        QuietTextButton(stringResource(R.string.undo), onUndo, color = colors.background)
    }
}
