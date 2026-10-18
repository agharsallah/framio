package dev.framio.mawaqit.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.ZonedDateTime
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * The adhkar display: a second page of the frame, next to the prayer times, that shows one dhikr after another on its
 * own (a wall display, nobody taps it). Also the random dhikr slides of the main screen.
 *
 * [home] is the page the frame rests on: shown when the display comes forward, and again after [returnMinutes]
 * without a touch on the other page (0 = stays where it was put).
 */
data class AdhkarLoopSettings(
    /** The page exists (a swipe away from the prayer times). */
    val enabled: Boolean = true,
    /** "main" or "adhkar". */
    val home: String = "main",
    val returnMinutes: Int = 15,
    /** Collection ids, in order; any of [AdhkarCategory]. */
    val collections: List<String> = DEFAULT_COLLECTIONS,
    /** Morning adhkar in the morning window, evening ones in the evening, bedtime ones at bedtime; the mix otherwise. */
    val timeAware: Boolean = true,
    /** "shuffle" or "sequential". */
    val order: String = "shuffle",
    /** Reading pace in percent: 200 shows each dhikr half as long. */
    val speed: Int = 100,
    val translation: Boolean = true,
    val transliteration: Boolean = false,
) {
    val categories: List<AdhkarCategory> get() = collections.mapNotNull(AdhkarCategory::of)

    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled).put("home", home).put("returnMinutes", returnMinutes)
        .put("collections", JSONArray(collections)).put("timeAware", timeAware).put("order", order).put("speed", speed)
        .put("translation", translation).put("transliteration", transliteration)

    companion object {
        val DEFAULT_COLLECTIONS = listOf("dhikr", "istighfar", "salawat", "quran")
        private val D = AdhkarLoopSettings()
        fun fromJson(j: JSONObject?) = if (j == null) D else AdhkarLoopSettings(
            enabled = j.optBoolean("enabled", D.enabled),
            home = j.optString("home", D.home).takeIf { it == "main" || it == "adhkar" } ?: D.home,
            returnMinutes = j.optInt("returnMinutes", D.returnMinutes).coerceIn(0, 240),
            // an empty or unknown list would leave the page blank: the default mix instead
            collections = j.optJSONArray("collections")?.strings()?.filter { AdhkarCategory.of(it) != null }?.distinct()
                ?.ifEmpty { null } ?: D.collections,
            timeAware = j.optBoolean("timeAware", D.timeAware),
            order = j.optString("order", D.order).takeIf { it == "shuffle" || it == "sequential" } ?: D.order,
            speed = j.optInt("speed", D.speed).coerceIn(50, 200),
            translation = j.optBoolean("translation", D.translation),
            transliteration = j.optBoolean("transliteration", D.transliteration),
        )
    }
}

/**
 * One screenful of the loop. A dhikr too long for one screen is cut into [parts] pages at its own commas and full
 * stops ([part] is 1-based); its meaning (translation, transliteration) is then a page of its own after the Arabic
 * ([meaning]). [millis] is how long it stays.
 */
data class LoopPage(
    val dhikr: Dhikr,
    val category: AdhkarCategory,
    /** The Arabic shown on this page (the whole dhikr when [parts] is 1); null on a meaning page. */
    val arabic: String?,
    val part: Int,
    val parts: Int,
    val translation: String?,
    val transliteration: String?,
    val millis: Long,
) {
    val meaning get() = arabic == null
    /** Stable across rebuilds of the playlist: the loop picks up where it was. */
    val key get() = "${dhikr.id}/${if (meaning) "m" else part}"
}

object AdhkarLoop {
    /** Arabic per page at most: fits the page at ≥ 40 sp in landscape and portrait (Amiri, 1.7 em lines). */
    const val PAGE_CHARS = 300
    /** A dhikr needing more pages than this (Al 'Imran 190–200) stays in the reader, out of the loop. */
    const val MAX_PARTS = 3
    /** Translation and transliteration under the Arabic when everything is this short, else on their own page. */
    const val INLINE_ARABIC = 220
    const val INLINE_LATIN = 260
    /** A meaning page holds this much (translation + transliteration); a longer translation is left out whole. */
    const val MEANING_CHARS = 620

    const val MIN_SECONDS = 8.0
    const val MAX_SECONDS = 50.0

