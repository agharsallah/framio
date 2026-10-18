package dev.framio.mawaqit.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.framio.mawaqit.core.AdhkarCategory
import dev.framio.mawaqit.core.PhaseKind
import dev.framio.mawaqit.core.Strings
import dev.framio.mawaqit.core.log
import dev.framio.mawaqit.data.AdhkarController
import dev.framio.mawaqit.data.AdhkarReminder
import dev.framio.mawaqit.engine.DisplayState

// The adhkar section's hooks into the shared screens: each is one call in Root, MainScreen or the settings sheet.

/** Provided by the activity; null in previews and tests, where every hook draws nothing. */
val LocalAdhkar = staticCompositionLocalOf<AdhkarController?> { null }

/** Root: whether the adhkar screen is the one to show (a prayer phase, a message or black wins). */
@Composable
fun adhkarOnTop(st: DisplayState): Boolean {
    val a = LocalAdhkar.current ?: return false
    val ui by a.ui.collectAsState()
    return a.onTop(st, ui)
}

/** The top bar's entry, next to the gear: quiet normally, lit with the progress while a reminder is due. */
@Composable
fun AdhkarTopChip(st: DisplayState) {
    val a = LocalAdhkar.current ?: return
    if (!st.settings.adhkar.enabled) return
    val p = LocalPalette.current
    val s = st.strings
    val r by a.reminder.collectAsState()
    val reminder = r
    val lit = reminder != null
    Row(
        Modifier.padding(start = 16.dp).height(48.dp).clip(RoundedCornerShape(50))
            .background(if (lit) p.accent.copy(alpha = 0.18f) else p.card)
            .border(1.dp, if (lit) p.accent else p.hairline, RoundedCornerShape(50))
            .adClick { a.open(reminder?.category) }
            .padding(start = 14.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AdhkarGlyph(reminder?.category, if (lit) p.accent else p.inkDim, Modifier.size(28.dp), second = p.accent2)
        Spacer(Modifier.width(10.dp))
        // Standing, the top bar is narrow: the progress alone next to the collection's glyph
        val narrow = LocalWindowInfo.current.containerSize.let { it.width < it.height }
        val text = when {
            reminder == null -> s["adhkar"]
            narrow -> "${reminder.done}/${reminder.total}"
            else -> "${s[reminder.category.key]}  ${reminder.done}/${reminder.total}"
        }
        val style = Base.copy(color = if (lit) p.accent else p.inkDim, fontSize = 21.sp, fontWeight = FontWeight.Medium)
        BasicText(text, style = if (s.rtl) style.arabic().copy(fontSize = 24.sp) else style, maxLines = 1)
    }
}

/**
 * The slide area of the main screen: while a reminder is due its card takes the place of the clock, for the first
 * [REMINDER_FIRST_S] seconds and then [REMINDER_AGAIN_S] seconds every [REMINDER_EVERY_S]; otherwise [content].
 */
@Composable
fun AdhkarReminderArea(st: DisplayState, content: @Composable () -> Unit) {
    val a = LocalAdhkar.current
    val r = a?.reminder?.collectAsState()?.value
    val age = r?.let { (st.now.toInstant().toEpochMilli() - it.since) / 1000 } ?: 0
    val big = r != null && (age < REMINDER_FIRST_S || age % REMINDER_EVERY_S < REMINDER_AGAIN_S)
    AnimatedContent(if (big) r else null, transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(700)) }, label = "adhkar-reminder") { shown ->
        if (shown != null && a != null) ReminderCard(st, a, shown) else content()
    }
}

private const val REMINDER_FIRST_S = 90L
private const val REMINDER_EVERY_S = 480L
private const val REMINDER_AGAIN_S = 40L

