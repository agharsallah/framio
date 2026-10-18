package dev.framio.mawaqit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.framio.mawaqit.core.AdhkarCategory
import dev.framio.mawaqit.core.AdhkarLog
import dev.framio.mawaqit.core.AdhkarStats
import dev.framio.mawaqit.core.CollectionStats
import dev.framio.mawaqit.core.Strings
import dev.framio.mawaqit.core.log
import dev.framio.mawaqit.data.AdhkarController
import dev.framio.mawaqit.engine.DisplayState
import java.time.format.TextStyle as DateStyle
import java.util.Locale

/**
 * The adhkar log: the planned collections' streak, the best one and the 30-day completion; a heatmap of the last
 * four months (each day's share of its planned adhkar said); every collection's day with its ring, last week, 30-day
 * rate and streak; the tasbih's totals and the usual time of each collection. All drawn on a few Canvases, no chart
 * library. Only the header reads the clock: the rest recomposes when the log or the day changes, not every second.
 */
@Composable
fun AdhkarLogScreen(st: DisplayState, a: AdhkarController, wide: Boolean) {
    val history by a.log.history.collectAsState()
    val today = st.now.toLocalDate()
    val schedules = st.settings.adhkar.schedules
    val stats = remember(history, today, schedules) { a.stats(history, today) }
    val lang = st.settings.display.language
    val strings = remember(lang) { Strings.of(lang) }
    val next = remember(st.now.minute, schedules) { a.dues().filter { it.at.isAfter(st.now) }.minByOrNull { it.at.toInstant() } }
    Column(Modifier.fillMaxSize().padding(start = if (wide) 48.dp else 34.dp, end = if (wide) 48.dp else 34.dp, top = 26.dp, bottom = 24.dp)) {
        LogHeader(st, a, wide, next?.let { strings.log("log_next").replace("{c}", strings[it.category.key]).replace("{t}", Format.time(it.at, st.clock24h)) })
        Spacer(Modifier.height(10.dp))
        LogBody(stats, strings, st.clock24h, wide, a)
    }
}

/** The Log button of the collections and completion screens: three rising bars. */
@Composable
fun LogPill(st: DisplayState, onClick: () -> Unit) {
    val p = LocalPalette.current
    AdPill(st.strings.log("log"), onClick, leading = { BarsGlyph(p.accent, Modifier.size(26.dp)) })
}

@Composable
private fun BarsGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val bar = w * 0.2f
        listOf(0.45f, 0.7f, 1f).forEachIndexed { i, h ->
            val x = w * 0.1f + i * (bar + w * 0.1f)
            drawRoundRect(color, Offset(x, w * (0.92f - 0.8f * h)), Size(bar, w * 0.8f * h), CornerRadius(bar / 3))
        }
    }
}

@Composable
private fun LogHeader(st: DisplayState, a: AdhkarController, wide: Boolean, next: String?) {
    val p = LocalPalette.current
    val s = st.strings
    Row(Modifier.fillMaxWidth().heightIn(min = 66.dp), verticalAlignment = Alignment.CenterVertically) {
        AdRoundButton({ a.back() }, size = 58.dp) { AdChevron(p.ink, toEnd = false, rtl = s.rtl, modifier = Modifier.size(38.dp)) }
        Spacer(Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                BasicText(s.log("log_title"), Modifier.alignByBaseline(), style = Base.copy(color = p.ink, fontSize = 36.sp, lineHeight = 42.sp, fontFamily = Fonts.light).let { if (s.rtl) it.arabic().copy(fontSize = 34.sp, lineHeight = 50.sp) else it }, maxLines = 1)
                if (!s.rtl && wide) { // standing, the Arabic name would run into the button
                    Spacer(Modifier.width(16.dp))
                    BasicText(Strings.of("ar").log("log_title"), Modifier.alignByBaseline(), style = Base.arabic().copy(color = p.accent2, fontSize = 28.sp, lineHeight = 40.sp), maxLines = 1)
                }
            }
            next?.let { BasicText(it, style = Base.copy(color = p.inkDim, fontSize = 17.sp, lineHeight = 22.sp).let { st -> if (s.rtl) st.arabic().copy(fontSize = 18.sp, lineHeight = 22.sp) else st }, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        AdPill(s["adhkar_reminders"], { a.openSchedules() }, leading = { BellGlyph(p.accent, Modifier.size(24.dp)) })
        Spacer(Modifier.width(22.dp))
        BasicText(Format.time(st.now, st.clock24h), style = digits(36.sp, p.inkDim))
    }
}

/** A bell: the reminders. */
@Composable
fun BellGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val body = Path().apply {
            moveTo(w * 0.18f, w * 0.74f)
            cubicTo(w * 0.28f, w * 0.62f, w * 0.26f, w * 0.48f, w * 0.27f, w * 0.4f)
            cubicTo(w * 0.29f, w * 0.22f, w * 0.71f, w * 0.22f, w * 0.73f, w * 0.4f)
            cubicTo(w * 0.74f, w * 0.48f, w * 0.72f, w * 0.62f, w * 0.82f, w * 0.74f)
            close()
        }
        drawPath(body, color)
        drawCircle(color, w * 0.08f, Offset(w * 0.5f, w * 0.84f))
        drawCircle(color, w * 0.06f, Offset(w * 0.5f, w * 0.16f))
    }
}

