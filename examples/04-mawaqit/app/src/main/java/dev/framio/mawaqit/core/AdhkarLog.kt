package dev.framio.mawaqit.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * One session on one day: [done] of [total] adhkar said their full number of times, [said] of [reps] repetitions
 * (each dhikr counted up to its number), whether it was [complete] and when: [at] is minutes after that day's
 * midnight on the wall clock (1440 and more past midnight, for a before-sleep session that crossed it), null when
 * not complete or not known (days migrated from before the log had only "completed"). [part] is the mean of each
 * dhikr's own progress (a 100× tasbih half said is half of one dhikr, not of the whole session), in thousandths.
 */
data class SessionLog(val done: Int, val total: Int, val said: Int, val reps: Int, val complete: Boolean, val at: Int? = null, val part: Int? = null) {
    /** 0–1: how much of it was said, each dhikr weighing the same; a complete session is 1 even when its counts are unknown. */
    val share: Float get() = when {
        complete -> 1f
        part != null -> (part / 1000f).coerceIn(0f, 1f)
        total > 0 -> (done.toFloat() / total).coerceIn(0f, 1f)
        reps > 0 -> (said.toFloat() / reps).coerceIn(0f, 1f)
        else -> 0f
    }

    fun toJson(): JSONArray = JSONArray().put(done).put(total).put(said).put(reps).put(if (complete) 1 else 0).put(at ?: -1).put(part ?: -1)

    companion object {
        fun fromJson(a: JSONArray?): SessionLog? {
            if (a == null || a.length() < 5) return null
            return SessionLog(a.optInt(0).coerceAtLeast(0), a.optInt(1).coerceAtLeast(0), a.optInt(2).coerceAtLeast(0),
                a.optInt(3).coerceAtLeast(0), a.optInt(4) == 1, a.optInt(5, -1).takeIf { it >= 0 }, a.optInt(6, -1).takeIf { it >= 0 }?.coerceAtMost(1000))
        }
    }
}

/** A day of the log: its sessions (keys as [AdhkarProgress.session]) and the free tasbih's count. */
data class DayLog(val sessions: Map<String, SessionLog> = emptyMap(), val tasbih: Int = 0) {
    val isEmpty get() = sessions.isEmpty() && tasbih == 0

    /** The after-prayer sessions of the day, at most one per prayer. */
    private fun afterPrayer() = sessions.filterKeys { it.startsWith(AdhkarCategory.AFTER_PRAYER.id + ":") }.values.take(PRAYERS)

    /** 0–1 of [c] that day. After the prayer: each of the five prayers is a fifth. */
    fun share(c: AdhkarCategory): Float =
        if (c == AdhkarCategory.AFTER_PRAYER) afterPrayer().sumOf { it.share.toDouble() }.toFloat() / PRAYERS
        else sessions[c.id]?.share ?: 0f

    /** 0–1 of [c] completed that day: 0 or 1, after the prayer the prayers completed / 5. */
    fun completion(c: AdhkarCategory): Float =
        if (c == AdhkarCategory.AFTER_PRAYER) afterPrayer().count { it.complete } / PRAYERS.toFloat()
        else if (sessions[c.id]?.complete == true) 1f else 0f

    fun complete(c: AdhkarCategory) = completion(c) >= 1f

    /** Any activity of [c] that day. */
    fun touched(c: AdhkarCategory) =
        if (c == AdhkarCategory.AFTER_PRAYER) afterPrayer().any { it.said > 0 || it.complete } else sessions[c.id]?.let { it.said > 0 || it.complete } == true

    fun toJson(): JSONObject {
        val s = JSONObject()
        for ((k, v) in sessions) s.put(k, v.toJson())
        return JSONObject().put("s", s).put("t", tasbih)
    }

    companion object {
        const val PRAYERS = 5

        fun fromJson(j: JSONObject?): DayLog {
            if (j == null) return DayLog()
            val s = j.optJSONObject("s")
            val sessions = HashMap<String, SessionLog>()
            if (s != null) for (k in s.keys()) SessionLog.fromJson(s.optJSONArray(k))?.let { sessions[k] = it }
            return DayLog(sessions, j.optInt("t").coerceAtLeast(0))
        }
    }
}

