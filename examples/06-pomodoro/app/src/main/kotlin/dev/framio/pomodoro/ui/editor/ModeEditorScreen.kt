package dev.framio.pomodoro.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.framio.pomodoro.R
import dev.framio.pomodoro.core.model.CopyTone
import dev.framio.pomodoro.core.model.CueStyle
import dev.framio.pomodoro.core.model.Display
import dev.framio.pomodoro.core.model.Face
import dev.framio.pomodoro.core.model.IntentionRule
import dev.framio.pomodoro.core.model.Mode
import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.ModeRules
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Rhythm
import dev.framio.pomodoro.core.model.Soundscape
import dev.framio.pomodoro.core.model.TouchPolicy
import dev.framio.pomodoro.di.EnginePort
import dev.framio.pomodoro.ui.LocalLandscape
import dev.framio.pomodoro.ui.components.AccentPicker
import dev.framio.pomodoro.ui.components.Chip
import dev.framio.pomodoro.ui.components.ChoiceRow
import dev.framio.pomodoro.ui.components.KeyboardSheet
import dev.framio.pomodoro.ui.components.QuietButton
import dev.framio.pomodoro.ui.components.QuietText
import dev.framio.pomodoro.ui.components.QuietTextButton
import dev.framio.pomodoro.ui.components.Section
import dev.framio.pomodoro.ui.components.SwitchRow
import dev.framio.pomodoro.ui.components.swallowTouches
import dev.framio.pomodoro.ui.theme.QuietTheme
import dev.framio.pomodoro.ui.why.InfoButton
import dev.framio.pomodoro.ui.why.Why
import dev.framio.pomodoro.ui.why.WhySheet
import kotlin.time.Duration

/**
 * The mode editor for [id] over Settings. [onOpen] switches to another mode (after Duplicate), [onClose] goes back,
 * [onDelete] hands the mode to the Modes list (which deletes it with an Undo bar).
 */
@Composable
fun ModeEditorRoute(
    port: EnginePort,
    id: ModeId,
    onOpen: (ModeId) -> Unit,
    onClose: () -> Unit,
    onDelete: (ModeId) -> Unit,
    modifier: Modifier = Modifier,
    vm: ModeEditorViewModel = viewModel(key = "editor-${id.value}", factory = ModeEditorViewModel.factory(port, id)),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.gone) { if (ui.gone) onClose() }
    ModeEditorScreen(
        ui = ui,
        actions = EditorActions(
            close = onClose, delete = { onDelete(id) }, duplicate = { vm.duplicate()?.let(onOpen) }, tab = vm::tab,
            startName = vm::startName, accent = vm::accent, fixed = vm::fixed, focus = vm::focus, shortBreak = vm::shortBreak,
            longBreak = vm::longBreak, longEvery = vm::longEvery, autoBreak = vm::autoBreak, autoFocus = vm::autoFocus,
            pauseCap = vm::pauseCap, nudge = vm::nudge, nudgeAfter = vm::nudgeAfter, intention = vm::intention, ifThen = vm::ifThen,
            resumeNote = vm::resumeNote, builtInItem = vm::builtInItem, startItem = vm::startItem, removeItem = vm::removeItem,
            skipRitual = vm::skipRitual, display = vm::display, face = vm::face, seconds = vm::seconds, holdToOpen = vm::holdToOpen,
            knock = vm::knock, cues = vm::cues, soundscape = vm::soundscape, checkIn = vm::checkIn, sunrise = vm::sunrise,
            warm = vm::warm, type = vm::type, finishTyping = vm::finishTyping, cancelTyping = vm::cancelTyping,
            dismissProblem = vm::dismissProblem,
        ),
        modifier = modifier,
    )
}

