package dev.framio.pomodoro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.framio.pomodoro.ui.theme.QuietTheme

/**
 * A one-line text field on the surface colour, typed with the **system IME**: only inside [KeyboardSheet] when
 * Settings → Appearance → Keyboard says "System". [autoFocus] asks for the IME as soon as it appears.
 */
@Composable
fun QuietField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    style: TextStyle = QuietTheme.type.title,
    autoFocus: Boolean = false,
    onDone: () -> Unit = {},
) {
    val colors = QuietTheme.colors
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style.copy(color = colors.ink),
        cursorBrush = SolidColor(colors.ink),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier
            .focusRequester(focus)
            .defaultMinSize(minHeight = QuietTheme.Target)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        decorationBox = { field ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) QuietText(hint, style, color = colors.inkDim, maxLines = 1)
                field()
            }
        },
    )
}

/**
 * A field that opens the keyboard sheet instead of taking focus (the built-in keyboard isn't an IME): it shows [value]
 * (or the [hint]) on the surface colour, and a tap anywhere on it calls [onClick].
 */
@Composable
fun TapField(value: String, hint: String, onClick: () -> Unit, modifier: Modifier = Modifier, style: TextStyle = QuietTheme.type.title) {
    val colors = QuietTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .defaultMinSize(minHeight = QuietTheme.Target)
            .clip(shape)
            .background(colors.surface)
            .border(2.dp, colors.line, shape)
            .clickable(role = Role.Button, onClickLabel = hint, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) QuietText(hint, style, color = colors.inkDim, maxLines = 1) else QuietText(value, style, maxLines = 1)
    }
}

/** A big checklist toggle: a box that fills with a tick, and its label. The whole card is the target. */
@Composable
fun CheckToggle(label: String, checked: Boolean, accent: Color, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier
            .defaultMinSize(minHeight = 96.dp)
            .clip(shape)
            .background(if (checked) colors.surfaceHigh else colors.surface)
            .border(2.dp, if (checked) accent else colors.line, shape)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tick(checked, accent, colors.line, colors.background, Modifier.size(40.dp))
        Spacer(Modifier.width(18.dp))
        QuietText(label, QuietTheme.type.label, maxLines = 2)
    }
}

/** The box of a [CheckToggle]: an outline, or filled with [accent] and a tick in [mark]. Static. */
@Composable
private fun Tick(checked: Boolean, accent: Color, line: Color, mark: Color, modifier: Modifier = Modifier) {
    Spacer(
        modifier.drawWithCache {
            val w = size.width
            val r = androidx.compose.ui.geometry.CornerRadius(w * 0.22f)
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.08f)
            onDrawBehind {
                if (checked) {
                    drawRoundRect(accent, cornerRadius = r)
                    val s = w * 0.1f
                    drawLine(mark, Offset(w * 0.26f, w * 0.52f), Offset(w * 0.43f, w * 0.69f), strokeWidth = s, cap = StrokeCap.Round)
                    drawLine(mark, Offset(w * 0.43f, w * 0.69f), Offset(w * 0.75f, w * 0.33f), strokeWidth = s, cap = StrokeCap.Round)
                } else {
                    drawRoundRect(line, cornerRadius = r, style = stroke)
                }
            }
        },
    )
}
