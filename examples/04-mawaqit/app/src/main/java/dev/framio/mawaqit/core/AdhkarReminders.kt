package dev.framio.mawaqit.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * One time of the day for a collection: settings.adhkar.schedules. [at] is either a clock time "HH:MM" or relative
 * to a prayer: "fajr+20" is 20 min after Fajr's screens (adhan … azkar) are over, so it never covers the prayer;
 * "maghrib-30" is 30 min before Maghrib's adhan. [days] are ISO days of the week (1 = Monday), empty = every day.
 * [mode] "remind" shows the reminder card (with the chime), "open" opens the reader by itself. [push] also sends a
 * plain-text notification to the user's ntfy topic (when [AdhkarSettings.pushEnabled]). [wake] lights a screen that
 * the night switched off.
 */
data class AdhkarTime(
    val id: String,
    val category: AdhkarCategory,
    val at: String,
    val days: Set<DayOfWeek> = emptySet(),
    val mode: String = REMIND,
    val enabled: Boolean = true,
    val push: Boolean = true,
    val wake: Boolean = false,
) {
    val opens get() = mode == OPEN

    fun on(day: LocalDate) = days.isEmpty() || day.dayOfWeek in days

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("category", category.id).put("at", at).put("days", JSONArray(days.sorted().map { it.value }))
        .put("mode", mode).put("enabled", enabled).put("push", push).put("wake", wake)

    companion object {
        const val REMIND = "remind"
        const val OPEN = "open"
        private val ID = Regex("[A-Za-z0-9_-]{1,16}")

        /** Null for an unknown collection or a time that doesn't parse. */
        fun fromJson(j: JSONObject, index: Int = 0): AdhkarTime? {
            val category = AdhkarCategory.of(j.optString("category")) ?: return null
            val at = AdhkarReminders.normalize(j.optString("at")) ?: return null
            val id = j.optString("id").takeIf { ID.matches(it) } ?: "t${index + 1}"
            return AdhkarTime(
                id = id,
                category = category,
                at = at,
                days = j.optJSONArray("days").let { a ->
                    if (a == null) emptySet() else (0 until a.length()).mapNotNull { a.optInt(it, 0).takeIf { d -> d in 1..7 }?.let(DayOfWeek::of) }.toSet()
                }.let { if (it.size == 7) emptySet() else it },
                mode = j.optString("mode", REMIND).takeIf { it == REMIND || it == OPEN } ?: REMIND,
                enabled = j.optBoolean("enabled", true),
                push = j.optBoolean("push", true),
                wake = j.optBoolean("wake", false),
            )
        }
    }
}

/**
 * When the schedules are due and what to do about it; the frame's side is data/AdhkarScheduler.
 *
 * A schedule's occurrence on a day ([Due]) is due at [Due.at] and shows its reminder card until [Due.until]: the end of
 * the collection's own time when [at] falls in it (morning until Shuruq + 3 h, evening until Maghrib + 1 h, before
 * sleep 3 h), otherwise [SHOW_HOURS] later. It fires (chime, opens the reader, phone notification) once, at [Due.at]
 * or, when a prayer screen or a message is up then, as soon as it's gone, within [GRACE_MIN]; later it is skipped
 * (the card still shows, quietly). Nothing fires for a collection completed or dismissed for that day.
 */
object AdhkarReminders {
    const val GRACE_MIN = 45L
    const val SHOW_HOURS = 2L
    const val MAX = 24
    private val CLOCK = Regex("([01]?[0-9]|2[0-3]):([0-5][0-9])")
    private val RELATIVE = Regex("(fajr|shuruq|dhuhr|asr|maghrib|isha)\\s*(?:([+-])\\s*(\\d{1,3}))?")
    val ANCHORS = listOf(PrayerKey.FAJR, PrayerKey.SHURUQ, PrayerKey.DHUHR, PrayerKey.ASR, PrayerKey.MAGHRIB, PrayerKey.ISHA)

    /** "7:05" → "07:05", "Asr + 15" → "asr+15", "fajr" → "fajr+0"; null when it isn't one of the two forms. */
    fun normalize(raw: String): String? {
        val s = raw.trim().lowercase()
        CLOCK.matchEntire(s)?.let { return "%02d:%s".format(java.util.Locale.ROOT, it.groupValues[1].toInt(), it.groupValues[2]) }
        val m = RELATIVE.matchEntire(s) ?: return null
        val n = (m.groupValues[3].toIntOrNull() ?: 0) * if (m.groupValues[2] == "-") -1 else 1
        if (n !in -180..240) return null
        return m.groupValues[1] + (if (n < 0) "-" else "+") + kotlin.math.abs(n)
    }

    /** The clock time of a fixed [at], else null. */
    fun clock(at: String): LocalTime? = CLOCK.matchEntire(at)?.let { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }

    /** The prayer and minutes of a relative [at], else null. */
    fun relative(at: String): Pair<PrayerKey, Int>? {
        val m = RELATIVE.matchEntire(at) ?: return null
        val p = PrayerKey.of(m.groupValues[1]) ?: return null
        return p to (m.groupValues[3].toIntOrNull() ?: 0) * if (m.groupValues[2] == "-") -1 else 1
    }

    /** When [t] is due on [day], or null (that prayer has no time that day). A clock time in a DST gap moves after it. */
    fun resolve(t: AdhkarTime, day: LocalDate, source: TimesSource, b: Behavior): ZonedDateTime? {
        clock(t.at)?.let { return ZonedDateTime.of(day, it, source.zone) }
        val (key, minutes) = relative(t.at) ?: return null
        val d = source.day(day) ?: return null
        val p = when (key) {
            PrayerKey.FAJR -> d.fajr
            PrayerKey.SHURUQ -> d.shuruq
            PrayerKey.DHUHR -> d.dhuhr
            PrayerKey.ASR -> d.asr
            PrayerKey.MAGHRIB -> d.maghrib
            PrayerKey.ISHA -> d.isha
            else -> null
        } ?: return null
        return if (minutes < 0) p.adhan.plusMinutes(minutes.toLong()) else AdhkarSchedule.flowEnd(p, source, b).plusMinutes(minutes.toLong())
    }

    /** One occurrence of a schedule. [key] names it in the progress's "announced" set: it fires once. */
    data class Due(val time: AdhkarTime, val day: LocalDate, val at: ZonedDateTime, val until: ZonedDateTime) {
        val category get() = time.category
        val key get() = "$day/${time.category.id}/${time.id}"
        fun showing(now: ZonedDateTime) = !now.isBefore(at) && now.isBefore(until)
    }

    /**
     * The occurrences of [times] on [day] (enabled, on that weekday, with a time). [windows] are the collections' own
     * times (AdhkarSchedule.windows of that day and the day before) and set how long each card stays.
     */
    fun occurrences(day: LocalDate, times: List<AdhkarTime>, source: TimesSource, b: Behavior, windows: List<AdhkarSchedule.Window>): List<Due> =
        times.filter { it.enabled && it.on(day) }.mapNotNull { t ->
            val at = resolve(t, day, source, b) ?: return@mapNotNull null
            val own = windows.firstOrNull { it.category == t.category && at in it }
            Due(t, day, at, own?.end?.takeIf { it.isAfter(at) } ?: at.plusHours(SHOW_HOURS))
        }

    /** What to do with an occurrence now. */
    enum class Step { WAIT, FIRE, SKIP, NONE }

    /**
     * [blocked]: a prayer screen (adhan … azkar, Jumu'a), a message or the clock sync is up. [settled]: completed,
     * dismissed for its day, or already fired or skipped.
     */
    fun step(d: Due, now: ZonedDateTime, blocked: Boolean, settled: Boolean): Step = when {
        settled -> Step.NONE
        now.isBefore(d.at) -> Step.WAIT
        !now.isBefore(d.at.plusMinutes(GRACE_MIN)) || !now.isBefore(d.until) -> Step.SKIP
        blocked -> Step.WAIT
        else -> Step.FIRE
    }

    /** The card to show at [now]: the earliest-due showing occurrence whose collection isn't done or dismissed. */
    fun showing(now: ZonedDateTime, dues: List<Due>, open: (Due) -> Boolean): Due? =
        dues.filter { it.showing(now) && open(it) }.minByOrNull { it.at.toInstant() }

    /** The next occurrence still to come after [now], for the panel and the log screen. */
    fun next(now: ZonedDateTime, dues: List<Due>): Due? = dues.filter { it.at.isAfter(now) }.minByOrNull { it.at.toInstant() }

    /**
     * The schedules of settings that predate them: the morning and evening reminders after Fajr's and Asr's screens,
     * the before-sleep time. The same times as before, so nothing changes for the user.
     */
    fun legacy(j: JSONObject?): List<AdhkarTime> {
        val morning = j?.optInt("morningAfterFajrMinutes", 20)?.coerceIn(0, 180) ?: 20
        val evening = j?.optInt("eveningAfterAsrMinutes", 15)?.coerceIn(0, 180) ?: 15
        val sleep = j?.optString("sleepTime", "")?.trim().orEmpty().let { normalize(it)?.takeIf { s -> clock(s) != null } }
        return listOfNotNull(
            AdhkarTime("morning", AdhkarCategory.MORNING, "fajr+$morning", enabled = j?.optBoolean("morningReminder", true) ?: true),
            AdhkarTime("evening", AdhkarCategory.EVENING, "asr+$evening", enabled = j?.optBoolean("eveningReminder", true) ?: true),
            sleep?.let { AdhkarTime("sleep", AdhkarCategory.SLEEP, it) },
        )
    }

    /** A new id not in [taken]. */
    fun newId(taken: Collection<String>): String {
        var i = taken.size + 1
        while ("t$i" in taken) i++
        return "t$i"
    }
}