/** Everything the editor can do, in one stable holder. */
class EditorActions(
    val close: () -> Unit = {},
    val delete: () -> Unit = {},
    val duplicate: () -> Unit = {},
    val tab: (EditorTab) -> Unit = {},
    val startName: () -> Unit = {},
    val accent: (Int) -> Unit = {},
    val fixed: (Boolean) -> Unit = {},
    val focus: (Int) -> Unit = {},
    val shortBreak: (Int) -> Unit = {},
    val longBreak: (Int) -> Unit = {},
    val longEvery: (Int) -> Unit = {},
    val autoBreak: (Boolean) -> Unit = {},
    val autoFocus: (Boolean) -> Unit = {},
    val pauseCap: (Int?) -> Unit = {},
    val nudge: (Boolean) -> Unit = {},
    val nudgeAfter: (Int) -> Unit = {},
    val intention: (IntentionRule) -> Unit = {},
    val ifThen: (Boolean) -> Unit = {},
    val resumeNote: (Boolean) -> Unit = {},
    val builtInItem: (String, Boolean) -> Unit = { _, _ -> },
    val startItem: () -> Unit = {},
    val removeItem: (String) -> Unit = {},
    val skipRitual: (Boolean) -> Unit = {},
    val display: (Display) -> Unit = {},
    val face: (Face) -> Unit = {},
    val seconds: (Boolean) -> Unit = {},
    val holdToOpen: (Boolean) -> Unit = {},
    val knock: (Boolean) -> Unit = {},
    val cues: (CueStyle) -> Unit = {},
    val soundscape: (Soundscape?) -> Unit = {},
    val checkIn: (Int?) -> Unit = {},
    val sunrise: (Boolean) -> Unit = {},
    val warm: (Boolean) -> Unit = {},
    val type: (String) -> Unit = {},
    val finishTyping: () -> Unit = {},
    val cancelTyping: () -> Unit = {},
    val dismissProblem: () -> Unit = {},
)

/**
 * The editor (PLAN §5.1): a live preview of the face in the theme on the left (it changes as you edit), the name and
 * accent under it; on the right three pages, *Rhythm*, *Before* (ritual) and *During* (screen and sound). Durations are
 * dials you drag around, never typed. A preset shows what it does, read-only, with "Duplicate & edit".
 * Standing, the preview sits on top and the pages below. The bottom-left 88×88 px stay empty.
 */
@Composable
fun ModeEditorScreen(ui: EditorUi, actions: EditorActions, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val mode = ui.mode
    val landscape = LocalLandscape.current
    var why by rememberSaveable { mutableStateOf<Why?>(null) }
    Box(
        modifier
            .fillMaxSize()
            .background(colors.background)
            .swallowTouches(),
    ) {
        if (mode == null) return@Box
        val accent = colors.accent(ui.accent)
        Column(Modifier.fillMaxSize().padding(start = QuietTheme.spacing.gutter, end = QuietTheme.spacing.gutter, top = 28.dp)) {
            Header(ui, mode, actions)
            Spacer(Modifier.height(16.dp))
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.width(360.dp)) {
                        ModePreview(mode, accent, ui.faceOverride ?: mode.environment.face, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(20.dp))
                        Identity(ui, mode, actions)
                    }
                    Spacer(Modifier.width(32.dp))
                    Pages(ui, mode, accent, actions, onWhy = { why = it }, modifier = Modifier.weight(1f))
                }
            } else {
                Row(verticalAlignment = Alignment.Top) {
                    ModePreview(mode, accent, ui.faceOverride ?: mode.environment.face, Modifier.width(360.dp))
                    Spacer(Modifier.width(24.dp))
                    Identity(ui, mode, actions, Modifier.weight(1f))
                }
                Spacer(Modifier.height(20.dp))
                Pages(ui, mode, accent, actions, onWhy = { why = it }, modifier = Modifier.weight(1f))
            }
        }
        when (ui.typing) {
            TextTarget.Name -> KeyboardSheet(
                stringResource(R.string.editor_name_title), ui.text, actions.type, actions.finishTyping, actions.cancelTyping,
                maxLength = ModeRules.MAX_NAME,
            )
            TextTarget.Item -> KeyboardSheet(
                stringResource(R.string.editor_item_title), ui.text, actions.type, actions.finishTyping, actions.cancelTyping,
                maxLength = ModeRules.MAX_ITEM, doneLabel = stringResource(R.string.add), hint = stringResource(R.string.editor_item_hint),
            )
            null -> Unit
        }
        why?.let { WhySheet(it, onClose = { why = null }) }
    }
}