@Composable
private fun LogBody(stats: AdhkarStats, s: Strings, clock24h: Boolean, wide: Boolean, a: AdhkarController) {
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(560.dp).fillMaxHeight()) {
                Kpis(stats, s)
                Spacer(Modifier.height(18.dp))
                HeatmapCard(stats, s, 214.dp, Modifier.fillMaxWidth())
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    TasbihCard(stats, s, Modifier.weight(1f).fillMaxHeight())
                    UsualCard(stats, s, clock24h, Modifier.weight(1f).fillMaxHeight())
                }
            }
            Spacer(Modifier.width(22.dp))
            CollectionsCard(stats, s, a, Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Kpis(stats, s)
            Spacer(Modifier.height(18.dp))
            HeatmapCard(stats, s, 268.dp, Modifier.fillMaxWidth())
            Spacer(Modifier.height(18.dp))
            CollectionsCard(stats, s, a, Modifier.fillMaxWidth().weight(1f))
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth().height(232.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                TasbihCard(stats, s, Modifier.weight(1f).fillMaxHeight())
                UsualCard(stats, s, clock24h, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

// ---------------------------------------------------------------- the three figures

@Composable
private fun Kpis(stats: AdhkarStats, s: Strings) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().height(142.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        val days = { n: Int -> if (n == 1) s.log("log_day") else s.log("log_days") }
        Kpi(s.log("log_streak"), stats.streak, days(stats.streak)) {
            AdFlame(if (stats.streak > 0) p.accent else p.inkFaint, Modifier.size(34.dp))
        }
        Kpi(s.log("log_best"), stats.best, days(stats.best)) {
            StarGlyph(p.accent2, Modifier.size(32.dp))
        }
        Column(Modifier.weight(1.15f).fillMaxHeight().card(p).padding(horizontal = 20.dp, vertical = 12.dp)) {
            Txt(s.log("log_rate"), 18.sp, p.inkDim, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AdRing(stats.rate30, p.accent, p.hairline, 6.dp, Modifier.size(36.dp))
                Spacer(Modifier.width(10.dp))
                BasicText(percent(stats.rate30), Modifier.weight(1f, fill = false), style = digits(42.sp, p.ink).copy(textDirection = TextDirection.Ltr), maxLines = 1, softWrap = false,
                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 26.sp, maxFontSize = 42.sp, stepSize = 2.sp))
            }
            Spacer(Modifier.weight(1f))
            Txt(s.log("log_30"), 15.sp, p.inkFaint, maxLines = 1)
        }
    }
}

@Composable
private fun RowScope.Kpi(title: String, value: Int, line: String, glyph: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.weight(1f).fillMaxHeight().card(p).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Txt(title, 18.sp, p.inkDim, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            glyph()
            Spacer(Modifier.width(12.dp))
            // a long streak shrinks rather than overflowing the tile; the unit has its own line below
            BasicText(value.toString(), Modifier.weight(1f, fill = false), style = digits(44.sp, p.ink), maxLines = 1, softWrap = false,
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 26.sp, maxFontSize = 44.sp, stepSize = 2.sp))
        }
        Spacer(Modifier.weight(1f))
        Txt(line, 15.sp, p.inkFaint, maxLines = 1)
    }
}

@Composable
private fun StarGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        // an eight-pointed star (khatam), the pattern of the background
        val c = center
        val r = size.minDimension / 2
        val path = Path()
        for (i in 0 until 16) {
            val ang = -Math.PI / 2 + i * Math.PI / 8
            val rr = if (i % 2 == 0) r else r * 0.62f
            val x = c.x + kotlin.math.cos(ang).toFloat() * rr
            val y = c.y + kotlin.math.sin(ang).toFloat() * rr
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color)
    }
}

// ---------------------------------------------------------------- heatmap

