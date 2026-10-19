package dev.framio.pomodoro.ui.insights

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.framio.pomodoro.core.history.DayBucket
import dev.framio.pomodoro.core.history.InsightsReport
import dev.framio.pomodoro.core.history.StatsRange
import dev.framio.pomodoro.core.model.Task
import dev.framio.pomodoro.core.notes.Taxonomy
import dev.framio.pomodoro.data.HistoryPort
import dev.framio.pomodoro.data.StatsView
import dev.framio.pomodoro.di.EnginePort
import dev.framio.pomodoro.platform.Logs
import dev.framio.pomodoro.ui.stateForUi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.time.Duration

/** One column of "focus per day": a day, a week or a month, with its finished sessions and focused time. */
@Immutable
data class Bar(val start: LocalDate, val sessions: Int, val focused: Duration)

/** How the columns are grouped: days for a week or 28 days, weeks for 12 weeks, months for all time. */
enum class BarUnit { Day, Week, Month }

@Immutable
data class InsightsUi(
    val loading: Boolean = true,
    val range: StatsRange = StatsRange.Month,
    val report: InsightsReport? = null,
    val bars: List<Bar> = emptyList(),
    val unit: BarUnit = BarUnit.Day,
    /** The target line for the columns (sessions a day, or a week); null for months. */
    val barTarget: Int? = null,
    val tags: Taxonomy = Taxonomy(),
    val fromCache: Boolean = false,
)

/**
 * Insights (DESIGN-history §8): the report for the chosen range, built off the main thread from the day buckets
 * (the cache's, when fresh). Logs how long the first report took (`EVENT|insights|…`) for the device measurements.
 */
class InsightsViewModel(
    private val history: HistoryPort,
    port: EnginePort,
    private val today: () -> LocalDate,
    compute: CoroutineDispatcher = Dispatchers.Default,
    private val nanos: () -> Long = System::nanoTime,
) : ViewModel() {
    private val range = MutableStateFlow(StatsRange.Month)
    private val opened = nanos()
    private var first = true

    private data class Engine(val target: Int, val tasks: List<Task>)

    val ui = combine(
        history.stats,
        range,
        port.state.map { Engine(it.settings.dailyTarget, it.tasks) }.distinctUntilChanged(),
        history.tags,
    ) { stats, r, e, tags -> map(stats, r, e, tags) }
        .onEach { u ->
            if (first && u.report != null) {
                first = false
                Logs.event("insights", "first report in ${(nanos() - opened) / 1_000_000} ms (${if (u.fromCache) "cache" else "full"}), ${u.report.started} sessions in range")
            }
        }
        .flowOn(compute)
        .stateForUi(viewModelScope, InsightsUi(tags = history.tags.value))

    init {
        history.needStats()
    }

    override fun onCleared() = history.release()

    fun setRange(r: StatsRange) {
        range.value = r
    }

    private fun map(stats: StatsView?, r: StatsRange, e: Engine, tags: Taxonomy): InsightsUi {
        if (stats == null) return InsightsUi(loading = true, range = r, tags = tags)
        val day = today()
        val report = InsightsReport.build(stats.buckets, day, r, e.target, e.tasks)
        val (unit, bars) = bars(stats.buckets, day, r)
        return InsightsUi(
            loading = false,
            range = r,
            report = report,
            bars = bars,
            unit = unit,
            barTarget = when (unit) {
                BarUnit.Day -> e.target
                BarUnit.Week -> e.target * 7
                BarUnit.Month -> null
            },
            tags = tags,
            fromCache = stats.fromCache,
        )
    }

    companion object {
        fun factory(history: HistoryPort, port: EnginePort, today: () -> LocalDate): ViewModelProvider.Factory =
            viewModelFactory { initializer { InsightsViewModel(history, port, today) } }

        /** The columns for [range]: 7 or 28 days, 12 weeks (Monday-based), or up to 24 months. */
        fun bars(buckets: Collection<DayBucket>, today: LocalDate, range: StatsRange): Pair<BarUnit, List<Bar>> {
            val byDate = buckets.associateBy { it.date }
            fun sum(from: LocalDate, days: Long): Bar {
                var n = 0
                var f = Duration.ZERO
                for (i in 0 until days) {
                    val b = byDate[from.plusDays(i)] ?: continue
                    n += b.completed
                    f += b.focused
                }
                return Bar(from, n, f)
            }
            return when (range) {
                StatsRange.Week, StatsRange.Month -> {
                    val n = range.days ?: 7
                    BarUnit.Day to (n - 1 downTo 0).map { sum(today.minusDays(it.toLong()), 1) }
                }
                StatsRange.Quarter -> {
                    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    BarUnit.Week to (11 downTo 0).map { sum(monday.minusWeeks(it.toLong()), 7) }
                }
                StatsRange.All -> {
                    val first = buckets.minOfOrNull { it.date } ?: today
                    val month = today.withDayOfMonth(1)
                    val count = ((month.year - first.year) * 12 + month.monthValue - first.monthValue + 1).coerceIn(1, 24)
                    BarUnit.Month to (count - 1 downTo 0).map {
                        val m = month.minusMonths(it.toLong())
                        sum(m, m.lengthOfMonth().toLong())
                    }
                }
            }
        }
    }
}
