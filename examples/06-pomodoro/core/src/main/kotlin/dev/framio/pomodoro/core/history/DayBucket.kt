package dev.framio.pomodoro.core.history

import dev.framio.pomodoro.core.model.LocalDateAsString
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.notes.Entry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Time and counts for one key (a mode id or a task id) within a day. [title] is the latest name seen. */
@Serializable
public data class Tally(
    @SerialName("k") val key: String,
    @SerialName("title") val title: String? = null,
    @SerialName("focused") val focused: Duration = Duration.ZERO,
    @SerialName("started") val started: Int = 0,
    @SerialName("completed") val completed: Int = 0,
) {
    public operator fun plus(o: Tally): Tally =
        Tally(key, o.title ?: title, focused + o.focused, started + o.started, completed + o.completed)
}

/** How often one reason tag came up. */
@Serializable
public data class TagCount(@SerialName("tag") val tag: String, @SerialName("n") val count: Int)

/**
 * Everything Insights needs about one local day, so a report over any range is a sum of days, and an edit only
 * recomputes the days it touched. Serializable: the app keeps these in a cache file so Insights opens without
 * reading the whole log (DESIGN-history §9).
 *
 * Hour lists have 24 cells by local clock hour. [focusByHour] spreads each session's focused time over the clock
 * hours from its start (pauses ignored: an approximation, good to the hour).
 */