/**
 * The durable adhkar log: a [DayLog] per day for the last [KEEP_DAYS] days. Today's (and yesterday's) entries are
 * rebuilt from [AdhkarProgress] whenever the counts change ([record]), so the log never needs a write per count:
 * the progress (written on every count) still holds those two days if the process dies before the log is saved.
 */
data class AdhkarHistory(val days: Map<LocalDate, DayLog> = emptyMap()) {

    operator fun get(day: LocalDate): DayLog? = days[day]

    val first: LocalDate? get() = days.keys.minOrNull()

    /** Puts [log] for [day] (an empty one removes it) and drops what is older than [KEEP_DAYS] before the newest day. */
    fun with(day: LocalDate, log: DayLog): AdhkarHistory {
        val next = if (log.isEmpty) days - day else days + (day to log)
        val newest = next.keys.maxOrNull() ?: return AdhkarHistory()
        val limit = newest.minusDays(KEEP_DAYS - 1)
        return AdhkarHistory(if (next.keys.any { it.isBefore(limit) }) next.filterKeys { !it.isBefore(limit) } else next)
    }

    fun toJson(): JSONObject {
        val d = JSONObject()
        for ((day, log) in days.toSortedMap()) d.put(day.toString(), log.toJson())
        return JSONObject().put("v", 1).put("days", d)
    }

    companion object {
        const val KEEP_DAYS = 400L

        fun fromJson(j: JSONObject?): AdhkarHistory {
            val d = j?.optJSONObject("days") ?: return AdhkarHistory()
            val out = HashMap<LocalDate, DayLog>()
            for (k in d.keys()) {
                val day = runCatching { LocalDate.parse(k) }.getOrNull() ?: continue
                val log = DayLog.fromJson(d.optJSONObject(k))
                if (!log.isEmpty) out[day] = log
            }
            return AdhkarHistory(out)
        }

        /**
         * The log before there was one: the progress's completed days (a few months, for the streaks) become
         * complete sessions with unknown counts and time, so no streak is lost. Days already in [into] win.
         */
        fun migrate(p: AdhkarProgress, into: AdhkarHistory = AdhkarHistory()): AdhkarHistory {
            val days = HashMap(into.days)
            for ((session, dates) in p.done) for (day in dates) {
                val log = days[day] ?: DayLog()
                if (session in log.sessions) continue
                days[day] = log.copy(sessions = log.sessions + (session to SessionLog(0, 0, 0, 0, complete = true)))
            }
            p.tasbihDay?.let { t -> if (p.tasbihCount > 0 && (days[t]?.tasbih ?: 0) == 0) days[t] = (days[t] ?: DayLog()).copy(tasbih = p.tasbihCount) }
            return AdhkarHistory(days).let { h -> h.days.keys.maxOrNull()?.let { h.with(it, h.days.getValue(it)) } ?: h }
        }
    }
}

/** Pure log arithmetic: a day's entry from the progress, streaks, rates and the heatmap. */
object AdhkarLog {

    /**
     * [day]'s entry as the progress has it now. [items] gives a session's adhkar; [previous] is the stored entry,
     * whose completion times are kept; a session completed just now gets [nowMinute] (minutes after [day]'s midnight).
     * Only days the progress still has counts for are rebuilt (today, yesterday): older ones keep [previous].
     */
    fun dayFrom(
        p: AdhkarProgress,
        day: LocalDate,
        items: (AdhkarCategory, PrayerKey?) -> List<Dhikr>,
        previous: DayLog?,
        nowMinute: Int,
    ): DayLog {
        val counts = p.counts[day]
        val sessions = HashMap<String, SessionLog>()
        val keys = LinkedHashSet<String>()
        counts?.keys?.let(keys::addAll)
        for ((session, dates) in p.done) if (day in dates) keys += session
        for (key in keys) {
            val (category, after) = parse(key) ?: continue
            val list = items(category, after)
            val c = counts?.get(key).orEmpty()
            val complete = p.isDone(day, key)
            val old = previous?.sessions?.get(key)
            if (c.isEmpty() && old != null) { // counts pruned: keep what was logged
                sessions[key] = old.copy(complete = complete || old.complete)
                continue
            }
            var done = 0
            var said = 0
            var reps = 0
            var part = 0.0
            for (d in list) {
                val target = d.count(after)
                val n = (c[d.id] ?: 0).coerceAtMost(target)
                reps += target
                said += n
                part += if (target > 0) n.toDouble() / target else 0.0
                if (n >= target) done++
            }
            if (said == 0 && !complete) continue
            val at = if (!complete) null else old?.at?.takeIf { old.complete } ?: nowMinute
            val mean = if (list.isEmpty()) 0 else Math.round(part / list.size * 1000).toInt()
            sessions[key] = SessionLog(done, list.size, said, reps, complete, at, mean)
        }
        // sessions of the stored entry the progress no longer knows (older than its two days) stay as they were
        previous?.sessions?.forEach { (k, v) -> if (counts == null && k !in sessions) sessions[k] = v }
        val tasbih = if (p.tasbihDay == day) p.tasbihCount else previous?.tasbih ?: 0
        return DayLog(sessions, tasbih)
    }