@Composable
private fun HeatmapCard(stats: AdhkarStats, s: Strings, height: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier.card(p).padding(horizontal = 22.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(s.log("log_heatmap"), 20.sp, p.ink, Modifier.weight(1f), maxLines = 1)
            Legend(s)
        }
        Spacer(Modifier.height(10.dp))
        Box {
            Heatmap(stats.heatmap, s.lang, s.rtl, Modifier.fillMaxWidth().height(height))
            if (stats.empty) {
                Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
                    Txt(s.log("log_empty"), 20.sp, p.inkDim, Modifier.clip(RoundedCornerShape(16.dp)).background(scrim(p)).padding(horizontal = 22.dp, vertical = 14.dp))
                }
            }
        }
    }
}

private fun scrim(p: Palette) = if (p.light) Color(0xE6FBF6EC) else p.bottom.copy(alpha = 0.88f)

@Composable
private fun Legend(s: Strings) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Txt(s.log("log_less"), 15.sp, p.inkFaint, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        for (v in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            Box(Modifier.padding(horizontal = 2.dp).size(14.dp).clip(RoundedCornerShape(4.dp)).background(heat(p, v)))
        }
        Spacer(Modifier.width(8.dp))
        Txt(s.log("log_more"), 15.sp, p.inkFaint, maxLines = 1)
    }
}

/** The colour of a day: the track for nothing said, then the accent from faint to full. */
private fun heat(p: Palette, v: Float): Color =
    if (v <= 0f) p.hairline else lerp(p.accent.copy(alpha = if (p.light) 0.22f else 0.2f), p.accent, v.coerceIn(0f, 1f))

/** Weeks as columns (newest at the reading direction's end), days as rows, month names above, weekday names aside. */
@Composable
private fun Heatmap(h: AdhkarLog.Heatmap, lang: String, rtl: Boolean, modifier: Modifier) {
    val p = LocalPalette.current
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val locale = remember(lang) { Locale(lang) }
    val labelStyle = remember(lang, p) {
        TextStyle(color = p.inkFaint, fontSize = if (lang == "ar") 15.sp else 14.sp, fontFamily = if (lang == "ar") Fonts.arabic else Fonts.regular)
    }
    // weekday names of rows 0, 2, 4, 6 (Monday, Wednesday, Friday, Sunday), month names where a month starts
    val days = remember(h.start, lang) {
        listOf(0, 2, 4, 6).map { it to measurer.measure(h.start.plusDays(it.toLong()).dayOfWeek.getDisplayName(DateStyle.SHORT, locale), labelStyle) }
    }
    val months = remember(h.start, h.weeks, lang) {
        (0 until h.weeks).mapNotNull { w ->
            val first = (0 until 7).map { h.day(w * 7 + it) }.firstOrNull { it.dayOfMonth == 1 } ?: if (w == 0) h.start else null
            first?.let { w to measurer.measure(it.month.getDisplayName(DateStyle.SHORT, locale), labelStyle) }
        }
    }
    Canvas(modifier) {
        val labelW = (days.maxOfOrNull { it.second.size.width } ?: 0) + 10.dp.toPx()
        val top = 22.dp.toPx()
        val gap = 4.dp.toPx()
        val cell = minOf((size.width - labelW) / h.weeks, (size.height - top) / 7f) - gap
        val step = cell + gap
        val gridW = step * h.weeks
        // left to right the grid starts after the labels; right to left it ends before them
        val x0 = if (rtl) size.width - labelW - gridW else labelW
        fun colX(w: Int) = if (rtl) x0 + (h.weeks - 1 - w) * step else x0 + w * step
        for ((row, t) in days) {
            val x = if (rtl) size.width - t.size.width.toFloat() else 0f
            drawText(t, topLeft = Offset(x, top + row * step + (cell - t.size.height) / 2))
        }
        var lastEnd = if (rtl) Float.MAX_VALUE else -Float.MAX_VALUE
        for ((w, t) in months) {
            val x = if (rtl) colX(w) + cell - t.size.width else colX(w)
            if (if (rtl) x + t.size.width > lastEnd - 6 else x < lastEnd + 6) continue // no overlap at the first column
            drawText(t, topLeft = Offset(x, 0f))
            lastEnd = if (rtl) x else x + t.size.width
        }
        val radius = CornerRadius(cell * 0.22f)
        val last = h.cells.indexOfLast { it != null }
        for (i in h.cells.indices) {
            val w = i / 7
            val d = i % 7
            val v = h.cells[i]
            val o = Offset(colX(w), top + d * step)
            if (v == null) {
                if (i < last) drawRoundRect(p.hairline.copy(alpha = p.hairline.alpha * 0.5f), o, Size(cell, cell), radius) // before the log
                continue
            }
            drawRoundRect(heat(p, v), o, Size(cell, cell), radius)
            if (i == last) drawRoundRect(p.ink, o, Size(cell, cell), radius, style = Stroke(2.dp.toPx())) // today
        }
    }
}

