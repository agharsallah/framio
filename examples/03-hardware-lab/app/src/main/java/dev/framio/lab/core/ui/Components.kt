package dev.framio.lab.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.framio.lab.core.Check
import dev.framio.lab.core.Status

/** Standard lab layout: title, one-line summary, then scrolling sections. */
@Composable
fun LabPage(
    title: String,
    summary: String,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = Modifier.fillMaxSize()
    Column(
        (if (scroll) base.verticalScroll(rememberScrollState()) else base).padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(summary, style = MaterialTheme.typography.bodyMedium.copy(color = InkDim))
        }
        content()
    }
}

/** A titled card. [trailing] sits at the right of the title row (buttons, status). */
@Composable
fun Section(
    title: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Surface1)
            .border(1.dp, Hairline, shape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (note != null) Text(note, style = MaterialTheme.typography.bodySmall)
            }
            trailing()
        }
        content()
    }
}

/** Label over value, in a grid of [columns]. */
@Composable
fun StatGrid(items: List<Pair<String, String>>, columns: Int = 4) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { (label, value) -> Stat(label, value, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Key/value rows for longer text (paths, fingerprints). */
@Composable
fun KeyValues(rows: List<Pair<String, String>>, keyWidth: Dp = 220.dp) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { (k, v) ->
            Row {
                Text(k, Modifier.width(keyWidth), style = MaterialTheme.typography.bodyMedium.copy(color = InkDim))
                Text(v, Modifier.weight(1f), style = Mono)
            }
        }
    }
}

fun statusColor(status: Status): Color = when (status) {
    Status.PASS -> Good
    Status.WARN -> Caution
    Status.FAIL -> Bad
    Status.ABSENT -> Neutral
    Status.INFO -> Sky
    Status.MANUAL -> Violet
}

@Composable
fun StatusChip(status: Status, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(status.name, style = MaterialTheme.typography.labelSmall.copy(color = color))
    }
}

/** A list of self-test results with their status chips. */
@Composable
fun CheckRows(checks: List<Check>, showLab: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        checks.forEach { c ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusChip(c.status, Modifier.width(76.dp))
                Text(
                    if (showLab) "${c.lab} · ${c.name}" else c.name,
                    Modifier.width(260.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(c.detail, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(color = InkDim))
            }
        }
    }
}

/** Live line chart. [min]/[max] null = autoscale. */
@Composable
fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
    min: Float? = null,
    max: Float? = null,
    capacity: Int = values.size,
) {
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(10.dp))
            .background(Surface0),
    ) {
        if (values.size < 2) return@Canvas
        val lo = min ?: values.min()
        val hi = (max ?: values.max()).let { if (it - lo < 1e-6f) lo + 1f else it }
        val n = maxOf(capacity, values.size)
        val dx = size.width / (n - 1)
        val x0 = (n - values.size) * dx
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = x0 + i * dx
            val y = size.height - (v.coerceIn(lo, hi) - lo) / (hi - lo) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        // zero line when the range crosses it
        if (lo < 0f && hi > 0f) {
            val y0 = size.height - (0f - lo) / (hi - lo) * size.height
            drawLine(Hairline, Offset(0f, y0), Offset(size.width, y0), 1f)
        }
        drawPath(path, color, style = Stroke(2.dp.toPx()))
    }
}

@Composable
fun ActionButton(text: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = true) {
    if (primary) {
        Button(
            onClick,
            enabled = enabled,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Teal, contentColor = Surface0),
        ) { Text(text, style = MaterialTheme.typography.labelLarge.copy(color = Surface0)) }
    } else {
        OutlinedButton(
            onClick,
            enabled = enabled,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        ) { Text(text, style = MaterialTheme.typography.labelLarge) }
    }
}

/** Buttons in a row with even spacing. */
@Composable
fun Actions(content: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(180.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value,
            onValueChange,
            Modifier.weight(1f),
            enabled = enabled,
            valueRange = range,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            colors = SliderDefaults.colors(thumbColor = Teal, activeTrackColor = Teal, inactiveTrackColor = Hairline),
        )
        Text(valueText, Modifier.width(120.dp).padding(start = 16.dp), style = Mono)
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, note: String? = null, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked,
            onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Teal, checkedThumbColor = Surface0),
        )
    }
}

/** A single choice out of a few options, as a row of pill buttons. */
@Composable
fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val on = o == selected
            OutlinedButton(
                { onSelect(o) },
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (on) Teal.copy(alpha = 0.18f) else Color.Transparent,
                    contentColor = if (on) Teal else Ink,
                ),
            ) { Text(label(o), style = MaterialTheme.typography.labelLarge.copy(color = if (on) Teal else Ink)) }
        }
    }
}

/** Grey explanatory text inside a section. */
@Composable
fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall)
}

/** Big status line: chip + sentence. */
@Composable
fun Verdict(status: Status, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusChip(status)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