@Serializable
public data class DayBucket(
    @Serializable(with = LocalDateAsString::class)
    @SerialName("date") val date: LocalDate,
    @SerialName("focused") val focused: Duration = Duration.ZERO,
    @SerialName("started") val started: Int = 0,
    @SerialName("completed") val completed: Int = 0,
    @SerialName("outcomes") val outcomes: List<Int> = List(Outcome.entries.size) { 0 },
    @SerialName("modes") val modes: List<Tally> = emptyList(),
    @SerialName("tasks") val tasks: List<Tally> = emptyList(),
    @SerialName("tags") val tags: List<TagCount> = emptyList(),
    @SerialName("endTags") val endTags: List<TagCount> = emptyList(),
    @SerialName("distractions") val distractions: Int = 0,
    @SerialName("distracted") val sessionsDistracted: Int = 0,
    @SerialName("dByHour") val distractionsByHour: List<Int> = ZERO24,
    /** Seconds of focus per clock hour. */
    @SerialName("fByHour") val focusByHour: List<Long> = ZERO24L,
    @SerialName("rated") val rated: Int = 0,
    @SerialName("rByHour") val ratedByHour: List<Int> = ZERO24,
    @SerialName("rSumByHour") val ratingSumByHour: List<Int> = ZERO24,
    @SerialName("flow") val flowBlocks: List<Duration> = emptyList(),
    /** A month folded into one bucket (dated the 1st): how many of its days had a session. Null for a plain day. */
    @SerialName("active") val activeDays: Int? = null,
) {
    public fun outcome(o: Outcome): Int = outcomes.getOrElse(o.ordinal) { 0 }

    /** Days with a session in this bucket: 0 or 1 for a day, up to 31 for a folded month. */
    val daysWithFocus: Int get() = activeDays ?: if (started > 0) 1 else 0

    /** This bucket and [o] as one, dated [date] (folding days into a month). */
    public fun merge(o: DayBucket, date: LocalDate): DayBucket = DayBucket(
        date = date,
        focused = focused + o.focused,
        started = started + o.started,
        completed = completed + o.completed,
        outcomes = outcomes.zip(o.outcomes, Int::plus),
        modes = mergeTallies(modes, o.modes),
        tasks = mergeTallies(tasks, o.tasks),
        tags = mergeCounts(tags, o.tags),
        endTags = mergeCounts(endTags, o.endTags),
        distractions = distractions + o.distractions,
        sessionsDistracted = sessionsDistracted + o.sessionsDistracted,
        distractionsByHour = distractionsByHour.zip(o.distractionsByHour, Int::plus),
        focusByHour = focusByHour.zip(o.focusByHour, Long::plus),
        rated = rated + o.rated,
        ratedByHour = ratedByHour.zip(o.ratedByHour, Int::plus),
        ratingSumByHour = ratingSumByHour.zip(o.ratingSumByHour, Int::plus),
        flowBlocks = flowBlocks + o.flowBlocks,
        activeDays = daysWithFocus + o.daysWithFocus,
    )

    public companion object {
        /**
         * Days the cache keeps as days: Insights' longest day-based range (84 days) and the range before it, which its
         * trends compare against, plus a margin. Older days are folded into months.
         */
        public const val KEEP_DAYS: Long = 180

        /**
         * The cache's compact form (DESIGN-history §10): days before [cutoff] folded into one bucket per month, dated the
         * 1st; days from [cutoff] on kept as they are. Insights' day-based ranges (≤ 84 days) must lie after [cutoff];
         * "all time" only sums. Pass the 1st of a month so no month is split.
         */
        public fun foldMonths(days: Collection<DayBucket>, cutoff: LocalDate): List<DayBucket> {
            val kept = ArrayList<DayBucket>()
            val months = LinkedHashMap<LocalDate, DayBucket>()
            for (b in days.sortedBy { it.date }) {
                if (!b.date.isBefore(cutoff)) {
                    kept += b
                    continue
                }
                val m = b.date.withDayOfMonth(1)
                val folded = b.copy(date = m, activeDays = b.daysWithFocus)
                months[m] = months[m]?.merge(b, m) ?: folded
            }
            return months.values.toList() + kept
        }

        private fun mergeTallies(a: List<Tally>, b: List<Tally>): List<Tally> {
            val m = LinkedHashMap<String, Tally>()
            (a + b).forEach { t -> m[t.key] = m[t.key]?.plus(t) ?: t }
            return m.values.toList()
        }

        private fun mergeCounts(a: List<TagCount>, b: List<TagCount>): List<TagCount> {
            val m = LinkedHashMap<String, Int>()
            (a + b).forEach { c -> m[c.tag] = (m[c.tag] ?: 0) + c.count }
            return m.map { TagCount(it.key, it.value) }
        }

        internal val ZERO24: List<Int> = List(24) { 0 }
        internal val ZERO24L: List<Long> = List(24) { 0L }

        /** The bucket for [date] from that day's sessions. */
        public fun of(date: LocalDate, sessions: List<SessionView>, zone: ZoneId): DayBucket {
            var focused = Duration.ZERO
            var completed = 0
            val outcomes = IntArray(Outcome.entries.size)
            val modes = LinkedHashMap<String, Tally>()
            val tasks = LinkedHashMap<String, Tally>()
            val tags = LinkedHashMap<String, Int>()
            val endTags = LinkedHashMap<String, Int>()
            var distractions = 0
            var distracted = 0
            val dByHour = IntArray(24)
            val fByHour = LongArray(24)
            var rated = 0
            val rByHour = IntArray(24)
            val rSum = IntArray(24)
            val flow = ArrayList<Duration>()
            // Oldest first, so the latest titles win.
            for (s in sessions.asReversed()) {
                val r = s.record
                val done = r.outcome.completed
                focused += r.focused
                if (done) completed++
                outcomes[r.outcome.ordinal]++
                val one = Tally("", null, r.focused, 1, if (done) 1 else 0)
                modes.add(one.copy(key = r.mode.value, title = r.modeName))
                r.task?.let { t -> tasks.add(one.copy(key = t.value, title = r.taskTitle ?: r.intention)) }
                if (done && r.planned == null) flow += r.focused
                val start = r.startWallMs
                if (start != null) spread(start, r.focused, zone, fByHour)
                if (r.rating != null && start != null) {
                    val h = hourOf(start, zone)
                    rated++
                    rByHour[h]++
                    rSum[h] += r.rating
                }
                var any = false
                for (e in s.entries) {
                    when (e) {
                        is Entry.Distraction -> {
                            distractions++
                            any = true
                            e.tags.distinct().forEach { tags.bump(it.value) }
                            val at = e.at
                            if (start != null && at != null) dByHour[hourOf(start + at.inWholeMilliseconds, zone)]++
                        }
                        is Entry.EndReason -> e.tags.distinct().forEach { endTags.bump(it.value) }
                        else -> Unit
                    }
                }
                if (any) distracted++
            }
            return DayBucket(
                date, focused, sessions.size, completed, outcomes.toList(),
                modes.values.toList(), tasks.values.toList(),
                tags.map { TagCount(it.key, it.value) }, endTags.map { TagCount(it.key, it.value) },
                distractions, distracted, dByHour.toList(), fByHour.toList(),
                rated, rByHour.toList(), rSum.toList(), flow,
            )
        }

        // Not Map.merge: that is API 24 on Android (PLAN §2, api23Guard).
        private fun MutableMap<String, Tally>.add(t: Tally) {
            put(t.key, get(t.key)?.plus(t) ?: t)
        }

        private fun MutableMap<String, Int>.bump(key: String) {
            put(key, (get(key) ?: 0) + 1)
        }

        internal fun hourOf(wallMs: Long, zone: ZoneId): Int = Instant.ofEpochMilli(wallMs).atZone(zone).hour

        /** Adds [focused] seconds into the clock hours from [startMs] on, splitting at each hour boundary (DST-safe). */
        internal fun spread(startMs: Long, focused: Duration, zone: ZoneId, into: LongArray) {
            var t = startMs
            var left = focused.inWholeMilliseconds
            var guard = 0
            while (left > 0 && guard++ < 48) {
                val here = Instant.ofEpochMilli(t).atZone(zone)
                val next = here.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant().toEpochMilli()
                val piece = minOf(left, next - t)
                into[here.hour] += piece.milliseconds.inWholeSeconds
                left -= piece
                t += piece
            }
        }
    }
}
