package dev.framio.pomodoro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.framio.pomodoro.ui.theme.QuietTheme

/**
 * A setting that is on or off: the label (and an optional [note] under it) on the left, a drawn switch on the right; the
 * whole row is the target (≥ 72 px tall). Disabled rows read dim and ignore taps.
 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    note: String? = null,
    enabled: Boolean = true,
    accent: Color = QuietTheme.colors.ink,
) {
    val colors = QuietTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = QuietTheme.Target)
            .clip(RoundedCornerShape(20.dp))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            QuietText(label, QuietTheme.type.body, color = if (enabled) colors.ink else colors.inkDim, maxLines = 2)
            if (note != null) QuietText(note, QuietTheme.type.caption, color = colors.inkDim, maxLines = 3)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked, if (enabled) accent else colors.line)
    }
}

/** The switch itself: a pill with a knob, filled with [on] when checked. Static (no animation to redraw). */
@Composable
private fun Switch(checked: Boolean, on: Color, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .size(84.dp, 48.dp)
            .clip(shape)
            .background(if (checked) on else colors.surfaceHigh)
            .border(2.dp, if (checked) on else colors.line, shape)
            .padding(6.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(if (checked) colors.background else colors.inkDim))
    }
}

/** A label above a row of choice chips (one selected); wraps when the chips don't fit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    name: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    note: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    val colors = QuietTheme.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            QuietText(label, QuietTheme.type.body, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            trailing()
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            options.forEach { o -> Chip(name(o), selected = o == selected, accent = accent, onClick = { onSelect(o) }) }
        }
        if (note != null) {
            Spacer(Modifier.height(8.dp))
            QuietText(note, QuietTheme.type.caption, color = colors.inkDim, maxLines = 4)
        }
    }
}

/** The 8 accent dots (each a full 72 px target), the chosen one ringed. */
@Composable
fun AccentPicker(selected: Int, onSelect: (Int) -> Unit, describe: (Int) -> String, modifier: Modifier = Modifier, perRow: Int = 4) {
    val colors = QuietTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        (0 until colors.accentCount).chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { i ->
                    val description = describe(i)
                    Box(
                        Modifier
                            .size(QuietTheme.Target)
                            .clip(CircleShape)
                            .clickable(role = Role.RadioButton) { onSelect(i) }
                            .semantics {
                                contentDescription = description
                                this.selected = i == selected
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (i == selected) Box(Modifier.size(56.dp).border(3.dp, colors.ink, CircleShape))
                        Box(Modifier.size(40.dp).clip(CircleShape).background(colors.accent(i)))
                    }
                }
            }
        }
    }
}

/**
 * For a full-screen sheet over another screen: taps on its empty parts go nowhere (not to the screen behind). Unlike a
 * disabled `clickable`, it adds no semantics, so the sheet's own buttons stay separate nodes for accessibility.
 */
fun Modifier.swallowTouches(): Modifier = pointerInput(Unit) { detectTapGestures { } }

/** A titled group of settings on the surface colour. */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = QuietTheme.colors
    Column(modifier.fillMaxWidth()) {
        QuietText(title, QuietTheme.type.label, color = colors.inkDim, maxLines = 1, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(colors.surface).padding(vertical = 8.dp)) { content() }
    }
}
