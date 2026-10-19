package dev.framio.pomodoro.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.framio.pomodoro.R
import dev.framio.pomodoro.core.model.Display
import dev.framio.pomodoro.core.model.Face
import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Soundscape
import dev.framio.pomodoro.core.model.Theme
import dev.framio.pomodoro.core.model.TimerSize
import dev.framio.pomodoro.data.HistoryPort
import dev.framio.pomodoro.data.NoHistory
import dev.framio.pomodoro.di.EnginePort
import dev.framio.pomodoro.ui.LocalLandscape
import dev.framio.pomodoro.ui.components.Chip
import dev.framio.pomodoro.ui.components.ChoiceRow
import dev.framio.pomodoro.ui.components.QuietButton
import dev.framio.pomodoro.ui.components.QuietText
import dev.framio.pomodoro.ui.components.QuietTextButton
import dev.framio.pomodoro.ui.components.Section
import dev.framio.pomodoro.ui.components.SwitchRow
import dev.framio.pomodoro.ui.editor.ModeEditorRoute
import dev.framio.pomodoro.ui.editor.displayName
import dev.framio.pomodoro.ui.editor.faceName
import dev.framio.pomodoro.ui.editor.soundscapeName
import dev.framio.pomodoro.ui.notes.UndoBar
import dev.framio.pomodoro.ui.theme.QuietColors
import dev.framio.pomodoro.ui.theme.QuietPalettes
import dev.framio.pomodoro.ui.components.swallowTouches
import dev.framio.pomodoro.ui.theme.QuietTheme
import dev.framio.pomodoro.ui.why.InfoButton
import dev.framio.pomodoro.ui.why.Why
import dev.framio.pomodoro.ui.why.WhySheet

@Composable
fun SettingsRoute(
    port: EnginePort,
    playTest: (Float) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    history: HistoryPort = NoHistory,
    host: SettingsHost = SettingsHost(playTest = playTest),
    vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(port, host)),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(SettingsSection.Modes) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var reasons by rememberSaveable { mutableStateOf(false) }
    SettingsScreen(
        ui = ui,
        section = section,
        actions = SettingsActions(
            section = { section = it }, close = onClose, volume = vm::volume, test = vm::test, soundscapeVolume = vm::soundscapeVolume,
            preview = vm::preview, theme = vm::theme, accent = vm::accent, face = vm::face, timerSize = vm::timerSize,
            reduceMotion = vm::reduceMotion, evening = vm::evening, eveningStart = vm::eveningStart, eveningEnd = vm::eveningEnd,
            systemKeyboard = vm::systemKeyboard, target = vm::target, move = vm::move,
            edit = { editing = it.value }, copy = { id -> vm.duplicate(id)?.let { editing = it.value } }, delete = vm::delete,
            undoDelete = vm::undoDelete, skipRitual = vm::skipRitual, clearParked = vm::clearParked, export = vm::export,
            reset = vm::reset, undoReset = vm::undoReset, reasons = { reasons = true },
        ),
        modifier = modifier,
    )
    editing?.let { id ->
        ModeEditorRoute(
            port, ModeId(id),
            onOpen = { editing = it.value },
            onClose = { editing = null },
            onDelete = {
                vm.delete(it)
                editing = null
            },
        )
    }
    if (reasons) TagEditorRoute(history, onClose = { reasons = false })
}

/** Everything Settings can do, in one stable holder. */
class SettingsActions(
    val section: (SettingsSection) -> Unit = {},
    val close: () -> Unit = {},
    val volume: (Int) -> Unit = {},
    val test: () -> Unit = {},
    val soundscapeVolume: (Int) -> Unit = {},
    val preview: (Soundscape) -> Unit = {},
    val theme: (Theme) -> Unit = {},
    val accent: (Int?) -> Unit = {},
    val face: (Face?) -> Unit = {},
    val timerSize: (TimerSize) -> Unit = {},
    val reduceMotion: (Boolean) -> Unit = {},
    val evening: (Boolean) -> Unit = {},
    val eveningStart: (Int) -> Unit = {},
    val eveningEnd: (Int) -> Unit = {},
    val systemKeyboard: (Boolean) -> Unit = {},
    val target: (Int) -> Unit = {},
    val move: (ModeId, Int) -> Unit = { _, _ -> },
    val edit: (ModeId) -> Unit = {},
    val copy: (ModeId) -> Unit = {},
    val delete: (ModeId) -> Unit = {},
    val undoDelete: () -> Unit = {},
    val skipRitual: (ModeId, Boolean) -> Unit = { _, _ -> },
    val clearParked: () -> Unit = {},
    val export: () -> Unit = {},
    val reset: () -> Unit = {},
    val undoReset: () -> Unit = {},
    val reasons: () -> Unit = {},
)

