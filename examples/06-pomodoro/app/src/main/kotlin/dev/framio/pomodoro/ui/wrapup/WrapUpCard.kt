package dev.framio.pomodoro.ui.wrapup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.framio.pomodoro.R
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.ui.components.Chip
import dev.framio.pomodoro.ui.components.QuietText
import dev.framio.pomodoro.ui.components.QuietTextButton
import dev.framio.pomodoro.ui.theme.QuietTheme

/**
 * "How did that go?" on the break screen of auto-break modes (Classic, Flow, Gentle): Done · Partly · Not yet answers
 * at once, Skip logs the block as unrated. Built from sections: [content] slots more of them in between the answers
 * and Skip (e.g. a "log a distraction" step), without touching these.
 */
@Composable
fun WrapUpCard(
    accent: Color,
    onAnswer: (Outcome?) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(QuietTheme.colors.surface)
            .padding(horizontal = 32.dp, vertical = 28.dp),
    ) {
        QuietText(stringResource(R.string.wrap_question), QuietTheme.type.title, maxLines = 1)
        Spacer(Modifier.height(20.dp))
        OutcomeChips(selected = null, accent = accent, onPick = onAnswer)
        content()
        Spacer(Modifier.height(8.dp))
        QuietTextButton(stringResource(R.string.wrap_skip), { onAnswer(null) })
    }
}

/** Done · Partly · Not yet; [selected] is filled. Shared by the break card and Deep's wrap-up screen. */
@Composable
fun OutcomeChips(selected: Outcome?, accent: Color, onPick: (Outcome) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        for ((o, text) in Outcomes) {
            Chip(stringResource(text), selected = selected == o, accent = accent, onClick = { onPick(o) })
        }
    }
}

private val Outcomes = listOf(Outcome.Done to R.string.outcome_done, Outcome.Partly to R.string.outcome_partly, Outcome.NotYet to R.string.outcome_not_yet)
