package dev.framio.pomodoro.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.framio.pomodoro.R
import dev.framio.pomodoro.core.notes.ReasonTag
import dev.framio.pomodoro.core.notes.TagId
import dev.framio.pomodoro.ui.components.QuietText
import dev.framio.pomodoro.ui.theme.ChartColors
import dev.framio.pomodoro.ui.theme.QuietTheme

/**
 * One reason as a chip (DESIGN-history §3): the tag's colour dot and glyph, then its label in ink (never in the tag
 * colour). Selected = a stronger surface and an ink outline, plus a tick; [count] shows "×2" for repeats. At least
 * 72 px tall.
 */
@Composable
fun ReasonChip(
    tag: ReasonTag,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int = 0,
    height: Dp = QuietTheme.Target,
) {
    val colors = QuietTheme.colors
    val chart = ChartColors.of(colors)
    val shape = RoundedCornerShape(height / 2)
    val back = if (selected) colors.surfaceHigh else colors.surface
    Row(
        modifier
            .defaultMinSize(minHeight = height)
            .clip(shape)
            .background(back)
            .border(if (selected) 3.dp else 2.dp, if (selected) colors.ink else colors.line, shape)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(start = 18.dp, end = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(chart.tag(tag.hue)))
        Spacer(Modifier.width(10.dp))
        TagGlyph(tag.icon, colors.ink, back, Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        QuietText(tag.label, QuietTheme.type.label, maxLines = 1)
        if (count > 1) {
            Spacer(Modifier.width(8.dp))
            QuietText(stringResource(R.string.times_count, count), QuietTheme.type.caption, color = colors.inkDim, maxLines = 1)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            TagGlyph(dev.framio.pomodoro.core.notes.TagIcon.Check, colors.ink, back, Modifier.size(22.dp))
        }
    }
}

/**
 * Reason chips in the user's order, wrapping. With [limit], only the first ones show, then "More" ([onMore]): the
 * hold panel's 6 + More (Hick). [counts] marks tags already logged (and selects them).
 */
@Composable
fun ReasonChips(
    tags: List<ReasonTag>,
    selected: Set<TagId>,
    onToggle: (TagId) -> Unit,
    modifier: Modifier = Modifier,
    counts: Map<TagId, Int> = emptyMap(),
    limit: Int = Int.MAX_VALUE,
    onMore: (() -> Unit)? = null,
    height: Dp = QuietTheme.Target,
    gap: Dp = 14.dp,
) {
    val shown = if (tags.size > limit) tags.take(limit) else tags
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
        shown.forEach { t ->
            ReasonChip(t, selected = t.id in selected || (counts[t.id] ?: 0) > 0, onClick = { onToggle(t.id) }, count = counts[t.id] ?: 0, height = height)
        }
        if (onMore != null && tags.size > shown.size) {
            MoreChip(onMore, height)
        }
    }
}

@Composable
private fun MoreChip(onClick: () -> Unit, height: Dp, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier
            .defaultMinSize(minHeight = height, minWidth = QuietTheme.Target)
            .clip(shape)
            .border(2.dp, colors.line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        QuietText(stringResource(R.string.more_reasons), QuietTheme.type.label, color = colors.inkDim, maxLines = 1)
    }
}