    /** "after_prayer:fajr" → (AFTER_PRAYER, FAJR); "morning" → (MORNING, null); unknown → null. */
    fun parse(session: String): Pair<AdhkarCategory, PrayerKey?>? {
        val id = session.substringBefore(':')
        val c = AdhkarCategory.of(id) ?: return null
        val after = if (session.contains(':')) PrayerKey.of(session.substringAfter(':')) else null
        return c to after
    }

    /** Minutes after [day]'s midnight on the wall clock at [now] (a later date adds 1440 per day). */
    fun minuteOf(day: LocalDate, now: java.time.LocalDateTime): Int =
        (ChronoUnit.DAYS.between(day, now.toLocalDate()) * 1440 + now.hour * 60 + now.minute).toInt().coerceAtLeast(0)

    /** Days in a row [c] was completed, ending [today], or yesterday while today is not done yet. */
    fun streak(h: AdhkarHistory, c: AdhkarCategory, today: LocalDate): Int = run(h, today) { it?.complete(c) == true }

    /** The longest run of days [c] was completed, in the whole log. */
    fun best(h: AdhkarHistory, c: AdhkarCategory): Int = bestRun(h) { it.complete(c) }

    /**
     * The day's score for the heatmap and the "perfect day" streak: the mean share of its [goals] (the collections
     * planned for that day). Null when nothing is planned for it.
     */
    fun score(log: DayLog?, goals: Set<AdhkarCategory>): Float? {
        if (goals.isEmpty()) return null
        if (log == null) return 0f
        return goals.sumOf { log.share(it).toDouble() }.toFloat() / goals.size
    }

    /** Days in a row with every planned collection completed; days with nothing planned don't break it. */
    fun dayStreak(h: AdhkarHistory, today: LocalDate, goals: (LocalDate) -> Set<AdhkarCategory>): Int {
        fun perfect(d: LocalDate): Boolean? {
            val g = goals(d)
            if (g.isEmpty()) return null
            val log = h[d] ?: return false
            return g.all { log.complete(it) }
        }
        var d = if (perfect(today) == true) today else today.minusDays(1)
        var n = 0
        val first = h.first ?: return 0
        while (!d.isBefore(first)) {
            when (perfect(d)) {
                true -> n++
                false -> return n
                null -> Unit
            }
            d = d.minusDays(1)
        }
        return n
    }

    fun bestDayStreak(h: AdhkarHistory, today: LocalDate, goals: (LocalDate) -> Set<AdhkarCategory>): Int {
        val first = h.first ?: return 0
        var best = 0
        var n = 0
        var d = first
        while (!d.isAfter(today)) {
            val g = goals(d)
            if (g.isNotEmpty()) {
                val log = h[d]
                if (log != null && g.all { log.complete(it) }) {
                    n++
                    if (n > best) best = n
                } else if (d != today) n = 0 // today may still be completed
            }
            d = d.plusDays(1)
        }
        return best
    }