/**
 * Settings, in sections (M5): Modes · Rituals · Deep · Sound · Appearance · Goals · Reasons · Data · About. Lying down,
 * a rail of sections on the left (starting right of the launcher's hot corner, x ≥ 96) and the section on the right;
 * standing, a row of section chips on top. Undo bars sit bottom-centre.
 */
@Composable
fun SettingsScreen(ui: SettingsUi, section: SettingsSection, actions: SettingsActions, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val landscape = LocalLandscape.current
    var why by rememberSaveable { mutableStateOf<Why?>(null) }
    Box(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .swallowTouches(),
    ) {
        Column(Modifier.fillMaxSize().padding(start = QuietTheme.spacing.gutter, end = QuietTheme.spacing.gutter, top = 28.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                QuietText(stringResource(R.string.settings), QuietTheme.type.title, maxLines = 1)
                Spacer(Modifier.weight(1f))
                QuietTextButton(stringResource(R.string.close), actions.close, color = colors.ink)
            }
            Spacer(Modifier.height(12.dp))
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    Rail(section, actions.section, Modifier.padding(start = 48.dp).width(260.dp))
                    Spacer(Modifier.width(32.dp))
                    Content(ui, section, actions, onWhy = { why = it }, modifier = Modifier.weight(1f))
                }
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(SettingsSection.entries, key = { it.name }) { s ->
                        Chip(stringResource(sectionName(s)), selected = s == section, accent = colors.ink, onClick = { actions.section(s) })
                    }
                }
                Spacer(Modifier.height(16.dp))
                Content(ui, section, actions, onWhy = { why = it }, modifier = Modifier.weight(1f))
            }
        }
        val undo = when {
            ui.deleted != null -> stringResource(R.string.settings_mode_deleted, ui.deleted) to actions.undoDelete
            ui.reset -> stringResource(R.string.settings_reset_done) to actions.undoReset
            else -> null
        }
        undo?.let { (message, onUndo) -> UndoBar(message, onUndo, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) }
        why?.let { WhySheet(it, onClose = { why = null }) }
    }
}

@Composable
private fun Rail(section: SettingsSection, onSection: (SettingsSection) -> Unit, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    Column(modifier) {
        SettingsSection.entries.forEach { s ->
            val selected = s == section
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(QuietTheme.Target)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected) colors.surfaceHigh else colors.background)
                    .clickable(role = Role.Tab) { onSection(s) }
                    .semantics { this.selected = selected }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                QuietText(stringResource(sectionName(s)), QuietTheme.type.label, color = if (selected) colors.ink else colors.inkDim, maxLines = 1)
            }
        }
    }
}

fun sectionName(s: SettingsSection) = when (s) {
    SettingsSection.Modes -> R.string.section_modes
    SettingsSection.Rituals -> R.string.section_rituals
    SettingsSection.Deep -> R.string.section_deep
    SettingsSection.Sound -> R.string.section_sound
    SettingsSection.Appearance -> R.string.section_appearance
    SettingsSection.Goals -> R.string.section_goals
    SettingsSection.Reasons -> R.string.section_reasons
    SettingsSection.Data -> R.string.section_data
    SettingsSection.About -> R.string.section_about
}