    /** Slides on the main screen: only short adhkar, and never this close to an adhan. */
    const val SLIDE_ARABIC = 170
    const val SLIDE_LATIN = 210
    const val SLIDE_QUIET_MINUTES = 10L

    /** The collections to loop now: the one whose time it is (morning, evening, bedtime) or the chosen mix. */
    fun collections(s: AdhkarLoopSettings, current: AdhkarCategory?): List<AdhkarCategory> =
        if (s.timeAware && current != null && current.daily && current != AdhkarCategory.AFTER_PRAYER) listOf(current)
        else s.categories.ifEmpty { AdhkarLoopSettings.DEFAULT_COLLECTIONS.mapNotNull(AdhkarCategory::of) }

    /** The adhkar of [cats] in order, each once (some are in two collections: Sayyid al-Istighfar…). */
    fun items(book: AdhkarBook, cats: List<AdhkarCategory>): List<Pair<Dhikr, AdhkarCategory>> {
        val seen = HashSet<String>()
        val out = ArrayList<Pair<Dhikr, AdhkarCategory>>()
        for (c in cats) for (d in book.items(c)) if (seen.add(d.id)) out += d to c
        return out
    }

    /**
     * One round of the loop. Shuffled rounds use [cycle] as the seed, and never start with the dhikr the previous
     * round ended on. [latin] is false in Arabic: then no translation nor transliteration.
     */
    fun playlist(book: AdhkarBook, s: AdhkarLoopSettings, current: AdhkarCategory?, latin: Boolean, cycle: Int): List<LoopPage> {
        val items = items(book, collections(s, current))
        val ordered = if (s.order == "shuffle" && items.size > 2) {
            val now = shuffled(items, cycle)
            val before = shuffled(items, cycle - 1)
            if (now.first().first.id == before.last().first.id) now.drop(1) + now.first() else now
        } else items
        return ordered.flatMap { (d, c) -> pages(d, c, s, latin) }
    }

    private fun shuffled(items: List<Pair<Dhikr, AdhkarCategory>>, cycle: Int) =
        items.shuffled(Random(cycle * 7919L + items.size))

    /** The pages of one dhikr; none when it is too long for [MAX_PARTS] pages. */
    fun pages(d: Dhikr, c: AdhkarCategory, s: AdhkarLoopSettings, latin: Boolean): List<LoopPage> {
        val parts = split(d.arabic) ?: return emptyList()
        val tr = d.translation?.takeIf { latin && s.translation }
        val tl = d.transliteration?.takeIf { latin && s.transliteration }
        val latinLength = (tr?.length ?: 0) + (tl?.length ?: 0)
        val inline = parts.size == 1 && d.arabic.length <= INLINE_ARABIC && latinLength <= INLINE_LATIN
        val out = ArrayList<LoopPage>(parts.size + 1)
        parts.forEachIndexed { i, text ->
            val withLatin = inline && latinLength > 0
            out += LoopPage(d, c, text, i + 1, parts.size, tr.takeIf { withLatin }, tl.takeIf { withLatin },
                millis(seconds(text.length, if (withLatin) latinLength else 0), s.speed))
        }
        if (!inline && latinLength > 0) {
            // a long translation goes alone (the transliteration is left out); one longer still is not shown at all
            val mt = tr?.takeIf { it.length <= MEANING_CHARS }
            val ml = tl?.takeIf { mt == null || mt.length + it.length <= MEANING_CHARS }?.takeIf { it.length <= MEANING_CHARS }
            if (mt != null || ml != null) {
                val n = (mt?.length ?: 0) + (ml?.length ?: 0)
                out += LoopPage(d, c, null, parts.size, parts.size, mt, ml, millis(4.0 + n * 0.06, s.speed))
            }
        }
        return out
    }

    /** Reading time: a few seconds to look up, then the words; recited slowly, so Arabic counts more than Latin. */
    fun seconds(arabicChars: Int, latinChars: Int): Double = 5.0 + arabicChars * 0.11 + latinChars * 0.05

    fun millis(seconds: Double, speed: Int): Long =
        (seconds.coerceIn(MIN_SECONDS, MAX_SECONDS) * 1000 * 100 / speed.coerceIn(50, 200)).roundToLong()

