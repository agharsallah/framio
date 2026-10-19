package dev.framio.pomodoro.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.framio.pomodoro.R
import dev.framio.pomodoro.core.notes.ReasonTag
import dev.framio.pomodoro.core.notes.TagIcon
import dev.framio.pomodoro.core.notes.TagId
import dev.framio.pomodoro.core.notes.TagScope
import dev.framio.pomodoro.core.notes.Taxonomy
import dev.framio.pomodoro.data.HistoryPort
import dev.framio.pomodoro.data.StatsView
import dev.framio.pomodoro.ui.components.KeyboardSheet
import dev.framio.pomodoro.ui.components.QuietButton
import dev.framio.pomodoro.ui.components.QuietText
import dev.framio.pomodoro.ui.components.QuietTextButton
import dev.framio.pomodoro.ui.notes.TagGlyph
import dev.framio.pomodoro.ui.notes.UndoBar
import dev.framio.pomodoro.ui.stateForUi
import dev.framio.pomodoro.ui.theme.ChartColors
import dev.framio.pomodoro.ui.theme.QuietTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/** The keyboard over the editor: renaming a tag, or adding one. */
sealed interface TagTyping {
    val text: String

    data class Rename(val id: TagId, override val text: String) : TagTyping

    data class Add(override val text: String) : TagTyping
}

@Immutable
data class TagEditorUi(
    val tags: List<ReasonTag> = emptyList(),
    /** All-time uses per tag (distractions and end reasons), from Insights' day buckets. */
    val used: Map<TagId, Int> = emptyMap(),
    val typing: TagTyping? = null,
    @param:StringRes val undo: Int? = null,
)

/**
 * Settings → Reasons (DESIGN-history §3.1): rename, reorder (▲ ▼: 72 px targets instead of a fiddly drag), hide or
 * show, colour and symbol, add. Never delete: history keeps pointing at tags. Bulk changes (sort by use, restore
 * defaults) have Undo. Names are typed on the built-in keyboard.
 */
class TagEditorViewModel(private val history: HistoryPort) : ViewModel() {
    private data class Local(val typing: TagTyping? = null, @param:StringRes val undo: Int? = null)

    private val local = MutableStateFlow(Local())
    private var before: Taxonomy? = null
    private var undoJob: Job? = null

    val ui = combine(history.tags, history.stats, local) { t, s, l -> TagEditorUi(t.tags, usage(s), l.typing, l.undo) }
        .stateForUi(viewModelScope, TagEditorUi(history.tags.value.tags))

    init {
        history.needStats()
    }

    override fun onCleared() = history.release()

    fun up(id: TagId) = change { t -> t.move(id, t.tags.indexOfFirst { it.id == id } - 1) }

    fun down(id: TagId) = change { t -> t.move(id, t.tags.indexOfFirst { it.id == id } + 1) }

    fun setHidden(id: TagId, hidden: Boolean) = change { it.setHidden(id, hidden) }

    /** Next colour slot: 0..7, then neutral gray, then 0 again. */
    fun nextHue(id: TagId) = change { t ->
        val h = t.tag(id)?.hue ?: return@change t
        t.restyle(id, hue = if (h >= 7) ReasonTag.NEUTRAL else h + 1)
    }

    fun nextIcon(id: TagId) = change { t ->
        val i = t.tag(id)?.icon ?: return@change t
        t.restyle(id, icon = TagIcon.entries[(i.ordinal + 1) % TagIcon.entries.size])
    }

    fun startRename(id: TagId) = local.update { it.copy(typing = TagTyping.Rename(id, history.tags.value.label(id))) }

    fun startAdd() = local.update { it.copy(typing = TagTyping.Add("")) }

    fun type(text: String) = local.update { l ->
        l.copy(
            typing = when (val t = l.typing) {
                is TagTyping.Rename -> t.copy(text = text.take(Taxonomy.MAX_LABEL))
                is TagTyping.Add -> t.copy(text = text.take(Taxonomy.MAX_LABEL))
                null -> null
            },
        )
    }