@Composable
private fun Content(ui: SettingsUi, section: SettingsSection, actions: SettingsActions, onWhy: (Why) -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(section) { scroll.scrollTo(0) }
    // Standing, the content starts at the left edge: the viewport stops above the launcher's hot corner
    val inset = if (LocalLandscape.current) 0.dp else QuietTheme.HotCorner
    Column(modifier.fillMaxSize().padding(bottom = inset).verticalScroll(scroll).padding(bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        when (section) {
            SettingsSection.Modes -> Modes(ui, actions)
            SettingsSection.Rituals -> Rituals(ui, actions)
            SettingsSection.Deep -> Deep(actions, onWhy)
            SettingsSection.Sound -> Sound(ui, actions, onWhy)
            SettingsSection.Appearance -> Appearance(ui, actions, onWhy)
            SettingsSection.Goals -> Goals(ui, actions)
            SettingsSection.Reasons -> Reasons(actions)
            SettingsSection.Data -> Data(ui, actions)
            SettingsSection.About -> About(ui, onWhy)
        }
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    QuietText(text, QuietTheme.type.caption, color = QuietTheme.colors.inkDim, maxLines = 6, modifier = modifier.padding(horizontal = 16.dp))
}

@Composable
private fun Modes(ui: SettingsUi, actions: SettingsActions) {
    val colors = QuietTheme.colors
    Note(stringResource(R.string.settings_modes_note))
    Section(stringResource(R.string.settings_modes_title)) {
        ui.modes.forEachIndexed { i, m ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp).defaultMinSize(minHeight = 88.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(colors.accent(ui.accentOverride ?: m.accent)))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    QuietText(m.name, QuietTheme.type.body, maxLines = 1)
                    val rhythm = if (m.focusMinutes != null && m.breakMinutes != null) {
                        stringResource(R.string.rhythm_fixed, m.focusMinutes, m.breakMinutes)
                    } else {
                        stringResource(R.string.rhythm_flow)
                    }
                    val kind = stringResource(if (m.builtIn) R.string.settings_mode_preset else R.string.settings_mode_own)
                    QuietText("$rhythm · ${stringResource(displayName(m.display))} · $kind", QuietTheme.type.caption, color = colors.inkDim, maxLines = 1)
                }
                QuietTextButton("▲", { actions.move(m.id, -1) }, color = if (i > 0) colors.ink else colors.line)
                QuietTextButton("▼", { actions.move(m.id, 1) }, color = if (i < ui.modes.lastIndex) colors.ink else colors.line)
                QuietTextButton(stringResource(if (m.builtIn) R.string.settings_view else R.string.settings_edit), { actions.edit(m.id) }, color = colors.ink)
                if (ui.canAdd) QuietTextButton(stringResource(R.string.editor_copy), { actions.copy(m.id) })
                if (!m.builtIn) QuietTextButton(stringResource(R.string.editor_delete), { actions.delete(m.id) })
            }
        }
    }
    if (!ui.canAdd) Note(stringResource(R.string.settings_modes_full))
}

@Composable
private fun Rituals(ui: SettingsUi, actions: SettingsActions) {
    Note(stringResource(R.string.settings_rituals_note))
    Section(stringResource(R.string.settings_ritual_screens)) {
        ui.rituals.forEach { r ->
            SwitchRow(
                stringResource(R.string.settings_skip_ritual, r.name), r.skip, { actions.skipRitual(r.id, it) },
                enabled = r.canSkip || r.skip, note = if (r.canSkip || r.skip) null else stringResource(R.string.editor_skip_after_run),
            )
        }
        if (ui.rituals.isEmpty()) Note(stringResource(R.string.settings_no_rituals))
    }
    Section(stringResource(R.string.parked_thoughts)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietText(pluralStringResource(R.plurals.settings_parked_kept, ui.parkedKept, ui.parkedKept), QuietTheme.type.body, maxLines = 2, modifier = Modifier.weight(1f))
            if (ui.parkedKept > 0) QuietTextButton(stringResource(R.string.clear), actions.clearParked, color = QuietTheme.colors.ink)
        }
    }
}

@Composable
private fun Deep(actions: SettingsActions, onWhy: (Why) -> Unit) {
    Section(stringResource(R.string.section_deep)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietText(stringResource(R.string.settings_deep_text), QuietTheme.type.body, maxLines = 8, modifier = Modifier.weight(1f))
            InfoButton({ onWhy(Why.Display) })
        }
        Spacer(Modifier.height(8.dp))
        Note(stringResource(R.string.settings_deep_controls))
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            QuietButton(stringResource(R.string.settings_deep_view), { actions.edit(Presets.DEEP_ID) })
            QuietButton(stringResource(R.string.settings_deep_copy), { actions.copy(Presets.DEEP_ID) }, fill = QuietTheme.colors.ink)
        }
    }
}

