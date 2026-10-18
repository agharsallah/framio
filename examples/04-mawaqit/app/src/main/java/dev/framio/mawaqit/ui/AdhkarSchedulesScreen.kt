package dev.framio.mawaqit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.framio.mawaqit.core.AdhkarCategory
import dev.framio.mawaqit.core.AdhkarReminders
import dev.framio.mawaqit.core.AdhkarTime
import dev.framio.mawaqit.core.PrayerKey
import dev.framio.mawaqit.core.Strings
import dev.framio.mawaqit.core.log
import dev.framio.mawaqit.data.AdhkarController
import dev.framio.mawaqit.engine.DisplayState
import java.time.DayOfWeek
import java.time.format.TextStyle as DateStyle
import java.util.Locale

/**
 * The reminder schedules on the frame: the list (a tap edits, the switch turns one on or off) and the editor: which
 * collection, a clock time or minutes after/before a prayer, the days, remind or open, phone and wake. Standing, the
 * editor takes the screen while open. The panel edits the same `settings.adhkar.schedules`.
 */
@Composable
fun AdhkarSchedulesScreen(st: DisplayState, a: AdhkarController, wide: Boolean) {
    val p = LocalPalette.current
    val s = st.strings
    val list = st.settings.adhkar.schedules
    var draft by remember { mutableStateOf<AdhkarTime?>(null) }
    val add = {
        a.touch()
        draft = AdhkarTime(AdhkarReminders.newId(list.map { it.id }), AdhkarCategory.MORNING, "fajr+20")
    }
    Column(Modifier.fillMaxSize().padding(start = if (wide) 48.dp else 34.dp, end = if (wide) 48.dp else 34.dp, top = 26.dp, bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(66.dp), verticalAlignment = Alignment.CenterVertically) {
            AdRoundButton({ if (!wide && draft != null) draft = null else a.back() }, size = 58.dp) {
                AdChevron(p.ink, toEnd = false, rtl = s.rtl, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.width(22.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                BellGlyph(p.accent, Modifier.size(34.dp))
                Spacer(Modifier.width(14.dp))
                Txt(s.log("sch_title"), 40.sp, p.ink, maxLines = 1, light = true)
            }
            if (list.size < AdhkarReminders.MAX) AdPill("+  " + s.log("sch_add"), add, primary = draft == null)
            Spacer(Modifier.width(22.dp))
            BasicText(Format.time(st.now, st.clock24h), style = digits(36.sp, p.inkDim))
        }
        Spacer(Modifier.height(18.dp))
        val editor = @Composable { m: Modifier ->
            val d = draft
            if (d != null) {
                Editor(st, d, isNew = list.none { it.id == d.id }, modifier = m, change = { a.touch(); draft = it },
                    save = { a.saveSchedule(it); draft = null }, delete = { a.removeSchedule(d.id); draft = null }, cancel = { a.touch(); draft = null })
            } else {
                Notes(st, m)
            }
        }
        val rows = @Composable { m: Modifier ->
            Column(m.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (list.isEmpty()) Txt(s.log("sch_empty"), 22.sp, p.inkDim, Modifier.padding(top = 20.dp))
                for (t in list) ScheduleRow(st, t, selected = draft?.id == t.id, edit = { a.touch(); draft = t }, toggle = { a.toggleSchedule(t.id) })
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                rows(Modifier.width(540.dp).fillMaxHeight())
                Spacer(Modifier.width(24.dp))
                editor(if (draft != null) Modifier.weight(1f).fillMaxHeight() else Modifier.weight(1f))
            }
        } else if (draft != null) {
            editor(Modifier.fillMaxSize())
        } else {
            rows(Modifier.fillMaxWidth().weight(1f))
            Spacer(Modifier.height(18.dp))
            Notes(st, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ScheduleRow(st: DisplayState, t: AdhkarTime, selected: Boolean, edit: () -> Unit, toggle: () -> Unit) {
    val p = LocalPalette.current
    val s = st.strings
    Row(
        Modifier.fillMaxWidth().height(96.dp).card(p, highlighted = selected).adClick(edit).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AdhkarGlyph(t.category, if (t.enabled) p.accent else p.inkFaint, Modifier.size(44.dp), second = p.accent2)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(describe(t.at, s, st.clock24h), style = digits(28.sp, if (t.enabled) p.ink else p.inkDim, light = false).let { if (s.rtl) it.arabic().copy(fontSize = 26.sp) else it }, maxLines = 1)
                Spacer(Modifier.width(12.dp))
                Txt(s[t.category.key], 19.sp, p.inkDim, Modifier.weight(1f, fill = false), maxLines = 1)
            }
            val bits = listOfNotNull(
                days(t.days, s),
                if (t.opens) s.log("sch_open") else s.log("sch_remind"),
                s.log("sch_phone").takeIf { t.push && st.settings.adhkar.pushEnabled },
                s.log("sch_wake").takeIf { t.wake },
            )
            Txt(bits.joinToString("  ·  "), 17.sp, p.inkFaint, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Switch(t.enabled, toggle)
    }
}

/** The editor's right-hand side when nothing is being edited: how the reminders behave, and the phone. */
@Composable
private fun Notes(st: DisplayState, modifier: Modifier) {
    val p = LocalPalette.current
    val s = st.strings
    Column(modifier.card(p).padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        BellGlyph(p.accent2, Modifier.size(44.dp))
        Txt(s.log("sch_hint"), 21.sp, p.inkDim)
        Txt(if (st.settings.adhkar.pushEnabled && st.settings.adhkar.pushTopic.isNotEmpty()) s.log("sch_push_on") else s.log("sch_push_off"), 19.sp, p.inkFaint)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Editor(
    st: DisplayState,
    d: AdhkarTime,
    isNew: Boolean,
    modifier: Modifier,
    change: (AdhkarTime) -> Unit,
    save: (AdhkarTime) -> Unit,
    delete: () -> Unit,
    cancel: () -> Unit,
) {
    val p = LocalPalette.current
    val s = st.strings
    val clock = AdhkarReminders.clock(d.at)
    val rel = AdhkarReminders.relative(d.at)
    Column(modifier.card(p).padding(horizontal = 26.dp, vertical = 20.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Section(s.log("sch_collection")) {
                for (c in AdhkarCategory.entries) Choice(s[c.key], c == d.category) { change(d.copy(category = c)) }
            }
            Section(describe(d.at, s, st.clock24h)) {
                Choice(s.log("sch_clock"), clock != null) { if (clock == null) change(d.copy(at = "07:00")) }
                Choice(s.log("sch_prayer"), rel != null) { if (rel == null) change(d.copy(at = "fajr+20")) }
            }
            if (clock != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val m = clock.hour * 60 + clock.minute
                    fun at(x: Int) = ((x % 1440) + 1440) % 1440
                    Stepper("−1 h", "+1 h", { change(d.copy(at = hhmm(at(m - 60)))) }, { change(d.copy(at = hhmm(at(m + 60)))) })
                    Spacer(Modifier.width(18.dp))
                    BasicText(clock(m, st.clock24h), style = digits(48.sp, p.ink, light = false))
                    Spacer(Modifier.width(18.dp))
                    Stepper("−5", "+5", { change(d.copy(at = hhmm(at(m - 5)))) }, { change(d.copy(at = hhmm(at(m + 5)))) })
                }
            }
            if (rel != null) {
                val (prayer, n) = rel
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (k in AdhkarReminders.ANCHORS) Choice(s.prayer(k), k == prayer) { change(d.copy(at = relative(k, n))) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Choice(s.log("sch_at_prayer").replace("{p}", s.prayer(prayer)), n >= 0) { if (n < 0) change(d.copy(at = relative(prayer, -n))) }
                    Spacer(Modifier.width(10.dp))
                    Choice(s.log("sch_before_prayer").replace("{p}", s.prayer(prayer)), n < 0) { if (n >= 0) change(d.copy(at = relative(prayer, -maxOf(n, 5)))) }
                    Spacer(Modifier.width(18.dp))
                    val minutes = kotlin.math.abs(n)
                    val sign = if (n < 0) -1 else 1
                    Stepper("−5", "+5",
                        { change(d.copy(at = relative(prayer, sign * (minutes - 5).coerceAtLeast(if (sign < 0) 5 else 0)))) },
                        { change(d.copy(at = relative(prayer, sign * (minutes + 5).coerceAtMost(if (sign < 0) 180 else 240)))) })
                    Spacer(Modifier.width(14.dp))
                    BasicText("$minutes", style = digits(40.sp, p.ink, light = false))
                    Spacer(Modifier.width(6.dp))
                    Txt(s.log("sch_minutes"), 18.sp, p.inkDim, maxLines = 1)
                }
            }
            Section(s.log("sch_days")) {
                Choice(s.log("sch_every_day"), d.days.isEmpty()) { change(d.copy(days = emptySet())) }
                val locale = Locale(st.settings.display.language)
                for (day in DayOfWeek.entries) {
                    Choice(day.getDisplayName(DateStyle.SHORT, locale), day in d.days) {
                        val next = if (day in d.days) d.days - day else d.days + day
                        change(d.copy(days = if (next.size == 7) emptySet() else next))
                    }
                }
            }
            Section(s.log("sch_mode")) {
                Choice(s.log("sch_remind"), !d.opens) { change(d.copy(mode = AdhkarTime.REMIND)) }
                Choice(s.log("sch_open"), d.opens) { change(d.copy(mode = AdhkarTime.OPEN)) }
                Choice((if (d.push) "✓  " else "") + s.log("sch_phone"), d.push) { change(d.copy(push = !d.push)) }
                Choice((if (d.wake) "✓  " else "") + s.log("sch_wake"), d.wake) { change(d.copy(wake = !d.wake)) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AdPill(s.log("sch_save"), { save(d) }, primary = true)
            AdPill(s.log("sch_cancel"), cancel)
            Spacer(Modifier.weight(1f))
            if (!isNew) AdPill(s.log("sch_delete"), delete)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column {
        Label(title, p.inkDim)
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

/** A chip to pick or tick; 48 dp tall for a finger. */
@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.height(50.dp).clip(RoundedCornerShape(50)).background(if (selected) p.accent else p.card)
            .border(1.dp, if (selected) p.accent else p.hairline, RoundedCornerShape(50))
            .adClick(onClick).padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, 20.sp, if (selected) p.onAccent else p.ink, maxLines = 1)
    }
}

@Composable
private fun Stepper(minus: String, plus: String, down: () -> Unit, up: () -> Unit) {
    val p = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AdRoundButton(down, size = 56.dp) { BasicText(minus, style = Base.copy(color = p.ink, fontSize = 18.sp, textDirection = TextDirection.Ltr)) }
        AdRoundButton(up, size = 56.dp) { BasicText(plus, style = Base.copy(color = p.ink, fontSize = 18.sp, textDirection = TextDirection.Ltr)) }
    }
}

/** On/off, drawn: a track with its knob at the end when on (mirrored in Arabic by the layout). */
@Composable
private fun Switch(on: Boolean, toggle: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.size(76.dp, 44.dp).clip(RoundedCornerShape(50)).background(if (on) p.accent else p.hairline).adClick(toggle).padding(5.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(50)).background(if (on) p.onAccent else p.inkDim))
    }
}

@Composable
private fun Txt(text: String, size: TextUnit, color: Color, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, light: Boolean = false) {
    val base = Base.copy(color = color, fontSize = size, fontFamily = if (light) Fonts.light else Fonts.regular)
    val style = if (hasArabic(text)) base.arabic().copy(fontSize = size * 1.1f, lineHeight = size * 1.8f, lineHeightStyle = ArabicLine) else base
    BasicText(text, modifier, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** "07:30", "20 min after Fajr", "30 min before Maghrib", "after Asr". */
internal fun describe(at: String, s: Strings, clock24h: Boolean): String {
    AdhkarReminders.clock(at)?.let { return clock(it.hour * 60 + it.minute, clock24h) }
    val (k, n) = AdhkarReminders.relative(at) ?: return at
    return when {
        n == 0 -> s.log("sch_at_prayer").replace("{p}", s.prayer(k))
        n > 0 -> s.log("sch_after").replace("{n}", n.toString()).replace("{p}", s.prayer(k))
        else -> s.log("sch_before").replace("{n}", (-n).toString()).replace("{p}", s.prayer(k))
    }
}

private fun days(days: Set<DayOfWeek>, s: Strings): String =
    if (days.isEmpty()) s.log("sch_every_day") else days.sorted().joinToString(" ") { it.getDisplayName(DateStyle.SHORT, Locale(s.lang)) }

private fun hhmm(m: Int) = "%02d:%02d".format(Locale.ROOT, m / 60, m % 60)

private fun relative(k: PrayerKey, n: Int) = k.id + (if (n < 0) "-" else "+") + kotlin.math.abs(n)