    /**
     * Completion rate of [c] over the [n] days ending [today]: completed days (after the prayer: prayers / 5) over the
     * days counted, which start no earlier than the log's first day, so a new log isn't punished for its past.
     */
    fun rate(h: AdhkarHistory, c: AdhkarCategory, today: LocalDate, n: Int): Float {
        val first = h.first ?: return 0f
        var sum = 0f
        var days = 0
        for (i in 0 until n) {
            val d = today.minusDays(i.toLong())
            if (d.isBefore(first)) break
            // today counts only once it has something: an open morning isn't a missed one
            if (d == today && (h[d]?.completion(c) ?: 0f) <= 0f) continue
            sum += h[d]?.completion(c) ?: 0f
            days++
        }
        return if (days == 0) 0f else sum / days
    }

    fun tasbih(h: AdhkarHistory, today: LocalDate, n: Int): Int =
        (0 until n).sumOf { h[today.minusDays(it.toLong())]?.tasbih ?: 0 }

    /** The usual completion time of [c] (median minute of the day) over the last [n] days; null below 3 known times. */
    fun usualMinute(h: AdhkarHistory, c: AdhkarCategory, today: LocalDate, n: Int = 30): Int? {
        if (c == AdhkarCategory.AFTER_PRAYER) return null
        val m = (0 until n).mapNotNull { h[today.minusDays(it.toLong())]?.sessions?.get(c.id)?.takeIf { s -> s.complete }?.at }.sorted()
        if (m.size < 3) return null
        return if (m.size % 2 == 1) m[m.size / 2] else (m[m.size / 2 - 1] + m[m.size / 2]) / 2
    }

    /**
     * The heatmap: [weeks] columns of 7 days, each column a week starting on [firstDay], the last column holding
     * [today]. Values are the day's [score]; null for days after today, before the log, or with nothing planned.
     */
    fun heatmap(
        h: AdhkarHistory,
        today: LocalDate,
        weeks: Int,
        goals: (LocalDate) -> Set<AdhkarCategory>,
        firstDay: DayOfWeek = DayOfWeek.MONDAY,
    ): Heatmap {
        val back = (today.dayOfWeek.value - firstDay.value + 7) % 7
        val start = today.minusDays(back.toLong() + (weeks - 1) * 7L)
        val first = h.first
        val cells = ArrayList<Float?>(weeks * 7)
        for (i in 0 until weeks * 7) {
            val d = start.plusDays(i.toLong())
            cells += if (d.isAfter(today) || first == null || d.isBefore(first)) null else score(h[d], goals(d))
        }
        return Heatmap(start, weeks, cells)
    }

    /** Cells column by column: index = week * 7 + day of the week (0 = [start]'s weekday). */
    data class Heatmap(val start: LocalDate, val weeks: Int, val cells: List<Float?>) {
        fun day(i: Int): LocalDate = start.plusDays(i.toLong())
    }

    private inline fun run(h: AdhkarHistory, today: LocalDate, ok: (DayLog?) -> Boolean): Int {
        var d = if (ok(h[today])) today else today.minusDays(1)
        var n = 0
        while (ok(h[d])) {
            n++
            d = d.minusDays(1)
        }
        return n
    }

    private inline fun bestRun(h: AdhkarHistory, ok: (DayLog) -> Boolean): Int {
        var best = 0
        for (d in h.days.keys) {
            if (h.days[d.minusDays(1)]?.let(ok) == true || !ok(h.days.getValue(d))) continue // not the start of a run
            var n = 0
            var x = d
            while (h.days[x]?.let(ok) == true) {
                n++
                x = x.plusDays(1)
            }
            if (n > best) best = n
        }
        return best
    }
}

/** One collection on the log screen: today, its streaks, rates and last week (shares, oldest first). */
data class CollectionStats(
    val category: AdhkarCategory,
    val today: Float,
    val todayComplete: Boolean,
    /** Done today in its own unit: adhkar said in full, or prayers completed after the prayer. */
    val todayDone: Int,
    val todayTotal: Int,
    val streak: Int,
    val best: Int,
    val rate7: Float,
    val rate30: Float,
    val week: List<Float>,
    /** Usual completion, minutes after midnight (can pass 1440), or null. */
    val usual: Int?,
    val goal: Boolean,
)

