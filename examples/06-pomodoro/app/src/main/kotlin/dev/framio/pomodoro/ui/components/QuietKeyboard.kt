package dev.framio.pomodoro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.framio.pomodoro.R
import dev.framio.pomodoro.ui.LocalLandscape
import dev.framio.pomodoro.ui.keyboard.KeyLayer
import dev.framio.pomodoro.ui.keyboard.KeyboardModel
import dev.framio.pomodoro.ui.keyboard.Shift
import dev.framio.pomodoro.ui.theme.LocalSystemKeyboard
import dev.framio.pomodoro.ui.theme.QuietTheme
import kotlin.math.roundToInt

/**
 * The built-in keyboard (M5; DESIGN-history §4: the exception, never required), used wherever text is typed: a task,
 * the if-then line, a checklist item, a mode or reason name, a note. Large keys in the theme, QWERTY plus one
 * numbers/symbols layer, accents on a long press (FR/DE and more, see [KeyboardModel.Variants]), Shift/Caps, Backspace
 * that repeats while held, Done. Not backed by a focused text field, so the system IME never pops up (M0: it covers
 * half the screen in light grey, and the launcher's dot sits on its `?123`). The bottom-left 88×88 px stay empty.
 *
 * Settings → Appearance → Keyboard can switch to the system IME instead ([LocalSystemKeyboard]); the sheet then shows
 * a real field at the top (the IME covers the bottom 410 px).
 */
@Composable
fun KeyboardSheet(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    maxLength: Int = 80,
    doneLabel: String = stringResource(R.string.done),
    hint: String = "",
) {
    val colors = QuietTheme.colors
    val system = LocalSystemKeyboard.current
    Column(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .swallowTouches()
            .padding(top = 28.dp, bottom = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = QuietTheme.spacing.gutter), verticalAlignment = Alignment.CenterVertically) {
            QuietText(title, QuietTheme.type.title, maxLines = 1, modifier = Modifier.weight(1f))
            QuietTextButton(stringResource(R.string.cancel), onCancel)
            if (system) {
                Spacer(Modifier.width(QuietTheme.spacing.s))
                QuietButton(doneLabel, onDone, fill = colors.ink)
            }
        }
        Spacer(Modifier.height(16.dp))
        if (system) {
            QuietField(
                value, { onValueChange(it.take(maxLength)) }, hint,
                Modifier.fillMaxWidth().padding(horizontal = QuietTheme.spacing.gutter), autoFocus = true, onDone = onDone,
            )
            return@Column
        }
        val scope = rememberCoroutineScope()
        val change by rememberUpdatedState(onValueChange)
        val model = remember { KeyboardModel(value, scope, maxLength) }
        SideEffect { model.onChange = { change(it) } }
        LaunchedEffect(value) { model.sync(value) }
        TextLine(model.text, hint, onClear = model::clear, modifier = Modifier.padding(horizontal = QuietTheme.spacing.gutter))
        Spacer(Modifier.weight(1f))
        Keys(model, doneLabel, onDone)
    }
}

/** What has been typed, with a caret; Clear when there is something. */
@Composable
private fun TextLine(text: String, hint: String, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 88.dp).clip(shape).background(colors.surface).border(2.dp, colors.line, shape)
            .padding(start = 24.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).semantics { testTag = "keyboard-text" }) {
            if (text.isEmpty() && hint.isNotEmpty()) {
                QuietText(hint, QuietTheme.type.title, color = colors.inkDim, maxLines = 1)
            } else {
                QuietText("$text▏", QuietTheme.type.title, maxLines = 2)
            }
        }
        if (text.isNotEmpty()) QuietTextButton(stringResource(R.string.key_clear), onClear)
    }
}

/** Key sizes for the frame lying down (1280 wide) and standing (800 wide). */
private class KeyMetrics(val key: Dp, val height: Dp, val gap: Dp, val side: Dp, val hotCorner: Dp)