@Composable
private fun Header(ui: EditorUi, mode: Mode, actions: EditorActions, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        QuietTextButton(stringResource(R.string.editor_back), actions.close, color = QuietTheme.colors.ink)
        Spacer(Modifier.width(8.dp))
        QuietText(
            if (ui.readOnly) stringResource(R.string.editor_preset_title, mode.name) else mode.name,
            QuietTheme.type.title, maxLines = 1, modifier = Modifier.weight(1f),
        )
        if (ui.problem != null) {
            QuietTextButton(
                if (ui.problem == ModeEditorViewModel.FULL) stringResource(R.string.editor_full, ModeRulesMax) else ui.problem,
                actions.dismissProblem, color = QuietTheme.colors.ink,
            )
        }
        if (ui.readOnly) {
            QuietButton(stringResource(R.string.editor_duplicate), actions.duplicate, fill = QuietTheme.colors.ink, enabled = ui.canDuplicate)
        } else {
            if (ui.canDuplicate) QuietTextButton(stringResource(R.string.editor_copy), actions.duplicate)
            QuietTextButton(stringResource(R.string.editor_delete), actions.delete)
        }
    }
}

private const val ModeRulesMax = dev.framio.pomodoro.core.model.Settings.MAX_MODES

/** Name (tap to type) and accent, under the preview. */
@Composable
private fun Identity(ui: EditorUi, mode: Mode, actions: EditorActions, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    Column(modifier) {
        if (!ui.readOnly) {
            QuietButton(stringResource(R.string.editor_rename), actions.startName)
            Spacer(Modifier.height(16.dp))
            QuietText(stringResource(R.string.editor_accent), QuietTheme.type.label, color = colors.inkDim, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            val names = AccentNames.map { stringResource(it) }
            AccentPicker(mode.accent, actions.accent, describe = { names[it] })
            if (ui.accentOverride != null) {
                QuietText(stringResource(R.string.editor_accent_overridden), QuietTheme.type.caption, color = colors.inkDim, maxLines = 2)
            }
        } else {
            QuietText(stringResource(R.string.editor_preset_note), QuietTheme.type.body, color = colors.inkDim, maxLines = 4)
        }
    }
}

private val AccentNames = listOf(
    R.string.accent_coral, R.string.accent_periwinkle, R.string.accent_teal, R.string.accent_gold,
    R.string.accent_rose, R.string.accent_sky, R.string.accent_green, R.string.accent_lilac,
)

@Composable
private fun Pages(ui: EditorUi, mode: Mode, accent: Color, actions: EditorActions, onWhy: (Why) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (ui.readOnly) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { Summary(mode) }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EditorTab.entries.forEach { t ->
                Chip(stringResource(tabName(t)), selected = ui.tab == t, accent = QuietTheme.colors.ink, onClick = { actions.tab(t) })
            }
        }
        Spacer(Modifier.height(16.dp))
        // One scroll per page: switching pages starts at the top
        val scroll = rememberScrollState()
        LaunchedEffect(ui.tab) { scroll.scrollTo(0) }
        // Standing, the pages start at the left edge: the viewport stops above the launcher's hot corner, so nothing
        // tappable ever scrolls into the bottom-left 88 px
        val inset = if (LocalLandscape.current) 0.dp else QuietTheme.HotCorner
        Column(
            Modifier.fillMaxWidth().weight(1f).padding(bottom = inset).verticalScroll(scroll).padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            when (ui.tab) {
                EditorTab.Rhythm -> RhythmPage(mode, accent, actions, onWhy)
                EditorTab.Ritual -> RitualPage(ui, mode, accent, actions)
                EditorTab.Screen -> ScreenPage(ui, mode, accent, actions, onWhy)
            }
        }
    }
}

private fun tabName(t: EditorTab) = when (t) {
    EditorTab.Rhythm -> R.string.editor_tab_rhythm
    EditorTab.Ritual -> R.string.editor_tab_ritual
    EditorTab.Screen -> R.string.editor_tab_screen
}