@Composable
private fun Sound(ui: SettingsUi, actions: SettingsActions, onWhy: (Why) -> Unit) {
    Section(stringResource(R.string.settings_cues)) {
        StepperRow(stringResource(R.string.cue_volume), stringResource(R.string.percent, ui.volumePercent), { actions.volume(-SettingsViewModel.STEP) }, { actions.volume(SettingsViewModel.STEP) }) {
            QuietButton(stringResource(R.string.test_cue), actions.test)
        }
        Note(stringResource(R.string.cue_volume_note))
    }
    Section(stringResource(R.string.editor_soundscape)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietText(stringResource(R.string.soundscape_honest), QuietTheme.type.body, maxLines = 3, modifier = Modifier.weight(1f))
            InfoButton({ onWhy(Why.Sound) })
        }
        StepperRow(
            stringResource(R.string.settings_soundscape_volume), stringResource(R.string.percent, ui.soundscapePercent),
            { actions.soundscapeVolume(-SettingsViewModel.STEP) }, { actions.soundscapeVolume(SettingsViewModel.STEP) },
        )
        PreviewRow(actions.preview)
        Note(stringResource(R.string.settings_soundscape_note))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewRow(onPreview: (Soundscape) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        QuietText(stringResource(R.string.settings_listen), QuietTheme.type.body, maxLines = 1)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Soundscape.entries.forEach { s -> QuietButton(stringResource(soundscapeName(s)), { onPreview(s) }) }
        }
    }
}

@Composable
private fun Appearance(ui: SettingsUi, actions: SettingsActions, onWhy: (Why) -> Unit) {
    val colors = QuietTheme.colors
    Section(stringResource(R.string.theme)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Theme.entries.forEach { t -> ThemeTile(t, t == ui.theme, { actions.theme(t) }, Modifier.weight(1f)) }
        }
        Note(stringResource(R.string.settings_theme_note))
    }
    Section(stringResource(R.string.settings_look)) {
        ChoiceRow(
            stringResource(R.string.settings_accent), listOf<Int?>(null) + (0 until colors.accentCount), ui.accentOverride,
            { if (it == null) stringResource(R.string.settings_each_own) else stringResource(AccentLabels[it]) }, actions.accent,
            ui.accentOverride?.let { colors.accent(it) } ?: colors.ink,
        )
        ChoiceRow(
            stringResource(R.string.settings_face), listOf<Face?>(null) + Face.entries, ui.face,
            { if (it == null) stringResource(R.string.settings_each_own) else stringResource(faceName(it)) }, actions.face, colors.ink,
        )
        ChoiceRow(
            stringResource(R.string.settings_timer_size), TimerSize.entries.toList(), ui.timerSize,
            {
                stringResource(
                    when (it) {
                        TimerSize.Small -> R.string.size_small
                        TimerSize.Medium -> R.string.size_medium
                        TimerSize.Large -> R.string.size_large
                    },
                )
            },
            actions.timerSize, colors.ink,
        )
        SwitchRow(stringResource(R.string.settings_reduce_motion), ui.reduceMotion, actions.reduceMotion, note = stringResource(R.string.settings_reduce_motion_note))
    }
    Section(stringResource(R.string.settings_evening)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SwitchRow(stringResource(R.string.settings_evening_on), ui.evening, actions.evening, note = stringResource(R.string.settings_evening_note), modifier = Modifier.weight(1f))
            InfoButton({ onWhy(Why.Evening) })
        }
        if (ui.evening) {
            StepperRow(stringResource(R.string.settings_evening_from), ui.eveningStart.toString(), { actions.eveningStart(-SettingsViewModel.TIME_STEP) }, { actions.eveningStart(SettingsViewModel.TIME_STEP) })
            StepperRow(stringResource(R.string.settings_evening_until), ui.eveningEnd.toString(), { actions.eveningEnd(-SettingsViewModel.TIME_STEP) }, { actions.eveningEnd(SettingsViewModel.TIME_STEP) })
        }
    }
    Section(stringResource(R.string.settings_keyboard)) {
        SwitchRow(stringResource(R.string.settings_system_keyboard), ui.systemKeyboard, actions.systemKeyboard, note = stringResource(R.string.settings_system_keyboard_note))
    }
}

private val AccentLabels = listOf(
    R.string.accent_coral, R.string.accent_periwinkle, R.string.accent_teal, R.string.accent_gold,
    R.string.accent_rose, R.string.accent_sky, R.string.accent_green, R.string.accent_lilac,
)

