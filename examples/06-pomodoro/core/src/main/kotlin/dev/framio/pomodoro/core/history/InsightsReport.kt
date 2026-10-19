package dev.framio.pomodoro.core.history

import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.Task
import dev.framio.pomodoro.core.notes.TagId
import dev.framio.pomodoro.core.stats.DayTotal
import dev.framio.pomodoro.core.stats.HourCell
import dev.framio.pomodoro.core.stats.HourHeatmap
import dev.framio.pomodoro.core.stats.TaskEstimate
import dev.framio.pomodoro.core.stats.TodayProgress
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Day buckets kept up to date incrementally. Single writer (the app's history repository, off the main thread):
 * [rebuild] once after loading, then [refresh] only the days an append or an edit touched.
 */
public class StatsIndex(public val zone: ZoneId) {
    private val days = HashMap<LocalDate, DayBucket>()

    public val buckets: Collection<DayBucket> get() = days.values

    public fun bucket(date: LocalDate): DayBucket? = days[date]

    /** Every day from scratch. */
    public fun rebuild(index: HistoryIndex) {
        days.clear()
        for (date in index.dates) days[date] = DayBucket.of(date, index.on(date), zone)
    }

    /** Recomputes [dates] from [index]: a day with no sessions left is removed. */
    public fun refresh(index: HistoryIndex, dates: Collection<LocalDate>) {
        for (date in dates) {
            val sessions = index.on(date)
            if (sessions.isEmpty()) days.remove(date) else days[date] = DayBucket.of(date, sessions, zone)
        }
    }

    /** Replaces everything with cached buckets (StatsCache), e.g. at start before the log is read. */
    public fun load(cached: Collection<DayBucket>) {
        days.clear()
        cached.forEach { days[it.date] = it }
    }
}

/** The period an Insights screen covers. [days] is null for "all time". */
public enum class StatsRange(public val days: Int?) {
    Week(7),
    Month(28),
    Quarter(84),
    All(null),
}

/** Up/down only when the change is big enough to mean something; otherwise [Same], or [Unknown] with too little data. */
public enum class Trend { Up, Down, Same, Unknown }

/** A reason tag's count in the range and in the range before it (null for "all time"). */
public data class ReasonCount(val tag: TagId, val count: Int, val previous: Int?, val trend: Trend)

/** A tag's counts per week, oldest first, for a small-multiples sparkline. */
public data class TagWeeks(val tag: TagId, val weeks: List<Int>)

/** Logged distractions against the focus they happened in, for one clock hour. */
public data class HourLoad(val hour: Int, val distractions: Int, val focused: Duration)

/** Time and counts per mode or per task, largest first. */
public data class Total(val key: String, val title: String?, val focused: Duration, val started: Int, val completed: Int)

/** Flow mode's typical block: the middle half of finished blocks (whole minutes). Only from [MIN] blocks. */
public data class FlowSummary(val blocks: Int, val median: Duration, val low: Duration, val high: Duration) {
    public companion object {
        public const val MIN: Int = 5
    }
}

/**
 * What the reader of the Insights screen sees, already decided: thresholds applied, small samples withheld, trends
 * only where the counts carry them. Sample sizes ride along everywhere so the UI can print "n = …".
 */
public data class InsightsReport(
    val range: StatsRange,
    /** First day of the range, or null for all time. */
    val from: LocalDate?,
    val to: LocalDate,
    val today: TodayProgress,
    /** The 7 days ending today, oldest first. */
    val week: List<DayTotal>,
    /** Monday-to-today of this week against target × days so far. */
    val weekSoFar: TodayProgress,
    /** "N of the last 7 days" and "N of the last 28 days" with a finished session. */
    val activeDays7: Int,
    val activeDays28: Int,
    val focused: Duration,
    val started: Int,
    val completed: Int,
    val daysWithFocus: Int,
    /** Count per outcome, in [Outcome] order. */
    val outcomes: List<Pair<Outcome, Int>>,
    val distractions: Int,
    val sessionsDistracted: Int,
    /** Distractions per hour of focus; null below [MIN_FOCUS_FOR_RATE] of focus. */
    val distractionsPerHour: Double?,
    val reasons: List<ReasonCount>,
    val endReasons: List<ReasonCount>,
    /** The top three reasons per week over the last [TREND_WEEKS] weeks. */
    val reasonWeeks: List<TagWeeks>,
    val hours: List<HourLoad>,
    /** Distractions recalled at the wrap-up or later: real, but not placed at an hour. */
    val unplaced: Int,
    val rated: Int,
    /** Mean rating by starting hour, or null below [HourHeatmap.MIN_RATED] rated sessions; cells under [MIN_CELL] have no mean. */
    val ratingByHour: List<HourCell>?,
    val modes: List<Total>,
    /** The [TOP_TASKS] biggest tasks, then everything else folded into one row with key [OTHER]. */
    val tasks: List<Total>,
    val estimates: List<TaskEstimate>,
    val flow: FlowSummary?,
) {
    public companion object {
        public val MIN_FOCUS_FOR_RATE: Duration = 5.hours
        public const val MIN_CELL: Int = 3
        public const val TREND_WEEKS: Int = 8
        public const val TOP_TASKS: Int = 7
        public const val OTHER: String = "__other"

        /** A change counts only if the larger count is at least this… */
        public const val TREND_MIN_COUNT: Int = 5

        /** …and the counts differ by at least 3 and by a quarter of the larger one. */
        public fun trendOf(now: Int, before: Int?): Trend {
            if (before == null) return Trend.Unknown
            val big = maxOf(now, before)
            if (big < TREND_MIN_COUNT) return Trend.Unknown
            val diff = now - before
            if (abs(diff) < 3 || abs(diff) * 4 < big) return Trend.Same
            return if (diff > 0) Trend.Up else Trend.Down
        }

        /**
         * Builds the report from [buckets] (a [StatsIndex]'s). [tasks] are the engine's tasks, for estimates (which
         * count all time, not the range: an estimate is about the whole task).
         */
        public fun build(
            buckets: Collection<DayBucket>,
            today: LocalDate,
            range: StatsRange,
            target: Int,
            tasks: List<Task> = emptyList(),
            firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
        ): InsightsReport {
            val byDate = buckets.associateBy { it.date }
            val from = range.days?.let { today.minusDays(it - 1L) }
            val inRange = buckets.filter { (from == null || !it.date.isBefore(from)) && !it.date.isAfter(today) }
            val before = range.days?.let { n ->
                val end = today.minusDays(n.toLong())
                val start = end.minusDays(n - 1L)
                buckets.filter { !it.date.isBefore(start) && !it.date.isAfter(end) }
            }

            val todayB = byDate[today]
            val todayP = TodayProgress(todayB?.completed ?: 0, target, todayB?.focused ?: Duration.ZERO)
            val week = (6 downTo 0).map { back ->
                val d = today.minusDays(back.toLong())
                val b = byDate[d]
                DayTotal(d, b?.focused ?: Duration.ZERO, b?.completed ?: 0)
            }
            val monday = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
            val soFar = buckets.filter { !it.date.isBefore(monday) && !it.date.isAfter(today) }
            val daysSoFar = (today.toEpochDay() - monday.toEpochDay() + 1).toInt()
            val weekSoFar = TodayProgress(soFar.sumOf { it.completed }, target * daysSoFar, soFar.fold(Duration.ZERO) { a, b -> a + b.focused })

            val focused = inRange.fold(Duration.ZERO) { a, b -> a + b.focused }
            val outcomes = Outcome.entries.map { o -> o to inRange.sumOf { it.outcome(o) } }
            val distractions = inRange.sumOf { it.distractions }

            val hoursFocus = LongArray(24)
            val hoursD = IntArray(24)
            val rated = IntArray(24)
            val rSum = IntArray(24)
            inRange.forEach { b ->
                for (h in 0 until 24) {
                    hoursFocus[h] += b.focusByHour[h]
                    hoursD[h] += b.distractionsByHour[h]
                    rated[h] += b.ratedByHour[h]
                    rSum[h] += b.ratingSumByHour[h]
                }
            }
            val ratedTotal = rated.sum()
            val ratingByHour = if (ratedTotal < HourHeatmap.MIN_RATED) {
                null
            } else {
                (0 until 24).map { h -> HourCell(h, rated[h], if (rated[h] >= MIN_CELL) rSum[h].toFloat() / rated[h] else null) }
            }

            val reasons = reasonCounts(inRange, before) { it.tags }
            val endReasons = reasonCounts(inRange, before) { it.endTags }
            val weekStarts = (TREND_WEEKS - 1 downTo 0).map { today.minusDays(7L * it + 6) }
            val reasonWeeks = reasons.take(3).map { rc ->
                TagWeeks(
                    rc.tag,
                    weekStarts.map { start ->
                        (0L until 7L).sumOf { d -> byDate[start.plusDays(d)]?.tags?.firstOrNull { it.tag == rc.tag.value }?.count ?: 0 }
                    },
                )
            }

            val flowBlocks = inRange.flatMap { it.flowBlocks }.sorted()
            val flow = if (flowBlocks.size < FlowSummary.MIN) {
                null
            } else {
                FlowSummary(flowBlocks.size, flowBlocks.pct(50).roundMin(), flowBlocks.pct(25).roundMin(), flowBlocks.pct(75).roundMin())
            }

            val allTaskDone = HashMap<String, Int>()
            buckets.forEach { b -> b.tasks.forEach { t -> allTaskDone[t.key] = (allTaskDone[t.key] ?: 0) + t.completed } }
            val estimates = tasks.mapNotNull { t -> t.estimateSessions?.let { TaskEstimate(t.id, t.title, it, allTaskDone[t.id.value] ?: 0) } }

            val taskTotals = totals(inRange.flatMap { it.tasks })
            val folded = if (taskTotals.size <= TOP_TASKS + 1) {
                taskTotals
            } else {
                val rest = taskTotals.drop(TOP_TASKS)
                taskTotals.take(TOP_TASKS) + Total(
                    OTHER, null, rest.fold(Duration.ZERO) { a, t -> a + t.focused }, rest.sumOf { it.started }, rest.sumOf { it.completed },
                )
            }

            return InsightsReport(
                range = range,
                from = from,
                to = today,
                today = todayP,
                week = week,
                weekSoFar = weekSoFar,
                activeDays7 = activeDays(byDate, today, 7),
                activeDays28 = activeDays(byDate, today, 28),
                focused = focused,
                started = inRange.sumOf { it.started },
                completed = inRange.sumOf { it.completed },
                daysWithFocus = inRange.sumOf { it.daysWithFocus },
                outcomes = outcomes,
                distractions = distractions,
                sessionsDistracted = inRange.sumOf { it.sessionsDistracted },
                distractionsPerHour = if (focused < MIN_FOCUS_FOR_RATE) null else distractions / (focused / 1.hours),
                reasons = reasons,
                endReasons = endReasons,
                reasonWeeks = reasonWeeks,
                hours = (0 until 24).map { HourLoad(it, hoursD[it], hoursFocus[it].seconds) },
                unplaced = distractions - hoursD.sum(),
                rated = ratedTotal,
                ratingByHour = ratingByHour,
                modes = totals(inRange.flatMap { it.modes }),
                tasks = folded,
                estimates = estimates,
                flow = flow,
            )
        }

        private fun activeDays(byDate: Map<LocalDate, DayBucket>, today: LocalDate, window: Int): Int =
            (0 until window).count { (byDate[today.minusDays(it.toLong())]?.completed ?: 0) > 0 }

        private fun reasonCounts(inRange: List<DayBucket>, before: List<DayBucket>?, pick: (DayBucket) -> List<TagCount>): List<ReasonCount> {
            val now = sum(inRange.flatMap(pick))
            val prev = before?.let { sum(it.flatMap(pick)) }
            return now.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { (tag, n) ->
                    val p = prev?.let { it[tag] ?: 0 }
                    ReasonCount(TagId(tag), n, p, trendOf(n, p))
                }
        }

        private fun sum(counts: List<TagCount>): Map<String, Int> {
            val m = HashMap<String, Int>()
            counts.forEach { m[it.tag] = (m[it.tag] ?: 0) + it.count }
            return m
        }

        private fun totals(tallies: List<Tally>): List<Total> {
            val m = LinkedHashMap<String, Tally>()
            tallies.forEach { t -> m[t.key] = m[t.key]?.plus(t) ?: t }
            return m.values.map { Total(it.key, it.title, it.focused, it.started, it.completed) }
                .sortedWith(compareByDescending<Total> { it.focused }.thenBy { it.key })
        }

        /** Nearest-rank percentile of a sorted list. */
        private fun List<Duration>.pct(p: Int): Duration = this[(ceil(p / 100.0 * size).toInt() - 1).coerceIn(0, size - 1)]

        private fun Duration.roundMin(): Duration = ((inWholeSeconds + 30) / 60).minutes
    }
}
