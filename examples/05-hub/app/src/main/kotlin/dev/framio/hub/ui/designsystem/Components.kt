package dev.framio.hub.ui.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun HubText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = if (color == Color.Unspecified) style else style.copy(color = color),
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

/** A rounded button: label, optional icon, at least [Hub.TARGET] tall. Filled with [fill] or outlined. */
@Composable
fun HubButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
    fill: Color? = null,
    content: Color = Color.Unspecified,
    big: Boolean = false,
) {
    val colors = Hub.colors
    val ink = if (content == Color.Unspecified) (if (fill != null) colors.surface0 else colors.ink) else content
    val shape = RoundedCornerShape(if (big) 32.dp else 22.dp)
    Row(
        modifier
            .defaultMinSize(minHeight = (if (big) Hub.BIG_TARGET else Hub.TARGET).dp, minWidth = Hub.TARGET.dp)
            .clip(shape)
            .then(if (fill != null) Modifier.background(fill) else Modifier.border(2.dp, colors.hairline, shape).background(colors.surface1))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = if (big) 36.dp else 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) {
            Icon(glyph, ink, size = if (big) 48.dp else 32.dp)
            if (label.isNotEmpty()) Spacer(Modifier.width(14.dp))
        }
        if (label.isNotEmpty()) HubText(label, if (big) Hub.type.title else Hub.type.label, color = ink, maxLines = 1)
    }
}

/** A round icon-only button with a spoken label. */
@Composable
fun IconButton(glyph: Glyph, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = Hub.TARGET.dp, tint: Color = Color.Unspecified) {
    val colors = Hub.colors
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.surface2)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(glyph, if (tint == Color.Unspecified) colors.ink else tint, size = size * 0.5f)
    }
}

/**
 * Press and hold for [holdMs] to act: for anything that can't be undone, and for Settings (keeps children
 * out without a PIN). A ring fills while held; letting go early does nothing.
 */
@Composable
fun HoldButton(
    label: String,
    onHeld: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
    holdMs: Int = 1_500,
    accent: Color = Color.Unspecified,
    onTap: (() -> Unit)? = null,
) {
    val colors = Hub.colors
    val ring = if (accent == Color.Unspecified) colors.amber else accent
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier
            .defaultMinSize(minHeight = Hub.TARGET.dp, minWidth = Hub.TARGET.dp)
            .clip(shape)
            .border(2.dp, colors.hairline, shape)
            .background(colors.surface1)
            .drawBehind {
                val p = progress.value
                if (p > 0f) drawRect(ring.copy(alpha = 0.28f), size = Size(size.width * p, size.height))
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = "hold"
                onClick(label) {
                    onHeld()
                    true
                }
            }
            .pointerInput(holdMs) {
                awaitEachGesture {
                    awaitFirstDown()
                    val job = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(holdMs, easing = LinearEasing))
                        onHeld()
                    }
                    val up = waitForUpOrCancellation()
                    val held = progress.value >= 1f
                    job.cancel()
                    scope.launch { progress.animateTo(0f, tween(150)) }
                    if (!held && up != null) onTap?.invoke()
                }
            }
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) {
            Icon(glyph, colors.ink, size = 32.dp)
            if (label.isNotEmpty()) Spacer(Modifier.width(14.dp))
        }
        if (label.isNotEmpty()) HubText(label, Hub.type.label, maxLines = 1)
    }
}

/** A pill with a coloured dot (never colour alone: always with its text). */
@Composable
fun HubChip(text: String, modifier: Modifier = Modifier, dot: Color? = null, glyph: Glyph? = null, emphasis: Boolean = false, onClick: (() -> Unit)? = null) {
    val colors = Hub.colors
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier
            .height(56.dp)
            .clip(shape)
            .background(if (emphasis) (dot ?: colors.amber).copy(alpha = 0.22f) else colors.surface1.copy(alpha = 0.72f))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(12.dp))
        }
        if (glyph != null) {
            Icon(glyph, colors.inkDim, size = 26.dp)
            Spacer(Modifier.width(10.dp))
        }
        HubText(text, Hub.type.title.copy(fontSize = Hub.type.title.fontSize * 0.9f), maxLines = 1, color = colors.ink)
    }
}

/** A choice among a few options, as a row of segments. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val colors = Hub.colors
    Row(modifier.clip(RoundedCornerShape(22.dp)).background(colors.surface1), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .defaultMinSize(minHeight = Hub.TARGET.dp, minWidth = 120.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (on) colors.ink else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(value) }
                    .semantics { stateDescription = if (on) "selected" else "" }
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) {
                HubText(label, Hub.type.label, color = if (on) colors.surface0 else colors.inkDim, maxLines = 1)
            }
        }
    }
}

/** An on/off switch row: the whole row is the target. */
@Composable
fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, detail: String? = null) {
    val colors = Hub.colors
    Row(
        modifier
            .defaultMinSize(minHeight = Hub.TARGET.dp)
            .clickable(role = Role.Switch) { onChange(!checked) }
            .semantics { stateDescription = if (checked) "on" else "off" }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            HubText(title, Hub.type.body)
            if (detail != null) HubText(detail, Hub.type.caption, color = colors.inkDim)
        }
        val track = if (checked) colors.good else colors.surface2
        Box(
            Modifier.size(84.dp, 48.dp).clip(RoundedCornerShape(24.dp)).background(track),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.padding(6.dp).size(36.dp).clip(CircleShape).background(colors.ink))
        }
    }
}

/** − value + for times and numbers, without a keyboard. */
@Composable
fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier, description: String = value) {
    val colors = Hub.colors
    Row(modifier.semantics(mergeDescendants = false) { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
        IconButton(Glyph.Minus, "less", onMinus, size = 64.dp)
        HubText(value, Hub.type.title, Modifier.width(132.dp).padding(horizontal = 8.dp), color = colors.ink, maxLines = 1)
        IconButton(Glyph.Plus, "more", onPlus, size = 64.dp)
    }
}

/** A dark card for panel content. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(28.dp)).background(Hub.colors.surface1).padding(24.dp)) { content() }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    HubText(text.uppercase(), Hub.type.label, modifier, color = Hub.colors.inkDim, maxLines = 1)
}

/** A thin progress ring, e.g. a routine's progress. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.progressRing(progress: Float, color: Color, track: Color, width: Float) {
    val inset = width / 2
    val arcSize = Size(size.width - width, size.height - width)
    drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(width))
    drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(width, cap = androidx.compose.ui.graphics.StrokeCap.Round))
}
