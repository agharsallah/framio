package dev.framio.mawaqit.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * The adhkar collections, read from `assets/adhkar.json` (Hisn al-Muslim through openly licensed datasets, and the
 * Quran's own text, see `assets/ADHKAR-SOURCE.txt`). The Arabic is never typed in code: it only comes from that file.
 *
 * [daily]: said at a time of day (morning, evening, after each prayer, at bedtime, on waking). The others are for any
 * time: general remembrance, seeking forgiveness, salawat, du'as from the Quran, for distress, and everyday moments.
 */
enum class AdhkarCategory(val id: String, val daily: Boolean = true) {
    MORNING("morning"), EVENING("evening"), AFTER_PRAYER("after_prayer"), SLEEP("sleep"), WAKING("waking"),
    DHIKR("dhikr", false), ISTIGHFAR("istighfar", false), SALAWAT("salawat", false), QURAN("quran", false),
    DISTRESS("distress", false), EVERYDAY("everyday", false);

    /** The I18n key of the category's name. */
    val key get() = "adhkar_$id"

    companion object {
        fun of(id: String?): AdhkarCategory? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One remembrance. [count] is how many times to say it; after Fajr and Maghrib some after-prayer adhkar are said
 * [countFajrMaghrib] times, and some only after the prayers in [only].
 */
data class Dhikr(
    val id: String,
    val arabic: String,
    val count: Int,
    val translation: String? = null,
    val transliteration: String? = null,
    val virtue: String? = null,
    val virtueAr: String? = null,
    /** Short, e.g. "Muslim 2723". */
    val reference: String? = null,
    val source: String? = null,
    val sourceAr: String? = null,
    val countFajrMaghrib: Int? = null,
    val only: Set<PrayerKey>? = null,
    /** When it is said, for the everyday and distress du'as: "Entering the mosque" and the Hisn chapter's Arabic. */
    val title: String? = null,
    val titleAr: String? = null,
) {
    fun count(after: PrayerKey?): Int =
        if (countFajrMaghrib != null && (after == PrayerKey.FAJR || after == PrayerKey.MAGHRIB)) countFajrMaghrib else count

    fun appliesAfter(after: PrayerKey?): Boolean = only == null || after == null || after in only

    /** Arabic speakers read the Arabic virtue when the dataset has one; everyone else the English. */
    fun virtue(lang: String): String? = (if (lang == "ar") virtueAr else null) ?: virtue

    /** The occasion in the reader's language: Arabic for Arabic, the English title otherwise. */
    fun title(lang: String): String? = (if (lang == "ar") titleAr else null) ?: title
}

class AdhkarBook(private val categories: Map<AdhkarCategory, List<Dhikr>>, val tasbih: List<Dhikr>) {

    /** The adhkar of [category]; for [AdhkarCategory.AFTER_PRAYER], those said after the prayer [after]. */
    fun items(category: AdhkarCategory, after: PrayerKey? = null): List<Dhikr> {
        val all = categories[category].orEmpty()
        return if (category == AdhkarCategory.AFTER_PRAYER) all.filter { it.appliesAfter(after) } else all
    }

    companion object {
        fun parse(json: String): AdhkarBook {
            val j = JSONObject(json)
            val itemsJ = j.getJSONObject("items")
            val byId = HashMap<String, Dhikr>()
            for (id in itemsJ.keys()) byId[id] = dhikr(id, itemsJ.getJSONObject(id))
            val cats = LinkedHashMap<AdhkarCategory, List<Dhikr>>()
            for (c in j.getJSONArray("categories").objects()) {
                val cat = AdhkarCategory.of(c.optString("id")) ?: continue
                cats[cat] = c.optJSONArray("items").strings().mapNotNull { byId[it] }
            }
            return AdhkarBook(cats, j.optJSONArray("tasbih").strings().mapNotNull { byId[it] })
        }

        private fun dhikr(id: String, o: JSONObject) = Dhikr(
            id = id,
            arabic = o.getString("ar"),
            count = o.optInt("n", 1),
            translation = o.str("tr"),
            transliteration = o.str("tl"),
            virtue = o.str("v"),
            virtueAr = o.str("va"),
            reference = o.str("r"),
            source = o.str("s"),
            sourceAr = o.str("sa"),
            countFajrMaghrib = o.optInt("nfm", 0).takeIf { it > 0 },
            only = o.optJSONArray("only")?.strings()?.mapNotNull(PrayerKey::of)?.toSet(),
            title = o.str("t"),
            titleAr = o.str("ta"),
        )

        private fun JSONObject.str(k: String): String? = optString(k, "").trim().ifEmpty { null }
    }
}

/**
 * Today's counts, what was completed on which day (for the streaks), the reminders dismissed or already announced.
 * Stored as one JSON document; only the last two days of counts are kept (a session before sleep can cross midnight).
 * A session is "category" or "after_prayer:<prayer>", so each prayer has its own after-prayer adhkar.
 */
data class AdhkarProgress(
    /** date → session → dhikr id → count. */
    val counts: Map<LocalDate, Map<String, Map<String, Int>>> = emptyMap(),
    /** session → the days it was completed. */
    val done: Map<String, Set<LocalDate>> = emptyMap(),
    /** "yyyy-mm-dd/category" of the reminders dismissed for that day. */
    val dismissed: Set<String> = emptySet(),
    /** Same keys: the reminders whose chime (and photo-frame wake) already happened. */
    val announced: Set<String> = emptySet(),
    val tasbihDay: LocalDate? = null,
    val tasbihCount: Int = 0,
    val tasbihPhrase: String = "",
) {
    fun count(day: LocalDate, session: String, id: String): Int = counts[day]?.get(session)?.get(id) ?: 0

    fun counts(day: LocalDate, session: String): Map<String, Int> = counts[day]?.get(session).orEmpty()

    fun tasbihToday(today: LocalDate): Int = if (tasbihDay == today) tasbihCount else 0

    fun isDone(day: LocalDate, session: String) = day in done[session].orEmpty()

    /** Sets one count; keeps today's and yesterday's counts only. */
    fun withCount(day: LocalDate, session: String, id: String, value: Int): AdhkarProgress {
        val forDay = counts[day].orEmpty()
        val forSession = forDay[session].orEmpty() + (id to value)
        val kept = counts.filterKeys { !it.isBefore(day.minusDays(1)) }
        return copy(counts = kept + (day to (forDay + (session to forSession))))
    }

    fun reset(day: LocalDate, session: String): AdhkarProgress {
        val forDay = counts[day] ?: return this
        return copy(counts = counts + (day to (forDay - session)), done = done + (session to (done[session].orEmpty() - day)))
    }

    /** Marks [session] complete on [day]. The durable log is [AdhkarHistory]; these days were its start (migration). */
    fun withDone(day: LocalDate, session: String): AdhkarProgress {
        val days = (done[session].orEmpty() + day).filter { !it.isBefore(day.minusDays(KEEP_DAYS)) }.toSet()
        return copy(done = done + (session to days))
    }

    fun withDismissed(key: String) = copy(dismissed = (dismissed + key).takeLast(20))
    /** "day/category/schedule" of the schedules fired (or skipped): up to [AdhkarReminders.MAX] a day, two days kept. */
    fun withAnnounced(key: String) = copy(announced = (announced + key).takeLast(2 * AdhkarReminders.MAX + 8))

    fun withTasbih(today: LocalDate, count: Int, phrase: String = tasbihPhrase) = copy(tasbihDay = today, tasbihCount = count, tasbihPhrase = phrase)

    /** Days in a row [session] was completed, ending today, or yesterday while today is still open. */
    fun streak(session: String, today: LocalDate): Int {
        val days = done[session].orEmpty()
        var d = if (today in days) today else today.minusDays(1)
        var n = 0
        while (d in days) {
            n++
            d = d.minusDays(1)
        }
        return n
    }

    fun toJson(): JSONObject {
        val c = JSONObject()
        for ((day, sessions) in counts) {
            val s = JSONObject()
            for ((session, ids) in sessions) s.put(session, JSONObject(ids as Map<*, *>))
            c.put(day.toString(), s)
        }
        val d = JSONObject()
        for ((session, days) in done) d.put(session, JSONArray(days.sorted().map { it.toString() }))
        return JSONObject().put("counts", c).put("done", d).put("dismissed", JSONArray(dismissed.toList()))
            .put("announced", JSONArray(announced.toList())).put("tasbihDay", tasbihDay?.toString() ?: "")
            .put("tasbihCount", tasbihCount).put("tasbihPhrase", tasbihPhrase)
    }

    companion object {
        const val KEEP_DAYS = 120L

        fun session(category: AdhkarCategory, after: PrayerKey? = null) =
            if (category == AdhkarCategory.AFTER_PRAYER && after != null) "${category.id}:${after.id}" else category.id

        fun reminderKey(day: LocalDate, category: AdhkarCategory) = "$day/${category.id}"

        fun fromJson(j: JSONObject?): AdhkarProgress {
            if (j == null) return AdhkarProgress()
            fun date(s: String?) = runCatching { LocalDate.parse(s) }.getOrNull()
            val counts = HashMap<LocalDate, Map<String, Map<String, Int>>>()
            j.optJSONObject("counts")?.let { c ->
                for (dk in c.keys()) {
                    val day = date(dk) ?: continue
                    val s = c.optJSONObject(dk) ?: continue
                    counts[day] = s.keys().asSequence().associateWith { sk ->
                        val ids = s.optJSONObject(sk) ?: JSONObject()
                        ids.keys().asSequence().associateWith { ids.optInt(it).coerceAtLeast(0) }
                    }
                }
            }
            val done = HashMap<String, Set<LocalDate>>()
            j.optJSONObject("done")?.let { d -> for (k in d.keys()) done[k] = d.optJSONArray(k).strings().mapNotNull(::date).toSet() }
            return AdhkarProgress(
                counts = counts,
                done = done,
                dismissed = j.optJSONArray("dismissed").strings().toSet(),
                announced = j.optJSONArray("announced").strings().toSet(),
                tasbihDay = date(j.optString("tasbihDay")),
                tasbihCount = j.optInt("tasbihCount").coerceAtLeast(0),
                tasbihPhrase = j.optString("tasbihPhrase"),
            )
        }
    }
}

private fun Set<String>.takeLast(n: Int): Set<String> = if (size <= n) this else toList().takeLast(n).toSet()