/** A theme as a small picture of itself (its own palette, whatever the current one is). */
@Composable
private fun ThemeTile(theme: Theme, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val current = QuietTheme.colors
    val c: QuietColors = QuietPalettes.of(theme)
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .clip(shape)
            .border(if (selected) 4.dp else 2.dp, if (selected) current.ink else current.line, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected },
    ) {
        Box(
            Modifier.fillMaxWidth().height(110.dp).background(c.background).drawWithCache {
                val stroke = 6.dp.toPx()
                val r = size.height * 0.3f
                val center = Offset(size.width * 0.3f, size.height / 2f)
                val topLeft = Offset(center.x - r, center.y - r)
                onDrawBehind {
                    drawCircle(c.inkFaint, r, center, style = Stroke(stroke))
                    drawArc(c.accent(0), -90f, 250f, false, topLeft, Size(r * 2, r * 2), style = Stroke(stroke, cap = StrokeCap.Round))
                    drawRoundRect(c.accent(1), Offset(size.width * 0.58f, size.height * 0.38f), Size(size.width * 0.3f, size.height * 0.24f), androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()))
                }
            },
        )
        QuietText(
            stringResource(themeName(theme)), QuietTheme.type.label, maxLines = 1, align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().background(c.surface).padding(vertical = 12.dp), color = c.ink,
        )
    }
}

fun themeName(t: Theme): Int = when (t) {
    Theme.Ink -> R.string.theme_ink
    Theme.Paper -> R.string.theme_paper
    Theme.Forest -> R.string.theme_forest
    Theme.Tomato -> R.string.theme_tomato
}

@Composable
private fun Goals(ui: SettingsUi, actions: SettingsActions) {
    Section(stringResource(R.string.daily_target)) {
        StepperRow(stringResource(R.string.settings_target), pluralStringResource(R.plurals.sessions_count, ui.target, ui.target), { actions.target(-1) }, { actions.target(1) })
        Note(stringResource(R.string.settings_target_note))
    }
}

@Composable
private fun Reasons(actions: SettingsActions) {
    Section(stringResource(R.string.reasons)) {
        Box(Modifier.padding(16.dp)) { QuietButton(stringResource(R.string.edit_reasons), actions.reasons) }
        Note(stringResource(R.string.reasons_note))
    }
}

@Composable
private fun Data(ui: SettingsUi, actions: SettingsActions) {
    Section(stringResource(R.string.settings_export)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietButton(stringResource(R.string.settings_export_button), actions.export, enabled = ui.export != ExportState.Working)
            Spacer(Modifier.width(20.dp))
            val line = when (val e = ui.export) {
                ExportState.Idle -> null
                ExportState.Working -> stringResource(R.string.settings_export_working)
                is ExportState.Done -> stringResource(R.string.settings_export_done, e.where)
                ExportState.Failed -> stringResource(R.string.settings_export_failed)
            }
            if (line != null) QuietText(line, QuietTheme.type.caption, maxLines = 3, modifier = Modifier.weight(1f))
        }
        Note(stringResource(R.string.settings_export_note))
    }
    Section(stringResource(R.string.settings_reset)) {
        Box(Modifier.padding(16.dp)) { QuietButton(stringResource(R.string.settings_reset_button), actions.reset) }
        Note(stringResource(R.string.settings_reset_note))
    }
}

@Composable
private fun About(ui: SettingsUi, onWhy: (Why) -> Unit) {
    Section(stringResource(R.string.section_about)) {
        QuietText(stringResource(R.string.settings_about_version, ui.version), QuietTheme.type.body, maxLines = 1, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        QuietText(stringResource(R.string.settings_about_text), QuietTheme.type.body, maxLines = 12, modifier = Modifier.padding(horizontal = 16.dp))
        Box(Modifier.padding(16.dp)) { QuietButton(stringResource(R.string.settings_about_why), { onWhy(Why.All) }) }
    }
    Section(stringResource(R.string.settings_claims)) {
        ClaimLines.forEach { QuietText("·  ${stringResource(it)}", QuietTheme.type.body, maxLines = 2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
    }
}

private val ClaimLines = listOf(
    R.string.claim_proven, R.string.claim_5217, R.string.claim_23, R.string.claim_90, R.string.claim_willpower, R.string.claim_phone, R.string.claim_noise,
)

/** A label, a value between − and +, and an optional trailing action. */
@Composable
private fun StepperRow(label: String, value: String, onLess: () -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        QuietText(label, QuietTheme.type.body, maxLines = 2, modifier = Modifier.weight(1f))
        QuietButton(stringResource(R.string.less), onLess, Modifier.width(88.dp))
        QuietText(value, QuietTheme.type.title, maxLines = 1, align = TextAlign.Center, modifier = Modifier.width(170.dp))
        QuietButton(stringResource(R.string.more), onMore, Modifier.width(88.dp))
        Spacer(Modifier.width(16.dp))
        trailing()
    }
}