@Composable
private fun ReminderCard(st: DisplayState, a: AdhkarController, r: AdhkarReminder) {
    val p = LocalPalette.current
    val s = st.strings
    BoxWithConstraints(
        Modifier.fillMaxSize().padding(vertical = 18.dp).card(p, highlighted = true)
            .adClick { a.open(r.category) },
    ) {
        val wide = maxWidth > maxHeight * 1.4f // the portrait slide area is nearly square: stacked there
        val title = @Composable {
            Label(s["adhkar"], p.accent)
            Spacer(Modifier.height(14.dp))
            BasicText(s[r.category.key], style = Base.copy(color = p.ink, fontSize = 54.sp, fontFamily = Fonts.light).let { if (s.rtl) it.arabic().copy(fontSize = 60.sp) else it }, maxLines = 1)
            if (!s.rtl) BasicText(Strings.of("ar")[r.category.key], style = Base.arabic().copy(color = p.accent2, fontSize = 44.sp, lineHeight = 62.sp), maxLines = 1)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AdRing(if (r.total == 0) 0f else r.done / r.total.toFloat(), p.accent, p.hairline, 5.dp, Modifier.size(34.dp))
                Spacer(Modifier.width(14.dp))
                BasicText("${r.done} / ${r.total}", style = digits(30.sp, p.ink, light = false).copy(textDirection = TextDirection.Ltr))
                Spacer(Modifier.width(18.dp))
                BasicText("·  ${s["adhkar_until"].replace("{t}", Format.time(r.until, st.clock24h))}", style = Base.copy(color = p.inkDim, fontSize = 24.sp))
            }
        }
        val buttons = @Composable {
            AdPill(s["adhkar_start"], { a.open(r.category) }, primary = true)
            Spacer(Modifier.width(14.dp).height(14.dp))
            AdPill(s["adhkar_later"], { a.dismissReminder() })
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                AdhkarGlyph(r.category, p.accent, Modifier.size(150.dp), second = p.accent2)
                Spacer(Modifier.width(44.dp))
                Column(Modifier.weight(1f)) { title() }
                Column(horizontalAlignment = Alignment.End) { buttons() }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                AdhkarGlyph(r.category, p.accent, Modifier.size(130.dp), second = p.accent2)
                Spacer(Modifier.height(28.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) { title() }
                Spacer(Modifier.height(32.dp))
                Row { buttons() }
            }
        }
    }
}

/**
 * Over a full-screen phase: on Mawaqit's azkar screen, "count them" opens the counted after-prayer adhkar; on the
 * dim night screen, a quiet line for a due reminder (a schedule never chimes or opens the reader at night).
 */
@Composable
fun AdhkarPhaseHint(st: DisplayState) {
    val a = LocalAdhkar.current ?: return
    if (!st.settings.adhkar.enabled) return
    val p = LocalPalette.current
    val s = st.strings
    val r by a.reminder.collectAsState()
    when {
        st.phase.kind == PhaseKind.AZKAR -> Box(Modifier.fillMaxSize().padding(36.dp), contentAlignment = Alignment.BottomEnd) {
            AdPill(s["adhkar_count_them"], { a.open(AdhkarCategory.AFTER_PRAYER) }, leading = {
                AdhkarGlyph(null, p.accent, Modifier.size(26.dp), second = p.accent2)
            })
        }
        st.phase.kind == PhaseKind.NIGHT && !st.screen.black && r != null -> {
            val c = r!!.category
            Box(Modifier.fillMaxSize().padding(bottom = 48.dp), contentAlignment = Alignment.BottomCenter) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).border(1.dp, p.inkFaint.copy(alpha = 0.5f), RoundedCornerShape(50))
                        .adClick { a.open(c) }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AdhkarGlyph(c, p.inkFaint, Modifier.size(26.dp))
                    Spacer(Modifier.width(12.dp))
                    BasicText("${s[c.key]} · ${s["adhkar_start"]}", style = Base.copy(color = p.inkFaint, fontSize = 22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The on-frame settings sheet: open a collection, turn the reminders on or off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdhkarSettingsRow(st: DisplayState, close: () -> Unit) {
    val a = LocalAdhkar.current ?: return
    val p = LocalPalette.current
    val s = st.strings
    val ad = st.settings.adhkar
    Column {
        Label(s["adhkar"], p.inkDim)
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetChip(s["adhkar_open"], false) { close(); a.open(null) }
            SheetChip(s["adhkar_morning"], false) { close(); a.open(AdhkarCategory.MORNING) }
            SheetChip(s["adhkar_evening"], false) { close(); a.open(AdhkarCategory.EVENING) }
            SheetChip(s["tasbih"], false) { close(); a.open(null, tasbih = true) }
            LocalAdhkarLoop.current?.takeIf { st.settings.loop.enabled }?.let { loop ->
                SheetChip(s["loop"], false) { close(); a.close("sheet"); loop.show(dev.framio.mawaqit.data.FramePage.ADHKAR, "sheet") }
            }
            SheetChip(s.log("log"), false) { close(); a.openLog() }
            // the schedules' own screen: lit while any is on
            val on = ad.schedules.count { it.enabled }
            SheetChip(if (on > 0) "${s["adhkar_reminders"]} · $on" else s["adhkar_reminders"], ad.enabled && on > 0) { close(); a.openSchedules() }
        }
    }
}

@Composable
private fun SheetChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) p.accent else p.card)
            .adClick(onClick).padding(horizontal = 22.dp, vertical = 12.dp),
    ) {
        val style = Base.copy(color = if (selected) p.onAccent else p.ink, fontSize = 22.sp)
        BasicText(text, style = if (text.any { it in '؀'..'ۿ' }) style.arabic().copy(fontSize = 24.sp) else style, maxLines = 1)
    }
}
