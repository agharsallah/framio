package dev.framio.pomodoro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.framio.pomodoro.ui.theme.QuietTheme

/** Text in the theme's ink unless told otherwise. One line ellipsizes; more lines clip. */
@Composable
fun QuietText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign = TextAlign.Unspecified,
) {
    val ink = if (color == Color.Unspecified) QuietTheme.colors.ink else color
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = ink, textAlign = align),
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

/**
 * A rounded button at least [QuietTheme.Target] tall ([QuietTheme.BigTarget] when [big]). Filled with [fill], or
 * a quiet outline on the surface colour.
 */
@Composable
fun QuietButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color? = null,
    big: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = QuietTheme.colors
    val shape = RoundedCornerShape(if (big) 40.dp else 24.dp)
    val ink = when {
        !enabled -> colors.inkDim
        fill != null -> colors.background
        else -> colors.ink
    }
    Row(
        modifier
            .defaultMinSize(minWidth = if (big) QuietTheme.BigTarget else QuietTheme.Target, minHeight = if (big) QuietTheme.BigTarget else QuietTheme.Target)
            .clip(shape)
            .then(
                if (fill != null && enabled) {
                    Modifier.background(fill)
                } else {
                    Modifier.background(colors.surface).border(2.dp, colors.line, shape)
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (big) 48.dp else 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        QuietText(label, if (big) QuietTheme.type.display else QuietTheme.type.label, color = ink, maxLines = 1)
    }
}

/** A text-only action for the corners (Settings, Exit, Home): quiet, but a full 72 px target. */
@Composable
fun QuietTextButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Box(
        modifier
            .defaultMinSize(minWidth = QuietTheme.Target, minHeight = QuietTheme.Target)
            .clip(RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        QuietText(label, QuietTheme.type.label, color = if (color == Color.Unspecified) QuietTheme.colors.inkDim else color, maxLines = 1)
    }
}

/** A choice chip (tasks, wrap-up outcomes): outlined, filled with [accent] when [selected]. */
@Composable
fun Chip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val shape = RoundedCornerShape(36.dp)
    Box(
        modifier
            .defaultMinSize(minHeight = QuietTheme.Target, minWidth = QuietTheme.Target)
            .clip(shape)
            .then(if (selected) Modifier.background(accent) else Modifier.background(colors.surface).border(2.dp, colors.line, shape))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        QuietText(label, QuietTheme.type.label, color = if (selected) colors.background else colors.ink, maxLines = 1)
    }
}
