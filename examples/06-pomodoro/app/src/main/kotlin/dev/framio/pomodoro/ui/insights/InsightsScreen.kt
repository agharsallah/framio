package dev.framio.pomodoro.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.framio.pomodoro.R
import dev.framio.pomodoro.core.history.InsightsReport
import dev.framio.pomodoro.core.history.StatsRange
import dev.framio.pomodoro.core.history.Trend
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.notes.TagIcon
import dev.framio.pomodoro.core.notes.Taxonomy
import dev.framio.pomodoro.data.HistoryPort
import dev.framio.pomodoro.di.EnginePort
import dev.framio.pomodoro.ui.LocalLandscape
import dev.framio.pomodoro.ui.components.Chip
import dev.framio.pomodoro.ui.components.QuietText
import dev.framio.pomodoro.ui.components.QuietTextButton
import dev.framio.pomodoro.ui.components.SkeletonCards
import dev.framio.pomodoro.ui.faces.TargetRing
import dev.framio.pomodoro.ui.notes.TagGlyph
import dev.framio.pomodoro.ui.notes.durationText
import dev.framio.pomodoro.ui.notes.outcomeText
import dev.framio.pomodoro.ui.theme.ChartColors
import dev.framio.pomodoro.ui.theme.QuietTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun InsightsRoute(
    history: HistoryPort,
    port: EnginePort,
    today: () -> LocalDate,
    onClose: () -> Unit,
    onHistory: () -> Unit,
    modifier: Modifier = Modifier,
    vm: InsightsViewModel = viewModel(factory = InsightsViewModel.factory(history, port, today)),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    InsightsScreen(ui, vm::setRange, onClose, onHistory, modifier)
}

/**
 * Insights (DESIGN-history §8): informational and forgiving. Rings against the target, "N of the last 7 days" (never a
 * streak), how sessions ended, what pulled you away and when, focus by hour only from 20 rated sessions, estimates
 * against actuals, time per task and mode. Every block says its n; small samples are withheld. Static draws; a still
 * skeleton while the report is built, then the rows arrive one per frame.
 */
@Composable
fun InsightsScreen(
    ui: InsightsUi,
    onRange: (StatsRange) -> Unit,
    onClose: () -> Unit,
    onHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = QuietTheme.colors
    val landscape = LocalLandscape.current
    Column(modifier.fillMaxSize().background(colors.background).padding(start = QuietTheme.spacing.gutter, end = QuietTheme.spacing.gutter, top = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            QuietText(stringResource(R.string.insights), QuietTheme.type.title, maxLines = 1)
            Spacer(Modifier.width(24.dp))
            if (landscape) RangeChips(ui.range, onRange)
            Spacer(Modifier.weight(1f))
            QuietTextButton(stringResource(R.string.history_open), onHistory)
            QuietTextButton(stringResource(R.string.close), onClose, color = colors.ink)
        }
        if (!landscape) {
            Spacer(Modifier.height(12.dp))
            RangeChips(ui.range, onRange)
        }
        val report = ui.report
        if (report == null) {
            // The screen is there at once; the report is built off the main thread (seconds the first time, LOG)
            SkeletonCards(stringResource(R.string.loading), landscape, Modifier.padding(top = 20.dp))
            return@Column
        }
        // The one motion: the rows replace the skeleton one per frame, top first (static afterwards). Composing the
        // headline and the first cards in one frame was the opening's long frame (LOG Phase B); a fade on top re-rendered
        // the whole report through an offscreen layer on every frame of it (LOG M6)
        val reduce = QuietTheme.motion.reduce
        var rows by remember { mutableIntStateOf(if (reduce) ROWS else 1) }
        LaunchedEffect(Unit) {
            while (rows < ROWS) {
                withFrameNanos { }
                rows++
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 20.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            // Lazy: only the cards on screen are composed (the first frame on the Mali was the cost, LOG)
            item(key = "head") { Headline(report) }
            if (rows > 1) item(key = "row1") { TwoCards(landscape, { PerDay(ui) }, { Outcomes(report) }) }
            if (rows > 2) item(key = "row2") { TwoCards(landscape, { Reasons(report, ui.tags) }, { Hours(report) }) }
            if (rows > 3) item(key = "row3") { TwoCards(landscape, { Rating(report) }, { Estimates(report) }) }
            if (rows > 4) item(key = "row4") { TwoCards(landscape, { Tasks(report) }, { Modes(report) }) }
            if (ui.fromCache && rows >= ROWS) {
                item(key = "cache") {
                    QuietText(stringResource(R.string.insights_from_cache), QuietTheme.type.caption, color = colors.inkDim, maxLines = 1)
                }
            }
        }
    }
}

/** The headline and the four rows of cards. */
private const val ROWS = 5

@Composable
private fun RangeChips(range: StatsRange, onRange: (StatsRange) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((r, label) in listOf(StatsRange.Week to R.string.range_week_short, StatsRange.Month to R.string.range_month, StatsRange.Quarter to R.string.range_quarter, StatsRange.All to R.string.range_all)) {
            Chip(stringResource(label), selected = r == range, accent = QuietTheme.colors.ink, onClick = { onRange(r) })
        }
    }
}

