package dev.framio.pomodoro.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.framio.pomodoro.R
import dev.framio.pomodoro.core.engine.Input
import dev.framio.pomodoro.core.model.SessionId
import dev.framio.pomodoro.core.notes.Entry
import dev.framio.pomodoro.core.notes.ReasonTag
import dev.framio.pomodoro.core.notes.TagId
import dev.framio.pomodoro.data.HistoryPort
import dev.framio.pomodoro.ui.LocalLandscape
import dev.framio.pomodoro.ui.components.Chip
import dev.framio.pomodoro.ui.components.INK_ASPECT
import dev.framio.pomodoro.ui.components.InkThumb
import dev.framio.pomodoro.ui.components.KeyboardSheet
import dev.framio.pomodoro.ui.components.QuietButton
import dev.framio.pomodoro.ui.components.QuietText
import dev.framio.pomodoro.ui.components.QuietTextButton
import dev.framio.pomodoro.ui.deep.ParkPad
import dev.framio.pomodoro.ui.theme.QuietTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration

/**
 * The end-early sheet's answers so far (DESIGN-history §5.3): reasons (multi-select), and the resume note as words
 * (a suggestion chip or typed) and/or handwriting. [pad] / [keyboard] say which input is open over the sheet.
 */
@Immutable
data class EndDraft(
    val tags: Set<TagId> = emptySet(),
    val text: String = "",
    val ink: String? = null,
    val pad: Boolean = false,
    val keyboard: Boolean = false,
)

/**
 * The end-early flow shared by Deep and Full focus: open → answer (all optional) → End session or Keep going. Ending
 * sends `EndEarly(words, ink)` to the engine and writes one [Entry.EndReason] with the chosen tags to the journal.
 */
class EndFlow(private val history: HistoryPort) {
    private val _draft = MutableStateFlow<EndDraft?>(null)
    val draft: StateFlow<EndDraft?> = _draft.asStateFlow()

    val isOpen: Boolean get() = _draft.value != null

    fun open() {
        _draft.value = EndDraft()
    }

    fun close() {
        _draft.value = null
    }

    fun toggle(tag: TagId) = edit { d -> d.copy(tags = if (tag in d.tags) d.tags - tag else d.tags + tag) }

    /** A suggestion chip: chosen, or tapped again to clear. */
    fun suggest(text: String) = edit { d -> d.copy(text = if (d.text == text) "" else text) }

    fun editText(text: String) = edit { it.copy(text = text.take(MAX_NOTE)) }

    fun openPad() = edit { it.copy(pad = true) }

    fun savePad(ink: String?) = edit { it.copy(pad = false, ink = ink ?: it.ink) }

    fun closePad() = edit { it.copy(pad = false) }

    fun openKeyboard() = edit { it.copy(keyboard = true) }

    fun closeKeyboard() = edit { it.copy(keyboard = false) }

    fun clearText() = edit { it.copy(text = "") }

    fun clearInk() = edit { it.copy(ink = null) }

    /** The engine input that ends the block, after writing the reasons for [session] at [at] focus time. */
    fun finish(session: SessionId?, at: Duration?): Input.EndEarly {
        val d = _draft.value ?: EndDraft()
        _draft.value = null
        if (session != null && d.tags.isNotEmpty()) {
            history.put(Entry.EndReason(history.newEntryId(), session, at, history.wallNow(), d.tags.toList()))
        }
        return Input.EndEarly(d.text.trim().takeIf { it.isNotEmpty() }, d.ink)
    }

    private fun edit(f: (EndDraft) -> EndDraft) = _draft.update { it?.let(f) }

    companion object {
        const val MAX_NOTE = 160
    }
}

class EndActions(
    val toggle: (TagId) -> Unit = {},
    val suggest: (String) -> Unit = {},
    val openPad: () -> Unit = {},
    val savePad: (String?) -> Unit = {},
    val closePad: () -> Unit = {},
    val openKeyboard: () -> Unit = {},
    val editText: (String) -> Unit = {},
    val closeKeyboard: () -> Unit = {},
    val clearText: () -> Unit = {},
    val clearInk: () -> Unit = {},
    val keepGoing: () -> Unit = {},
    val end: () -> Unit = {},
)

/** [EndFlow]'s callbacks plus the screen's own Keep going and End. */
fun EndFlow.actions(keepGoing: () -> Unit, end: () -> Unit): EndActions = EndActions(
    ::toggle, ::suggest, ::openPad, ::savePad, ::closePad, ::openKeyboard, ::editText, ::closeKeyboard, ::clearText, ::clearInk, keepGoing, end,
)