@Composable
private fun RhythmPage(mode: Mode, accent: Color, actions: EditorActions, onWhy: (Why) -> Unit) {
    val rhythm = mode.rhythm
    Section(stringResource(R.string.editor_rhythm_kind)) {
        ChoiceRow(
            stringResource(R.string.editor_rhythm_kind_label), listOf(true, false), rhythm is Rhythm.Fixed,
            { stringResource(if (it) R.string.editor_fixed else R.string.editor_flow) }, actions.fixed, accent,
        )
    }
    when (rhythm) {
        is Rhythm.Fixed -> {
            Section(stringResource(R.string.editor_lengths)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    DurationDial(stringResource(R.string.editor_focus), rhythm.focus.minutesInt(), ModeRules.FOCUS.minutesRange(), actions.focus, accent, size = 200.dp)
                    DurationDial(stringResource(R.string.editor_short), rhythm.short.minutesInt(), ModeRules.SHORT.minutesRange(), actions.shortBreak, accent, size = 200.dp)
                    DurationDial(
                        stringResource(R.string.editor_long), rhythm.long.minutesInt(), ModeRules.LONG.minutesRange(), actions.longBreak, accent,
                        size = 200.dp, enabled = rhythm.longEvery > 0,
                    )
                }
                ChoiceRow(
                    stringResource(R.string.editor_long_every), listOf(0, 2, 3, 4, 5, 6, 8), rhythm.longEvery.closestOf(listOf(0, 2, 3, 4, 5, 6, 8)),
                    { if (it == 0) stringResource(R.string.editor_never) else stringResource(R.string.editor_every_n, it) }, actions.longEvery, accent,
                )
            }
            Section(stringResource(R.string.editor_flowing)) {
                SwitchRow(stringResource(R.string.editor_auto_break), rhythm.autoBreak, actions.autoBreak, note = stringResource(R.string.editor_auto_break_note), accent = accent)
                SwitchRow(stringResource(R.string.editor_auto_focus), rhythm.autoFocus, actions.autoFocus, accent = accent)
                PauseCap(rhythm.pauseCap, accent, actions)
            }
        }
        is Rhythm.Flow -> {
            Section(stringResource(R.string.editor_flow_breaks)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        rhythm.tiers.forEach { tier ->
                            val rest = tier.rest.minutesInt()
                            val line = tier.upTo?.let { stringResource(R.string.editor_tier, it.minutesInt(), rest) } ?: stringResource(R.string.editor_tier_last, rest)
                            QuietText(line, QuietTheme.type.body, maxLines = 1)
                        }
                        QuietText(stringResource(R.string.editor_tiers_fixed), QuietTheme.type.caption, color = QuietTheme.colors.inkDim, maxLines = 2)
                    }
                    InfoButton({ onWhy(Why.Tiers) })
                }
                SwitchRow(stringResource(R.string.editor_auto_break), rhythm.autoBreak, actions.autoBreak, accent = accent)
            }
            Section(stringResource(R.string.editor_nudge)) {
                SwitchRow(stringResource(R.string.editor_nudge_on), rhythm.nudgeAfter != null, actions.nudge, note = stringResource(R.string.editor_nudge_note), accent = accent)
                rhythm.nudgeAfter?.let { after ->
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        DurationDial(stringResource(R.string.editor_nudge_after), after.minutesInt(), ModeRules.NUDGE.minutesRange(), actions.nudgeAfter, accent, size = 200.dp)
                    }
                }
                PauseCap(rhythm.pauseCap, accent, actions)
            }
        }
    }
}

@Composable
private fun PauseCap(cap: Duration?, accent: Color, actions: EditorActions) {
    val options = listOf<Int?>(null, 5, 10, 15, 20, 30)
    ChoiceRow(
        stringResource(R.string.editor_pause_cap), options, cap?.minutesInt()?.closestOf(options.filterNotNull()),
        { if (it == null) stringResource(R.string.editor_off) else stringResource(R.string.dial_minutes, it) }, actions.pauseCap, accent,
        note = stringResource(R.string.editor_pause_cap_note),
    )
}