    /** Done on the keyboard: renames or adds (a blank or duplicate name changes nothing). */
    fun finishTyping() {
        when (val t = local.value.typing) {
            is TagTyping.Rename -> change { it.rename(t.id, t.text) }
            is TagTyping.Add -> change { tax -> tax.add(t.text, TagIcon.Dots, nextFreeHue(tax))?.first ?: tax }
            null -> Unit
        }
        cancelTyping()
    }

    fun cancelTyping() = local.update { it.copy(typing = null) }

    /** Your order by all-time use (the app never sorts on its own). Undo for a few seconds. */
    fun sortByUse() {
        val used = ui.value.used
        bulk(R.string.reasons_sorted) { t -> t.copy(tags = t.tags.sortedByDescending { used[it.id] ?: 0 }) }
    }

    fun restoreDefaults() = bulk(R.string.reasons_restored) { t ->
        // Built-ins come back as shipped; your own tags stay, hidden (history still names them)
        Taxonomy(tags = Taxonomy.Defaults + t.tags.filter { !it.builtIn }.map { it.copy(hidden = true) })
    }

    fun undo() {
        undoJob?.cancel()
        before?.let { b -> history.updateTags { b } }
        before = null
        local.update { it.copy(undo = null) }
    }

    private fun bulk(@StringRes message: Int, f: (Taxonomy) -> Taxonomy) {
        before = history.tags.value
        change(f)
        undoJob?.cancel()
        local.update { it.copy(undo = message) }
        undoJob = viewModelScope.launch {
            delay(6.seconds)
            before = null
            local.update { it.copy(undo = null) }
        }
    }

    private fun change(f: (Taxonomy) -> Taxonomy) = history.updateTags(f)

    private fun nextFreeHue(t: Taxonomy): Int = (0..7).firstOrNull { h -> t.tags.none { !it.hidden && it.hue == h } } ?: ReasonTag.NEUTRAL

    companion object {
        fun usage(s: StatsView?): Map<TagId, Int> {
            val m = HashMap<TagId, Int>()
            s?.buckets?.forEach { b -> (b.tags + b.endTags).forEach { c -> m[TagId(c.tag)] = (m[TagId(c.tag)] ?: 0) + c.count } }
            return m
        }

        fun factory(history: HistoryPort): ViewModelProvider.Factory = viewModelFactory { initializer { TagEditorViewModel(history) } }
    }
}

@Composable
fun TagEditorRoute(history: HistoryPort, onClose: () -> Unit, modifier: Modifier = Modifier, vm: TagEditorViewModel = viewModel(factory = TagEditorViewModel.factory(history))) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    TagEditorScreen(
        ui,
        TagEditorActions(
            close = onClose, up = vm::up, down = vm::down, setHidden = vm::setHidden, nextHue = vm::nextHue, nextIcon = vm::nextIcon,
            rename = vm::startRename, add = vm::startAdd, type = vm::type, finishTyping = vm::finishTyping, cancelTyping = vm::cancelTyping,
            sortByUse = vm::sortByUse, restoreDefaults = vm::restoreDefaults, undo = vm::undo,
        ),
        modifier,
    )
}

class TagEditorActions(
    val close: () -> Unit = {},
    val up: (TagId) -> Unit = {},
    val down: (TagId) -> Unit = {},
    val setHidden: (TagId, Boolean) -> Unit = { _, _ -> },
    val nextHue: (TagId) -> Unit = {},
    val nextIcon: (TagId) -> Unit = {},
    val rename: (TagId) -> Unit = {},
    val add: () -> Unit = {},
    val type: (String) -> Unit = {},
    val finishTyping: () -> Unit = {},
    val cancelTyping: () -> Unit = {},
    val sortByUse: () -> Unit = {},
    val restoreDefaults: () -> Unit = {},
    val undo: () -> Unit = {},
)