// ---------------------------------------------------------------- collections

@Composable
private fun CollectionsCard(stats: AdhkarStats, s: Strings, a: AdhkarController, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier.card(p).padding(horizontal = 22.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Txt(s.log("log_today"), 20.sp, p.ink, Modifier.weight(1f), maxLines = 1)
            Txt(s.log("log_7"), 15.sp, p.inkFaint, Modifier.width(DOTS_W + GAP_W), maxLines = 1)
            Txt(s.log("log_30"), 15.sp, p.inkFaint, Modifier.width(RATE_W), maxLines = 1)
            Spacer(Modifier.width(STREAK_W))
        }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            for (c in stats.collections) CollectionRow(c, s, a)
            if (stats.collections.isEmpty()) Txt(s.log("log_empty"), 19.sp, p.inkDim, Modifier.padding(top = 12.dp))
        }
        // what the dots mean
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(p.accent2))
            Spacer(Modifier.width(8.dp))
            Txt(s.log("log_goal").replace("{c}", stats.goals.joinToString(", ") { s[it.key] }), 15.sp, p.inkFaint, Modifier.weight(1f), maxLines = 1)
        }
    }
}

@Composable
private fun CollectionRow(c: CollectionStats, s: Strings, a: AdhkarController) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().height(82.dp).adClick { a.open(c.category) }, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(62.dp), contentAlignment = Alignment.Center) {
            AdRing(c.today, if (c.todayComplete) p.accent else p.accent.copy(alpha = 0.75f), p.hairline, 6.dp, Modifier.fillMaxSize())
            AdhkarGlyph(c.category, if (c.today > 0f) p.accent else p.inkDim, Modifier.size(30.dp), second = p.accent2)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(s[c.category.key], 22.sp, p.ink, Modifier.weight(1f, fill = false), weight = FontWeight.Medium, maxLines = 1)
                if (c.goal) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(p.accent2))
                }
            }
            val line = when {
                c.category == AdhkarCategory.AFTER_PRAYER -> s.log("log_prayers").replace("{n}", c.todayDone.toString())
                c.todayComplete -> s["adhkar_done"]
                c.today > 0f -> "${c.todayDone}/${c.todayTotal}"
                else -> s.log("log_none_today")
            }
            Txt(line, 17.sp, if (c.todayComplete) p.accent else p.inkDim, maxLines = 1)
        }
        WeekDots(c.week, s.rtl, Modifier.width(DOTS_W).height(20.dp))
        Spacer(Modifier.width(GAP_W))
        Column(Modifier.width(RATE_W)) {
            Box(Modifier.width(RATE_W - 18.dp).height(8.dp).clip(RoundedCornerShape(50)).background(p.hairline)) {
                if (c.rate30 > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(c.rate30).clip(RoundedCornerShape(50)).background(p.accent))
            }
            Spacer(Modifier.height(6.dp))
            BasicText(percent(c.rate30), style = digits(18.sp, p.inkDim, light = false).copy(textDirection = TextDirection.Ltr))
        }
        Row(Modifier.width(STREAK_W), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            if (c.streak > 0) {
                AdFlame(p.accent, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                BasicText(c.streak.toString(), style = digits(22.sp, p.ink, light = false))
            }
        }
    }
}

private val DOTS_W = 120.dp
private val GAP_W = 14.dp
private val RATE_W = 96.dp
private val STREAK_W = 56.dp

/** The last seven days, oldest first in the reading direction: full for completed, partly filled for begun. */
@Composable
private fun WeekDots(week: List<Float>, rtl: Boolean, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        val n = week.size
        val step = size.width / n
        val r = minOf(step * 0.36f, size.height / 2)
        week.forEachIndexed { i, v ->
            val x = if (rtl) size.width - (i + 0.5f) * step else (i + 0.5f) * step
            val c = Offset(x, size.height / 2)
            drawCircle(p.hairline, r, c)
            if (v >= 1f) drawCircle(p.accent, r, c)
            else if (v > 0f) drawArc(p.accent, -90f, 360f * v, true, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r))
            if (i == n - 1) drawCircle(p.ink.copy(alpha = 0.7f), r + 2.dp.toPx(), c, style = Stroke(1.5.dp.toPx())) // today
        }
    }
}