/** Two cards side by side in landscape, stacked standing. */
@Composable
private fun TwoCards(landscape: Boolean, left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit) {
    if (landscape) {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Card(Modifier.weight(1f), left)
            Card(Modifier.weight(1f), right)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Card(Modifier.fillMaxWidth(), left)
            Card(Modifier.fillMaxWidth(), right)
        }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(QuietTheme.colors.surface).padding(24.dp), content = content)
}

@Composable
private fun Title(text: String, n: Int? = null) {
    Row(Modifier.padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        QuietText(text, QuietTheme.type.label, maxLines = 1, modifier = Modifier.weight(1f))
        if (n != null) QuietText(stringResource(R.string.n_equals, n), QuietTheme.type.caption, color = QuietTheme.colors.inkDim, maxLines = 1)
    }
}

/** Today's ring, the week's ring, consistency, and the range's totals. */
@Composable
private fun Headline(r: InsightsReport, modifier: Modifier = Modifier) {
    val colors = QuietTheme.colors
    val chart = ChartColors.of(colors)
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(48.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        RingStat(r.today.fraction, stringResource(R.string.today_of, r.today.sessions, r.today.target), durationText(r.today.focused), chart)
        RingStat(r.weekSoFar.fraction, stringResource(R.string.week_of, r.weekSoFar.sessions, r.weekSoFar.target), durationText(r.weekSoFar.focused), chart)
        Column {
            QuietText(stringResource(R.string.active_7, r.activeDays7), QuietTheme.type.title, maxLines = 1)
            QuietText(stringResource(R.string.active_28, r.activeDays28), QuietTheme.type.caption, color = colors.inkDim, maxLines = 1)
        }
        Column {
            QuietText(stringResource(R.string.range_totals, r.completed, durationText(r.focused)), QuietTheme.type.title, maxLines = 1)
            QuietText(stringResource(R.string.range_started, r.started, r.daysWithFocus), QuietTheme.type.caption, color = colors.inkDim, maxLines = 1)
        }
    }
}

@Composable
private fun RingStat(fraction: Float, value: String, detail: String, chart: ChartColors, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // Goal gradient: the track is a lighter step of the same hue
        TargetRing(fraction, chart.data, chart.data.copy(alpha = 0.25f), Modifier.size(84.dp))
        Spacer(Modifier.width(18.dp))
        Column {
            QuietText(value, QuietTheme.type.title, maxLines = 1)
            QuietText(detail, QuietTheme.type.caption, color = QuietTheme.colors.inkDim, maxLines = 1)
        }
    }
}


@Composable
private fun ColumnScope.PerDay(ui: InsightsUi) {
    val colors = QuietTheme.colors
    val chart = ChartColors.of(colors)
    Title(
        stringResource(
            when (ui.unit) {
                BarUnit.Day -> R.string.focus_per_day
                BarUnit.Week -> R.string.focus_per_week
                BarUnit.Month -> R.string.focus_per_month
            },
        ),
    )
    Columns(ui.bars.map { it.sessions }, ui.barTarget, chart.data, colors.line, Modifier.fillMaxWidth().height(150.dp))
    Spacer(Modifier.height(8.dp))
    Row {
        val month = ui.unit == BarUnit.Month
        val fmt = remember(month) { DateTimeFormatter.ofPattern(if (month) "MMM yy" else "d MMM") }
        QuietText(ui.bars.firstOrNull()?.start?.format(fmt).orEmpty(), QuietTheme.type.caption, color = colors.inkDim, maxLines = 1, modifier = Modifier.weight(1f))
        ui.barTarget?.let { QuietText(stringResource(R.string.target_line, it), QuietTheme.type.caption, color = colors.inkDim, maxLines = 1, modifier = Modifier.weight(1f), align = TextAlign.Center) }
        QuietText(ui.bars.lastOrNull()?.start?.format(fmt).orEmpty(), QuietTheme.type.caption, color = colors.inkDim, maxLines = 1, modifier = Modifier.weight(1f), align = TextAlign.End)
    }
}