@Composable
fun TagEditorScreen(ui: TagEditorUi, actions: TagEditorActions, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val shown = ui.tags.filter { !it.hidden && it.scope == TagScope.Any }
    val endOnly = ui.tags.filter { !it.hidden && it.scope == TagScope.EndOnly }
    val hidden = ui.tags.filter { it.hidden }
    Box(modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize().padding(start = QuietTheme.spacing.gutter, end = QuietTheme.spacing.gutter, top = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                QuietText(stringResource(R.string.reasons), QuietTheme.type.title, maxLines = 1, modifier = Modifier.weight(1f))
                QuietButton(stringResource(R.string.add_reason), actions.add)
                Spacer(Modifier.width(12.dp))
                QuietTextButton(stringResource(R.string.close), actions.close, color = colors.ink)
            }
            QuietText(stringResource(R.string.reasons_note), QuietTheme.type.caption, color = colors.inkDim, maxLines = 2)
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(shown, key = { it.id.value }) { TagRow(it, ui.used[it.id] ?: 0, actions) }
                if (endOnly.isNotEmpty()) {
                    item(key = "end") { Section(stringResource(R.string.section_end_only)) }
                    items(endOnly, key = { it.id.value }) { TagRow(it, ui.used[it.id] ?: 0, actions) }
                }
                if (hidden.isNotEmpty()) {
                    item(key = "hidden") { Section(stringResource(R.string.section_hidden)) }
                    items(hidden, key = { it.id.value }) { TagRow(it, ui.used[it.id] ?: 0, actions) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 96.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                QuietTextButton(stringResource(R.string.sort_by_use), actions.sortByUse)
                QuietTextButton(stringResource(R.string.restore_defaults), actions.restoreDefaults)
            }
        }
        ui.undo?.let { UndoBar(stringResource(it), actions.undo, Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp)) }
        ui.typing?.let { t ->
            val title = when (t) {
                is TagTyping.Rename -> stringResource(R.string.rename_reason, ui.tags.firstOrNull { it.id == t.id }?.label.orEmpty())
                is TagTyping.Add -> stringResource(R.string.new_reason)
            }
            KeyboardSheet(title, t.text, actions.type, actions.finishTyping, actions.cancelTyping, maxLength = Taxonomy.MAX_LABEL)
        }
    }
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier) {
    QuietText(title, QuietTheme.type.caption, color = QuietTheme.colors.inkDim, maxLines = 1, modifier = modifier.padding(top = 20.dp, bottom = 6.dp))
}

/** One tag: ▲ ▼, colour and symbol (tap to change), label and use count, Rename, Hide/Show. 72 px tall. */
@Composable
private fun TagRow(tag: ReasonTag, used: Int, actions: TagEditorActions, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val chart = ChartColors.of(colors)
    val colourLabel = stringResource(R.string.change_colour, tag.label)
    val glyphLabel = stringResource(R.string.change_glyph, tag.label)
    val upLabel = stringResource(R.string.move_up)
    val downLabel = stringResource(R.string.move_down)
    Row(modifier.fillMaxWidth().heightIn(min = QuietTheme.Target), verticalAlignment = Alignment.CenterVertically) {
        QuietTextButton("▲", { actions.up(tag.id) }, Modifier.semantics { contentDescription = upLabel }.width(72.dp))
        QuietTextButton("▼", { actions.down(tag.id) }, Modifier.semantics { contentDescription = downLabel }.width(72.dp))
        Box(
            Modifier.size(QuietTheme.Target).clickable(role = Role.Button, onClick = { actions.nextHue(tag.id) }).semantics { contentDescription = colourLabel },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(chart.tag(tag.hue)))
        }
        Box(
            Modifier.size(QuietTheme.Target).clickable(role = Role.Button, onClick = { actions.nextIcon(tag.id) }).semantics { contentDescription = glyphLabel },
            contentAlignment = Alignment.Center,
        ) {
            TagGlyph(tag.icon, colors.ink, colors.background, Modifier.size(30.dp))
        }
        QuietText(tag.label, QuietTheme.type.body, maxLines = 1, color = if (tag.hidden) colors.inkDim else colors.ink, modifier = Modifier.weight(1f))
        QuietText(stringResource(R.string.used_times, used), QuietTheme.type.caption, color = colors.inkDim, maxLines = 1, modifier = Modifier.width(130.dp))
        QuietTextButton(stringResource(R.string.rename), { actions.rename(tag.id) })
        QuietTextButton(stringResource(if (tag.hidden) R.string.show else R.string.hide), { actions.setHidden(tag.id, !tag.hidden) })
    }
}