@Composable
private fun RitualPage(ui: EditorUi, mode: Mode, accent: Color, actions: EditorActions) {
    val ritual = mode.ritual
    Section(stringResource(R.string.editor_before)) {
        ChoiceRow(
            stringResource(R.string.editor_intention), IntentionRule.entries.toList(), ritual.intention,
            {
                stringResource(
                    when (it) {
                        IntentionRule.Off -> R.string.editor_off
                        IntentionRule.Optional -> R.string.editor_optional
                        IntentionRule.Required -> R.string.editor_required
                    },
                )
            },
            actions.intention, accent,
        )
        SwitchRow(stringResource(R.string.editor_if_then), ritual.ifThen, actions.ifThen, note = stringResource(R.string.editor_if_then_note), accent = accent)
        SwitchRow(stringResource(R.string.editor_resume_note), ritual.showResumeNote, actions.resumeNote, accent = accent)
    }
    Section(stringResource(R.string.editor_checklist)) {
        ModeEditorViewModel.BuiltInItems.forEach { item ->
            SwitchRow(builtInLabel(item.id), ritual.checklist.any { it.id == item.id }, { actions.builtInItem(item.id, it) }, accent = accent)
        }
        ritual.checklist.filter { c -> ModeEditorViewModel.BuiltInItems.none { it.id == c.id } }.forEach { item ->
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                QuietText(item.label, QuietTheme.type.body, maxLines = 1, modifier = Modifier.weight(1f))
                QuietTextButton(stringResource(R.string.editor_remove), { actions.removeItem(item.id) })
            }
        }
        if (ritual.checklist.size < ModeRules.MAX_CHECKLIST) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { QuietButton(stringResource(R.string.editor_add_item), actions.startItem) }
        }
        QuietText(
            stringResource(R.string.editor_checklist_note), QuietTheme.type.caption, color = QuietTheme.colors.inkDim, maxLines = 2,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    if (ritual.hasScreen) {
        Section(stringResource(R.string.editor_ritual_screen)) {
            SwitchRow(
                stringResource(R.string.skip_ritual_next), ui.skipRitual, actions.skipRitual, enabled = ui.canSkip || ui.skipRitual,
                note = stringResource(if (ui.canSkip || ui.skipRitual) R.string.editor_skip_note else R.string.editor_skip_after_run), accent = accent,
            )
        }
    }
}

@Composable
fun builtInLabel(id: String): String = when (id) {
    Presets.CHECK_PHONE -> stringResource(R.string.check_phone)
    Presets.CHECK_WATER -> stringResource(R.string.check_water)
    Presets.CHECK_ONE_WINDOW -> stringResource(R.string.check_one_window)
    else -> id
}

@Composable
private fun ScreenPage(ui: EditorUi, mode: Mode, accent: Color, actions: EditorActions, onWhy: (Why) -> Unit) {
    val e = mode.environment
    val dark = e.display != Display.Full
    Section(stringResource(R.string.editor_screen)) {
        ChoiceRow(
            stringResource(R.string.editor_display), Display.entries.toList(), e.display, { stringResource(displayName(it)) }, actions.display, accent,
            note = stringResource(displayNote(e.display)), trailing = { InfoButton({ onWhy(Why.Display) }) },
        )
        if (e.display != Display.Blackout) {
            ChoiceRow(
                stringResource(R.string.editor_face), Face.entries.toList(), e.face, { stringResource(faceName(it)) }, actions.face, accent,
                note = if (ui.faceOverride != null) stringResource(R.string.editor_face_overridden) else null,
            )
        }
        if (!dark) SwitchRow(stringResource(R.string.editor_seconds), e.showSeconds, actions.seconds, accent = accent)
        SwitchRow(stringResource(R.string.editor_hold), e.touch is TouchPolicy.HoldToReveal, actions.holdToOpen, note = stringResource(R.string.editor_hold_note), accent = accent)
        if (dark) {
            SwitchRow(stringResource(R.string.editor_knock), e.knockToPeek, actions.knock, note = stringResource(R.string.editor_knock_note), accent = accent)
            SwitchRow(stringResource(R.string.editor_sunrise), e.sunrise, actions.sunrise, accent = accent)
        }
        if (e.display == Display.Calm) {
            val options = listOf<Int?>(null, 10, 15, 20, 30)
            ChoiceRow(
                stringResource(R.string.editor_check_in), options, e.checkIn?.minutesInt()?.closestOf(options.filterNotNull()),
                { if (it == null) stringResource(R.string.editor_off) else stringResource(R.string.editor_every_min, it) }, actions.checkIn, accent,
                note = stringResource(R.string.editor_check_in_note),
            )
        }
    }
    Section(stringResource(R.string.editor_sound)) {
        ChoiceRow(
            stringResource(R.string.editor_cues), CueStyle.entries.toList(), e.cues, { stringResource(cueName(it)) }, actions.cues, accent,
        )
        ChoiceRow(
            stringResource(R.string.editor_soundscape), listOf(null) + Soundscape.entries, e.soundscape, { stringResource(soundscapeName(it)) },
            actions.soundscape, accent, note = stringResource(R.string.soundscape_honest), trailing = { InfoButton({ onWhy(Why.Sound) }) },
        )
    }
    Section(stringResource(R.string.editor_words)) {
        SwitchRow(stringResource(R.string.editor_warm), e.tone == CopyTone.Warm, actions.warm, note = stringResource(R.string.editor_warm_note), accent = accent)
    }
}