/**
 * "Ending at 23 min of 50" (DESIGN-history §5.3): why stop now (optional reason chips, the end-only ones first), the
 * next step when you're back (suggestion chips, handwriting or the built-in keyboard; never required), then Keep
 * going and End session as equals. Neutral copy: ending is a choice, not a failure.
 */
@Composable
fun EndSheet(
    draft: EndDraft,
    focusedMinutes: Int?,
    plannedMinutes: Int?,
    logged: Int,
    reasons: List<ReasonTag>,
    suggestions: List<String>,
    accent: Color,
    actions: EndActions,
    modifier: Modifier = Modifier,
) {
    val colors = QuietTheme.colors
    val landscape = LocalLandscape.current
    Box(modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize().padding(start = QuietTheme.spacing.gutter, end = QuietTheme.spacing.gutter, top = 28.dp, bottom = 28.dp)) {
            val title = when {
                focusedMinutes == null -> stringResource(R.string.ending_now)
                plannedMinutes == null -> pluralStringResource(R.plurals.ending_at, focusedMinutes, focusedMinutes)
                else -> stringResource(R.string.ending_at_of, focusedMinutes, plannedMinutes)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                QuietText(title, QuietTheme.type.title, maxLines = 1, modifier = Modifier.weight(1f))
                if (logged > 0) QuietText(pluralStringResource(R.plurals.distractions_logged, logged, logged), QuietTheme.type.caption, color = colors.inkDim, maxLines = 1)
            }
            Spacer(Modifier.height(if (landscape) 20.dp else 32.dp))
            QuietText(stringResource(R.string.why_stop), QuietTheme.type.body, color = colors.inkDim, maxLines = 1)
            Spacer(Modifier.height(12.dp))
            ReasonChips(reasons, draft.tags, actions.toggle, height = 64.dp, gap = 12.dp)
            Spacer(Modifier.height(if (landscape) 24.dp else 40.dp))
            QuietText(stringResource(R.string.note_title), QuietTheme.type.body, color = colors.inkDim, maxLines = 1)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                suggestions.forEach { s -> Chip(s, selected = draft.text == s, accent = accent, onClick = { actions.suggest(s) }) }
                Chip(stringResource(R.string.write_it), selected = false, accent = accent, onClick = actions.openPad)
                Chip(stringResource(R.string.type_it), selected = false, accent = accent, onClick = actions.openKeyboard)
            }
            if (draft.ink != null || (draft.text.isNotEmpty() && draft.text !in suggestions)) {
                Spacer(Modifier.height(12.dp))
                NotePreview(draft.text.takeIf { it !in suggestions }, draft.ink, actions.clearText, actions.clearInk)
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(start = 96.dp), horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally)) {
                QuietButton(stringResource(R.string.keep_going), actions.keepGoing, big = true, modifier = Modifier.width(if (landscape) 420.dp else 300.dp))
                QuietButton(stringResource(R.string.end_session), actions.end, fill = accent, big = true, modifier = Modifier.width(if (landscape) 420.dp else 300.dp))
            }
        }
        if (draft.pad) {
            ParkPad(accent, actions.savePad, actions.closePad, title = stringResource(R.string.note_title), hint = stringResource(R.string.note_pad_hint), saveLabel = stringResource(R.string.save))
        }
        if (draft.keyboard) {
            KeyboardSheet(stringResource(R.string.note_title), draft.text, actions.editText, actions.closeKeyboard, actions.closeKeyboard, maxLength = EndFlow.MAX_NOTE)
        }
    }
}

/** What was written: words and/or a scribble, each with its own ×. */
@Composable
fun NotePreview(text: String?, ink: String?, onClearText: () -> Unit, onClearInk: () -> Unit, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!text.isNullOrEmpty()) {
            Row(Modifier.clip(RoundedCornerShape(20.dp)).background(colors.surface).padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                QuietText("“$text”", QuietTheme.type.body, maxLines = 1, modifier = Modifier.width(520.dp))
                QuietTextButton("×", onClearText)
            }
        }
        if (ink != null) {
            Row(Modifier.clip(RoundedCornerShape(20.dp)).background(colors.surface).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                InkThumb(ink, colors.ink, stringResource(R.string.note_title), Modifier.size(144.dp, (144 / INK_ASPECT).dp))
                QuietTextButton("×", onClearInk)
            }
        }
    }
}