/** Everything the log screen and `adhkar.history` show, computed once per change of the log or the day. */
data class AdhkarStats(
    val today: LocalDate,
    val collections: List<CollectionStats>,
    val heatmap: AdhkarLog.Heatmap,
    /** Days in a row with every planned collection completed, and the best such run. */
    val streak: Int,
    val best: Int,
    /** The planned collections' mean completion over 30 days. */
    val rate30: Float,
    val tasbihToday: Int,
    val tasbih7: Int,
    val tasbih30: Int,
    /** The last 14 days' tasbih, oldest first. */
    val tasbih14: List<Int>,
    val goals: Set<AdhkarCategory>,
    val days: Int,
) {
    val empty get() = days == 0

    fun toJson(): JSONObject {
        val c = JSONObject()
        for (s in collections) c.put(s.category.id, JSONObject().put("today", round(s.today)).put("complete", s.todayComplete)
            .put("done", s.todayDone).put("total", s.todayTotal).put("streak", s.streak).put("best", s.best)
            .put("rate7", round(s.rate7)).put("rate30", round(s.rate30)).put("week", JSONArray(s.week.map(::round)))
            .put("usual", s.usual?.let { "%02d:%02d".format(java.util.Locale.ROOT, it / 60 % 24, it % 60) } ?: JSONObject.NULL)
            .put("goal", s.goal))
        return JSONObject().put("streak", streak).put("best", best).put("rate30", round(rate30)).put("days", days)
            .put("goals", JSONArray(goals.map { it.id }))
            .put("tasbih", JSONObject().put("today", tasbihToday).put("week", tasbih7).put("month", tasbih30).put("last14", JSONArray(tasbih14)))
            .put("collections", c)
    }

    private fun round(f: Float) = Math.round(f * 100) / 100.0
}

/** The planned collections of a day: those with an enabled schedule on it; without any schedule, morning and evening. */
fun adhkarGoals(times: List<AdhkarTime>): (LocalDate) -> Set<AdhkarCategory> {
    val on = times.filter { it.enabled }
    if (on.isEmpty()) return { setOf(AdhkarCategory.MORNING, AdhkarCategory.EVENING) }
    return { day -> on.filter { it.on(day) }.map { it.category }.toSet() }
}

fun AdhkarLog.stats(
    h: AdhkarHistory,
    today: LocalDate,
    goals: (LocalDate) -> Set<AdhkarCategory>,
    weeks: Int = 18,
    /** A collection's number of adhkar, for one not started today. */
    size: (AdhkarCategory) -> Int = { 0 },
): AdhkarStats {
    val todayGoals = goals(today)
    val recent = (0 until 30L).mapNotNull { h[today.minusDays(it)] }
    val shown = AdhkarCategory.entries.filter { c -> c in todayGoals || recent.any { it.touched(c) } }
    val log = h[today]
    val collections = shown.map { c ->
        val prayers = c == AdhkarCategory.AFTER_PRAYER
        val s = log?.sessions?.get(c.id)
        CollectionStats(
            category = c,
            today = log?.share(c) ?: 0f,
            todayComplete = log?.complete(c) == true,
            todayDone = if (prayers) Math.round((log?.completion(c) ?: 0f) * DayLog.PRAYERS) else s?.done ?: 0,
            todayTotal = if (prayers) DayLog.PRAYERS else s?.total?.takeIf { it > 0 } ?: size(c),
            streak = streak(h, c, today),
            best = best(h, c),
            rate7 = rate(h, c, today, 7),
            rate30 = rate(h, c, today, 30),
            week = (6 downTo 0).map { h[today.minusDays(it.toLong())]?.share(c) ?: 0f },
            usual = usualMinute(h, c, today),
            goal = c in todayGoals,
        )
    }
    val goalRates = collections.filter { it.goal }.map { it.rate30 }
    return AdhkarStats(
        today = today,
        collections = collections,
        heatmap = heatmap(h, today, weeks, goals),
        streak = dayStreak(h, today, goals),
        best = bestDayStreak(h, today, goals),
        rate30 = if (goalRates.isEmpty()) 0f else goalRates.sum() / goalRates.size,
        tasbihToday = log?.tasbih ?: 0,
        tasbih7 = tasbih(h, today, 7),
        tasbih30 = tasbih(h, today, 30),
        tasbih14 = (13 downTo 0).map { h[today.minusDays(it.toLong())]?.tasbih ?: 0 },
        goals = todayGoals,
        days = h.days.size,
    )
}