// ---------------------------------------------------------------- tasbih, usual times

@Composable
private fun TasbihCard(stats: AdhkarStats, s: Strings, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier.card(p).padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AdhkarGlyph(null, p.accent, Modifier.size(26.dp), second = p.accent2)
            Spacer(Modifier.width(10.dp))
            Txt(s.log("log_tasbih"), 20.sp, p.ink, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                BasicText(stats.tasbih7.toString(), style = digits(38.sp, p.ink))
                Txt(s.log("log_7"), 15.sp, p.inkDim, maxLines = 1)
            }
            Column(Modifier.weight(1f)) {
                BasicText(stats.tasbih30.toString(), style = digits(38.sp, p.inkDim))
                Txt(s.log("log_30"), 15.sp, p.inkDim, maxLines = 1)
            }
        }
        Spacer(Modifier.height(10.dp))
        Bars(stats.tasbih14, s.rtl, Modifier.fillMaxWidth().weight(1f))
    }
}

/** Fourteen thin bars, today's lit. */
@Composable
private fun Bars(values: List<Int>, rtl: Boolean, modifier: Modifier) {
    val p = LocalPalette.current
    Canvas(modifier) {
        val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
        val step = size.width / values.size
        val w = step * 0.62f
        values.forEachIndexed { i, v ->
            val h = if (v == 0) 3.dp.toPx() else (size.height * v / max).coerceAtLeast(4.dp.toPx())
            val x = if (rtl) size.width - (i + 1) * step + (step - w) / 2 else i * step + (step - w) / 2
            val color = if (i == values.lastIndex) p.accent else if (v == 0) p.hairline else p.accent.copy(alpha = 0.5f)
            drawRoundRect(color, Offset(x, size.height - h), Size(w, h), CornerRadius(w / 3))
        }
    }
}

@Composable
private fun UsualCard(stats: AdhkarStats, s: Strings, clock24h: Boolean, modifier: Modifier) {
    val p = LocalPalette.current
    val times = stats.collections.filter { it.usual != null }.take(6)
    Column(modifier.card(p).padding(horizontal = 20.dp, vertical = 14.dp)) {
        Txt(s.log("log_usual"), 20.sp, p.ink, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        // as many rows as the tile has room for (it is shorter in landscape and with Amiri): never a clipped one
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val fit = (maxHeight / USUAL_ROW).toInt().coerceAtLeast(1)
            Column {
                if (times.isEmpty()) Txt("–", 30.sp, p.inkFaint)
                for (c in times.take(fit)) {
                    Row(Modifier.fillMaxWidth().height(USUAL_ROW), verticalAlignment = Alignment.CenterVertically) {
                        AdhkarGlyph(c.category, p.accent, Modifier.size(24.dp), second = p.accent2)
                        Spacer(Modifier.width(10.dp))
                        Txt(s[c.category.key], 17.sp, p.inkDim, Modifier.weight(1f), maxLines = 1)
                        BasicText(clock(c.usual!!, clock24h), style = digits(24.sp, p.ink, light = false), maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/** One "usually done at" row: room for a line of Amiri at 19 sp with its marks. */
private val USUAL_ROW = 34.dp

// ---------------------------------------------------------------- bits

/** Text in the right face: Amiri for Arabic, the system's otherwise. */
@Composable
private fun Txt(text: String, size: TextUnit, color: Color, modifier: Modifier = Modifier, weight: FontWeight? = null, maxLines: Int = Int.MAX_VALUE) {
    val base = Base.copy(color = color, fontSize = size, fontWeight = weight)
    // Amiri's marks reach far above and below: a roomy line, or a single line gets clipped
    val style = if (hasArabic(text)) base.arabic().copy(fontSize = size * 1.12f, lineHeight = size * 1.8f, lineHeightStyle = ArabicLine) else base
    BasicText(text, modifier, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Keeps the line's full height (no trimming), so Amiri's high marks and low tails stay inside it. */
internal val ArabicLine = androidx.compose.ui.text.style.LineHeightStyle(
    androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.None,
)

private fun percent(f: Float) = "${Math.round(f * 100)}%"

/** Minutes after midnight (≥ 1440 past it) as a clock time. */
internal fun clock(minutes: Int, clock24h: Boolean): String {
    val h = minutes / 60 % 24
    val m = minutes % 60
    return if (clock24h) "%02d:%02d".format(Locale.ROOT, h, m) else "%d:%02d".format(Locale.ROOT, if (h % 12 == 0) 12 else h % 12, m)
}