@Composable
private fun Keys(model: KeyboardModel, doneLabel: String, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val landscape = LocalLandscape.current
    // Lying down: 10 keys of 104 + 9 gaps of 12 = 1148 of 1184, rows 92 tall (the whole lower half: big targets);
    // standing: 10 × 64 + 9 × 6 = 694 of 752, rows 96 tall
    val m = if (landscape) KeyMetrics(104.dp, 92.dp, 12.dp, 48.dp, 48.dp) else KeyMetrics(64.dp, 96.dp, 6.dp, 24.dp, 72.dp)
    val centers = remember { mutableStateMapOf<String, Offset>() }
    val density = LocalDensity.current
    Box(modifier.fillMaxWidth().padding(horizontal = m.side)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(m.gap), horizontalAlignment = Alignment.CenterHorizontally) {
            val rows = if (model.layer == KeyLayer.Letters) KeyboardModel.LetterRows else KeyboardModel.SymbolRows
            val record = { label: String -> Modifier.onGloballyPositioned { centers[label] = it.boundsInParent().center + it.parentRowOffset() } }
            rows.forEachIndexed { i, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(m.gap)) {
                    if (i == 2) {
                        if (model.layer == KeyLayer.Letters) {
                            ShiftKey(model, m.key * 1.5f, m.height)
                        } else {
                            val first = row.first()
                            CharKey(first, first, m.key * 1.5f, m.height, model, record(first))
                        }
                    }
                    val keys = if (i == 2 && model.layer == KeyLayer.Symbols) row.drop(1) else row
                    keys.forEach { c ->
                        val label = if (model.shift != Shift.Off && model.layer == KeyLayer.Letters) c.uppercase() else c
                        CharKey(c, label, m.key, m.height, model, record(c))
                    }
                    if (i == 2) BackspaceKey(model, m.key * 1.5f, m.height)
                }
            }
            // The bottom row starts right of the launcher's hot corner (x ≥ 96 on the frame)
            Row(Modifier.fillMaxWidth().padding(start = m.hotCorner), horizontalArrangement = Arrangement.spacedBy(m.gap, Alignment.CenterHorizontally)) {
                Key(
                    if (model.layer == KeyLayer.Letters) "?123" else "ABC", m.key * 1.5f, m.height,
                    description = stringResource(R.string.key_switch), onTap = model::switchLayer,
                )
                CharKey(",", ",", m.key, m.height, model, record(","))
                Key(stringResource(R.string.key_space), m.key * 4.5f, m.height, onTap = model::space)
                CharKey(".", ".", m.key, m.height, model, record("."))
                Key(doneLabel, m.key * 2f, m.height, accent = true, onTap = onDone)
            }
        }
        model.accents?.let { strip ->
            // A tap outside the strip closes it
            Box(
                Modifier.matchParentSize().clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = model::dismissAccents,
                ),
            )
            val center = centers[strip.key.lowercase()] ?: centers[strip.key] ?: Offset.Zero
            val width = with(density) { (m.key * strip.options.size + m.gap * (strip.options.size - 1)).toPx() }
            val maxX = with(density) { (if (landscape) 1184.dp else 752.dp).toPx() } - width
            val x = (center.x - width / 2).coerceIn(0f, maxX.coerceAtLeast(0f))
            // Above the pressed key's row; for the top row (nothing above inside the keyboard, where taps would not
            // reach) on the row below instead, never over the key itself
            val above = center.y - with(density) { (m.height * 1.5f + m.gap).toPx() }
            val y = if (above >= 0f) above else center.y + with(density) { (m.height / 2 + m.gap).toPx() }
            Row(
                Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .clip(RoundedCornerShape(20.dp)).background(QuietTheme.colors.surface).border(2.dp, QuietTheme.colors.line, RoundedCornerShape(20.dp)),
                horizontalArrangement = Arrangement.spacedBy(m.gap),
            ) {
                strip.options.forEach { o -> Key(o, m.key, m.height, onTap = { model.pick(o) }) }
            }
        }
    }
}

/** A key's centre lies in its row; the row's own place in the column is added by the column's layout below. */
private fun androidx.compose.ui.layout.LayoutCoordinates.parentRowOffset(): Offset =
    parentLayoutCoordinates?.boundsInParent()?.topLeft ?: Offset.Zero

@Composable
private fun CharKey(key: String, label: String, width: Dp, height: Dp, model: KeyboardModel, modifier: Modifier = Modifier) {
    Key(label, width, height, modifier = modifier.semantics { testTag = "key-$key" }, onTap = { model.tap(key) }, onLongPress = { model.longPress(key) })
}

@Composable
private fun ShiftKey(model: KeyboardModel, width: Dp, height: Dp) {
    val state = when (model.shift) {
        Shift.Off -> stringResource(R.string.key_shift_off)
        Shift.Once -> stringResource(R.string.key_shift_once)
        Shift.Locked -> stringResource(R.string.key_shift_locked)
    }
    Key(
        if (model.shift == Shift.Locked) "⇪" else "⇧", width, height,
        description = stringResource(R.string.key_shift), state = state,
        lit = model.shift != Shift.Off, accent = model.shift == Shift.Locked,
        modifier = Modifier.semantics { testTag = "key-shift" },
        onTap = model::shiftTap, onLongPress = model::shiftLock,
    )
}

/** Backspace: deletes on press (not on release), and keeps deleting while held. */
@Composable
private fun BackspaceKey(model: KeyboardModel, width: Dp, height: Dp) {
    val colors = QuietTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val description = stringResource(R.string.key_delete)
    Box(
        Modifier
            .width(width)
            .height(height)
            .clip(shape)
            .background(colors.surfaceHigh)
            .pointerInput(model) {
                awaitEachGesture {
                    awaitFirstDown()
                    model.backspaceDown()
                    waitForUpOrCancellation()
                    model.backspaceUp()
                }
            }
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                testTag = "key-backspace"
                onClick { model.deleteOne(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        QuietText("⌫", QuietTheme.type.title, maxLines = 1)
    }
}

@Composable
private fun Key(
    label: String,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    description: String? = null,
    state: String? = null,
    accent: Boolean = false,
    lit: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onTap: () -> Unit,
) {
    val colors = QuietTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLongPress)
    Box(
        modifier
            .width(width)
            .height(height)
            .clip(shape)
            .background(if (accent) colors.ink else colors.surfaceHigh)
            .then(if (lit && !accent) Modifier.border(3.dp, colors.ink, shape) else Modifier)
            .pointerInput(onLongPress != null) {
                detectTapGestures(onTap = { tap() }, onLongPress = if (onLongPress != null) ({ long?.invoke() }) else null)
            }
            .clearAndSetSemantics {
                contentDescription = description ?: label
                role = Role.Button
                if (state != null) stateDescription = state
                onClick { tap(); true }
                if (onLongPress != null) onLongClick { long?.invoke(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        QuietText(label, QuietTheme.type.title, color = if (accent) colors.background else colors.ink, maxLines = 1)
    }
}