@Composable
private fun ColumnScope.Outcomes(r: InsightsReport) {
    val colors = QuietTheme.colors
    val chart = ChartColors.of(colors)
    val n = r.outcomes.sumOf { it.second }
    Title(stringResource(R.string.outcomes_title), n)
    val colorOf = { o: Outcome ->
        when (o) {
            Outcome.Done -> chart.outcomes[0]
            Outcome.Partly -> chart.outcomes[1]
            Outcome.NotYet -> chart.outcomes[2]
            Outcome.Unrated -> chart.outcomes[3]
            Outcome.EndedEarly -> chart.neutral
            Outcome.Interrupted -> chart.neutralDeep
        }
    }
    StackedBar(r.outcomes.map { (o, c) -> c to colorOf(o) }, colors.line, Modifier.fillMaxWidth().height(28.dp))
    Spacer(Modifier.height(16.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        r.outcomes.forEach { (o, c) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(colorOf(o)))
                Spacer(Modifier.width(8.dp))
                QuietText(stringResource(R.string.legend_count, outcomeText(o), c), QuietTheme.type.caption, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ColumnScope.Reasons(r: InsightsReport, tags: Taxonomy) {
    val colors = QuietTheme.colors
    val chart = ChartColors.of(colors)
    Title(stringResource(R.string.reasons_title), r.sessionsDistracted.takeIf { r.distractions > 0 })
    if (r.reasons.isEmpty()) {
        QuietText(stringResource(R.string.reasons_none), QuietTheme.type.body, color = colors.inkDim)
        return
    }
    val rate = r.distractionsPerHour
    QuietText(
        if (rate != null) {
            stringResource(R.string.reasons_rate, r.distractions, durationText(r.focused), oneDecimal(rate))
        } else {
            stringResource(R.string.reasons_plain, r.distractions)
        },
        QuietTheme.type.caption,
        color = colors.inkDim,
        maxLines = 2,
    )
    Spacer(Modifier.height(12.dp))
    val max = r.reasons.maxOf { it.count }.coerceAtLeast(1)
    r.reasons.take(TOP_REASONS).forEach { rc ->
        val t = tags.tag(rc.tag)
        Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(chart.tag(t?.hue ?: -1)))
            Spacer(Modifier.width(8.dp))
            TagGlyph(t?.icon ?: TagIcon.Dots, colors.ink, colors.surface, Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            QuietText(tags.label(rc.tag), QuietTheme.type.caption, maxLines = 1, modifier = Modifier.width(150.dp))
            HBar(rc.count.toFloat() / max, chart.data, colors.line, Modifier.weight(1f).height(12.dp))
            QuietText("${rc.count}", QuietTheme.type.caption, maxLines = 1, modifier = Modifier.width(48.dp), align = TextAlign.End)
            QuietText(trendText(rc.trend, rc.previous), QuietTheme.type.caption, color = colors.inkDim, maxLines = 1, modifier = Modifier.width(150.dp), align = TextAlign.End)
        }
    }
    if (r.reasonWeeks.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        QuietText(stringResource(R.string.trend_weeks), QuietTheme.type.caption, color = colors.inkDim, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            r.reasonWeeks.forEach { w ->
                Column(Modifier.weight(1f)) {
                    QuietText(tags.label(w.tag), QuietTheme.type.caption, maxLines = 1)
                    Sparkline(w.weeks, chart.data, colors.surface, Modifier.fillMaxWidth().height(44.dp))
                }
            }
        }
    }
}

@Composable
@androidx.compose.runtime.ReadOnlyComposable
private fun trendText(t: Trend, previous: Int?): String = when (t) {
    Trend.Up -> stringResource(R.string.trend_up, previous ?: 0)
    Trend.Down -> stringResource(R.string.trend_down, previous ?: 0)
    Trend.Same -> stringResource(R.string.trend_same)
    Trend.Unknown -> "—"
}

@Composable
private fun ColumnScope.Hours(r: InsightsReport) {
    val colors = QuietTheme.colors
    val chart = ChartColors.of(colors)
    Title(stringResource(R.string.hours_title), r.distractions - r.unplaced)
    HourColumns(
        r.hours.map { it.distractions },
        r.hours.map { it.focused.inWholeMinutes.toFloat() },
        chart.data,
        { chart.sequential(it, colors.surface) },
        colors.line,
        Modifier.fillMaxWidth().height(140.dp),
    )
    Spacer(Modifier.height(6.dp))
    HourAxis()
    Spacer(Modifier.height(8.dp))
    QuietText(stringResource(R.string.hours_caption, r.unplaced), QuietTheme.type.caption, color = colors.inkDim, maxLines = 3)
}

@Composable
private fun HourAxis(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth()) {
        listOf("0", "6", "12", "18", "24").forEachIndexed { i, h ->
            QuietText(h, QuietTheme.type.caption, color = QuietTheme.colors.inkDim, maxLines = 1, modifier = Modifier.weight(1f), align = if (i == 0) TextAlign.Start else if (i == 4) TextAlign.End else TextAlign.Center)
        }
    }
}

@Composable
private fun ColumnScope.Rating(r: InsightsReport) {
    val colors = QuietTheme.colors
    val chart = ChartColors.of(colors)
    Title(stringResource(R.string.rating_title), r.rated)
    val cells = r.ratingByHour
    if (cells == null) {
        QuietText(stringResource(R.string.rating_needed, r.rated), QuietTheme.type.body, color = colors.inkDim, maxLines = 2)
        return
    }
    HeatCells(cells.map { it.mean }, { chart.sequential(it, colors.surface) }, colors.line, Modifier.fillMaxWidth().height(44.dp))
    Spacer(Modifier.height(6.dp))
    HourAxis()
    Spacer(Modifier.height(8.dp))
    QuietText(stringResource(R.string.rating_caption, r.rated), QuietTheme.type.caption, color = colors.inkDim, maxLines = 3)
}

@Composable
private fun ColumnScope.Estimates(r: InsightsReport) {
    val colors = QuietTheme.colors
    val chart = ChartColors.of(colors)
    Title(stringResource(R.string.estimates_title), r.estimates.size.takeIf { it > 0 })
    if (r.estimates.isEmpty()) {
        QuietText(stringResource(R.string.estimates_none), QuietTheme.type.body, color = colors.inkDim)
        return
    }
    val max = r.estimates.maxOf { maxOf(it.estimate, it.actual) }
    r.estimates.take(TOP_ROWS).forEach { e ->
        Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietText(e.title, QuietTheme.type.caption, maxLines = 1, modifier = Modifier.width(180.dp))
            Dumbbell(e.estimate, e.actual, max, chart.data, colors.surface, Modifier.weight(1f).height(24.dp))
            QuietText(stringResource(R.string.estimate_line, e.estimate, e.actual), QuietTheme.type.caption, color = colors.inkDim, maxLines = 1, modifier = Modifier.width(90.dp), align = TextAlign.End)
        }
    }
}

@Composable
private fun ColumnScope.Tasks(r: InsightsReport) {
    Title(stringResource(R.string.tasks_title), r.tasks.sumOf { it.started }.takeIf { it > 0 })
    TotalRows(r.tasks.map { (if (it.key == InsightsReport.OTHER) null else it.title) to it.focused }, stringResource(R.string.tasks_other))
}

@Composable
private fun ColumnScope.Modes(r: InsightsReport) {
    val colors = QuietTheme.colors
    Title(stringResource(R.string.modes_title), r.modes.sumOf { it.started }.takeIf { it > 0 })
    TotalRows(r.modes.map { (it.title ?: it.key) to it.focused }, "")
    r.flow?.let { f ->
        Spacer(Modifier.height(10.dp))
        QuietText(
            stringResource(R.string.flow_typical, durationText(f.median), durationText(f.low), durationText(f.high), f.blocks),
            QuietTheme.type.caption,
            color = colors.inkDim,
            maxLines = 2,
        )
    }
}

@Composable
private fun TotalRows(rows: List<Pair<String?, kotlin.time.Duration>>, other: String) {
    val colors = QuietTheme.colors
    val chart = ChartColors.of(colors)
    val max = rows.maxOfOrNull { it.second.inWholeMinutes }?.coerceAtLeast(1) ?: 1
    rows.take(TOP_ROWS + 1).forEach { (label, d) ->
        Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietText(label ?: other, QuietTheme.type.caption, maxLines = 1, modifier = Modifier.width(180.dp))
            HBar(d.inWholeMinutes.toFloat() / max, chart.data, colors.line, Modifier.weight(1f).height(12.dp))
            QuietText(durationText(d), QuietTheme.type.caption, maxLines = 1, modifier = Modifier.width(130.dp), align = TextAlign.End)
        }
    }
}

private const val TOP_REASONS = 6
private const val TOP_ROWS = 7

/** "1.4": one decimal, no locale lookup (a rate is a rough figure; more decimals would be fake precision). */
private fun oneDecimal(v: Double): String {
    val tenths = kotlin.math.round(v * 10).toLong()
    return "${tenths / 10}.${tenths % 10}"
}