    /**
     * [arabic] in at most [MAX_PARTS] pages of [PAGE_CHARS], cut after its own punctuation (commas, full stops, the
     * Quran's pause marks) as evenly as it allows, else between words; null when it needs more pages. No word is ever
     * cut and nothing is dropped: the pages joined with spaces are the text.
     */
    fun split(arabic: String, max: Int = PAGE_CHARS): List<String>? {
        val text = arabic.trim()
        if (text.length <= max) return listOf(text)
        val words = text.split(' ').filter { it.isNotEmpty() }
        // ends[i]: length of words[0..i] joined; a cut after word i starts the next page with word i + 1
        val ends = IntArray(words.size)
        var len = -1
        for (i in words.indices) {
            len += words[i].length + 1
            ends[i] = len
        }
        for (n in ceil(text.length / max.toDouble()).toInt()..MAX_PARTS) {
            val cuts = cuts(words, ends, n, max) ?: continue
            val out = ArrayList<String>(n)
            var from = 0
            for (c in cuts + words.lastIndex) {
                out += words.subList(from, c + 1).joinToString(" ")
                from = c + 1
            }
            return out
        }
        return null
    }

    /** n - 1 cut points (index of the last word of each page but the last), or null when a page can't fit. */
    private fun cuts(words: List<String>, ends: IntArray, n: Int, max: Int): List<Int>? {
        val total = ends.last()
        val out = ArrayList<Int>(n - 1)
        var start = -1 // last word of the previous page
        for (k in 1 until n) {
            val startLen = if (start < 0) 0 else ends[start] + 1
            val target = startLen + (total - startLen) / (n - k + 1)
            // candidates: the page from start + 1 to i fits, and what is left can still fit the pages after it
            var best = -1
            var bestScore = Double.MAX_VALUE
            for (i in start + 1 until words.lastIndex) {
                val pageLen = ends[i] - startLen
                if (pageLen > max) break
                if (total - (ends[i] + 1) > max * (n - k)) continue
                if (isPause(words[i + 1])) continue // a pause mark stays at the end of its words
                val punct = if (breaksAfter(words[i])) 0.0 else 1.0
                val score = punct * max + kotlin.math.abs(ends[i] - target)
                if (score < bestScore) {
                    bestScore = score
                    best = i
                }
            }
            if (best < 0) return null
            out += best
            start = best
        }
        if (ends.last() - (ends[start] + 1) > max) return null
        return out
    }

    private fun breaksAfter(word: String): Boolean = word.last() in AFTER || isPause(word)
    private fun isPause(word: String): Boolean = word.all { it in PAUSE } // Tanzil writes the pause marks as words

    // ASCII and Arabic comma, semicolon, full stop, colon; closing brackets; the Quran's small pause marks
    private const val AFTER = ",،;؛.:)]*!؟?"
    private const val PAUSE = "ۖۗۘۙۚۛۜ"

    // ---------------------------------------------------------------- slides on the main screen

    /** Short enough for the slide area with its translation: the adhkar the main screen may show between slides. */
    fun slideCandidates(book: AdhkarBook, s: AdhkarLoopSettings): List<Pair<Dhikr, AdhkarCategory>> =
        items(book, collections(s, null)).filter { (d, _) ->
            d.arabic.length <= SLIDE_ARABIC && (d.translation?.length ?: 0) <= SLIDE_LATIN
        }

    /** A random one, not among [recent] (the last few shown) when there is a choice. */
    fun pick(candidates: List<Pair<Dhikr, AdhkarCategory>>, recent: Collection<String>, random: Random): Pair<Dhikr, AdhkarCategory>? {
        if (candidates.isEmpty()) return null
        val fresh = candidates.filter { it.first.id !in recent }.ifEmpty { candidates }
        return fresh[random.nextInt(fresh.size)]
    }

    /** Never during a reminder, the iqama countdown or any prayer screen, nor in the last minutes before an adhan. */
    fun slideAllowed(now: ZonedDateTime, phase: PhaseKind, nextAdhan: ZonedDateTime?): Boolean =
        phase == PhaseKind.NORMAL && (nextAdhan == null || Duration.between(now, nextAdhan).toMinutes() >= SLIDE_QUIET_MINUTES)
}