/** What a preset does, in sentences: shown instead of the controls (presets are read-only). */
@Composable
private fun Summary(mode: Mode, modifier: Modifier = Modifier) {
    val e = mode.environment
    val lines = buildList {
        add(rhythmLine(mode.rhythm))
        add(
            stringResource(
                when (mode.ritual.intention) {
                    IntentionRule.Off -> R.string.summary_intention_off
                    IntentionRule.Optional -> R.string.summary_intention_optional
                    IntentionRule.Required -> R.string.summary_intention_required
                },
            ),
        )
        if (mode.ritual.ifThen) add(stringResource(R.string.summary_if_then))
        if (mode.ritual.checklist.isNotEmpty()) add(stringResource(R.string.summary_checklist, mode.ritual.checklist.joinToString(" · ") { it.label }))
        add(stringResource(R.string.summary_display, stringResource(displayName(e.display)), stringResource(faceName(e.face))))
        if (e.touch is TouchPolicy.HoldToReveal) add(stringResource(R.string.summary_hold))
        if (e.knockToPeek) add(stringResource(R.string.summary_knock))
        add(stringResource(R.string.summary_cues, stringResource(cueName(e.cues))))
    }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(QuietTheme.colors.surface).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        lines.forEach { QuietText(it, QuietTheme.type.body, maxLines = 3) }
    }
}

@Composable
private fun rhythmLine(r: Rhythm): String = when (r) {
    is Rhythm.Fixed -> if (r.longEvery > 0) {
        stringResource(R.string.summary_fixed_long, r.focus.minutesInt(), r.short.minutesInt(), r.long.minutesInt(), r.longEvery)
    } else {
        stringResource(R.string.summary_fixed_plain, r.focus.minutesInt(), r.short.minutesInt())
    }
    is Rhythm.Flow -> stringResource(R.string.summary_flow_line)
}

fun displayName(d: Display) = when (d) {
    Display.Full -> R.string.display_full
    Display.Calm -> R.string.screen_calm
    Display.Blackout -> R.string.screen_blackout
}

private fun displayNote(d: Display) = when (d) {
    Display.Full -> R.string.display_full_note
    Display.Calm -> R.string.display_calm_note
    Display.Blackout -> R.string.display_blackout_note
}

fun faceName(f: Face) = when (f) {
    Face.Ring -> R.string.face_ring
    Face.Disc -> R.string.face_disc
    Face.Horizon -> R.string.face_horizon
    Face.Digits -> R.string.face_digits
}

fun cueName(c: CueStyle) = when (c) {
    CueStyle.Off -> R.string.editor_off
    CueStyle.Soft -> R.string.cue_soft
    CueStyle.Rising -> R.string.cue_rising
}

fun soundscapeName(s: Soundscape?) = when (s) {
    null -> R.string.editor_off
    Soundscape.Rain -> R.string.soundscape_rain
    Soundscape.Wind -> R.string.soundscape_wind
    Soundscape.Pink -> R.string.soundscape_pink
    Soundscape.Brown -> R.string.soundscape_brown
}

/** A preset value on a chip row: the option nearest [this] (an edited value may sit between the chips). */
private fun Int.closestOf(options: List<Int>): Int = options.minBy { kotlin.math.abs(it - this) }

private fun Duration.minutesInt(): Int = inWholeMinutes.toInt()

private fun ClosedRange<Duration>.minutesRange(): IntRange = start.inWholeMinutes.toInt()..endInclusive.inWholeMinutes.toInt()
