package dev.framio.mawaqit.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** A mosque page or API response that can't be read. */
class MawaqitPageException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class ParsedPage(val mosque: Mosque, val times: MawaqitTimes, val confJson: JSONObject)

/** A mosque found by the API search. [distanceM] only in a search around a position. */
data class SearchHit(val slug: String?, val uuid: String, val name: String, val address: String?, val distanceM: Int?)

/** Reads a mosque's public page (mawaqit.net/<lang>/<slug>) or the same fields from the authenticated API. */
object MawaqitPage {
    private val CONF = Regex("""\bconfData\s*=\s*\{""")
    private val UUID = Regex("""/api/2\.0/mosque/([0-9A-Za-z-]+)/weather""")
    private val ID = Regex("""/id/([0-9]+)/has-been-updated""")
    private val LAST_UPDATED = Regex("""\blastUpdated\s*=\s*([0-9]+)""")
    private val COLOR = Regex("#[0-9a-fA-F]{3,8}")

    /** @throws MawaqitPageException when the page has no `confData` object, or it isn't valid JSON */
    fun parse(html: String, slug: String): ParsedPage {
        val match = CONF.find(html) ?: throw MawaqitPageException("No confData in the page of $slug")
        val text = jsonObjectAt(html, match.range.last)
            ?: throw MawaqitPageException("Unterminated confData in the page of $slug")
        val conf = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw MawaqitPageException("confData of $slug is not JSON", e)
        }
        return parseConf(
            conf, slug,
            uuid = UUID.find(html)?.groupValues?.get(1),
            id = ID.find(html)?.groupValues?.get(1)?.toIntOrNull(),
            lastUpdated = LAST_UPDATED.find(html)?.groupValues?.get(1)?.toLongOrNull(),
        )
    }

    /** Maps a `confData`, or the API's prayer-times + config JSON (same field names). */
    fun parseConf(conf: JSONObject, slug: String, uuid: String? = null, id: Int? = null, lastUpdated: Long? = null): ParsedPage {
        val zone = conf.str("timezone")?.let { tz -> try { ZoneId.of(tz) } catch (e: DateTimeException) { null } }
            ?: ZoneId.systemDefault()
        val eidTimes = listOfNotNull(conf.str("aidPrayerTime"), conf.str("aidPrayerTime2"), conf.str("aidPrayerTime3"))
            .filter { MawaqitTimes.parseTime(it) != null }

        val times = MawaqitTimes(
            calendar = months(conf.opt("calendar")),
            iqamaCalendar = months(conf.opt("iqamaCalendar")),
            iqamaEnabled = conf.bool("iqamaEnabled", true),
            jumua = conf.str("jumua"),
            jumua2 = conf.str("jumua2"),
            jumua3 = conf.str("jumua3"),
            jumuaAsDuhr = conf.bool("jumuaAsDuhr", false),
            imsakMinBeforeFajr = conf.int("imsakNbMinBeforeFajr") ?: 0,
            zone = zone,
            eidTimes = eidTimes,
        )
        val mosque = Mosque(
            slug = slug,
            name = conf.str("label") ?: conf.str("name") ?: slug,
            address = conf.str("localisation"),
            zone = zone,
            latitude = conf.double("latitude") ?: 0.0,
            longitude = conf.double("longitude") ?: 0.0,
            countryCode = conf.str("countryCode"),
            uuid = uuid ?: conf.str("uuid"),
            mawaqitId = id ?: conf.int("id"),
            lastUpdated = lastUpdated,
            config = config(conf),
            announcements = conf.optJSONArray("announcements").objects()
                .filter { it.bool("isDesktop", true) }
                .mapNotNull { announcement(it, zone) },
            flash = flash(conf.optJSONObject("flash")),
            logoUrl = conf.str("logo"),
            imageUrl = conf.str("image") ?: conf.str("exteriorPicture"),
            streamUrl = conf.str("streamUrl"),
            site = conf.str("site"),
            phone = conf.str("phone"),
            paymentWebsite = conf.str("paymentWebsite"),
            hijriAdjustment = conf.int("hijriAdjustment") ?: 0,
            hijriForce30 = conf.bool("hijriDateForceTo30", false),
            eidTimes = eidTimes,
            closed = conf.str("closed"),
        )
        return ParsedPage(mosque, times, conf)
    }

    /** The API's `/2.0/mosque/search` response: an array of mosques. */
    fun parseSearch(json: String): List<SearchHit> {
        val array = try {
            JSONArray(json.trim())
        } catch (e: JSONException) {
            throw MawaqitPageException("Search response is not a JSON array", e)
        }
        return array.objects().mapNotNull { m ->
            val uuid = m.str("uuid") ?: return@mapNotNull null
            SearchHit(
                slug = m.str("slug"),
                uuid = uuid,
                name = m.str("label") ?: m.str("name") ?: uuid,
                address = m.str("localisation"),
                distanceM = m.int("proximity"),
            )
        }
    }

    /** The JSON object starting at the first `{` from [from], braces counted outside strings; null if unbalanced. */
    internal fun jsonObjectAt(text: String, from: Int): String? {
        val start = text.indexOf('{', from).takeIf { it >= 0 } ?: return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(start, i + 1)
            }
        }
        return null
    }

    private fun config(c: JSONObject): MosqueConfig {
        val d = MosqueConfig()
        val adhanEnabled = c.optJSONArray("adhanEnabledByPrayer")
        val prayerMinutes = c.optJSONArray("duaAfterPrayerShowTimes")
        return MosqueConfig(
            adhanEnabled = List(5) { i -> adhanEnabled?.let { truthy(it.opt(i)) } ?: d.adhanEnabled[i] },
            adhanVoice = c.str("adhanVoice"),
            adhanDurationSeconds = c.int("adhanDuration")?.takeIf { it > 0 } ?: d.adhanDurationSeconds,
            customAthanUrl = c.str("customAthan"),
            customAthanDurationSeconds = c.int("customAthanDuration")?.takeIf { it > 0 },
            duaAfterAdhan = c.bool("duaAfterAzanEnabled", d.duaAfterAdhan),
            duaAfterPrayer = c.bool("duaAfterPrayerEnabled", d.duaAfterPrayer),
            prayerMinutes = List(5) { i -> prayerMinutes?.let { toInt(it.opt(i)) } ?: d.prayerMinutes[i] },
            iqamaBeep = c.bool("iqamaBip", d.iqamaBeep),
            iqamaFullScreenCountdown = c.bool("iqamaFullScreenCountdown", d.iqamaFullScreenCountdown),
            iqamaDisplaySeconds = c.int("iqamaDisplayTime")?.takeIf { it >= 0 } ?: d.iqamaDisplaySeconds,
            iqamaMoreImportant = c.bool("iqamaMoreImportant", d.iqamaMoreImportant),
            alwaysHhMm = c.bool("alwaysDisplayHhMmFormat", d.alwaysHhMm),
            blackScreenWhenPraying = c.bool("blackScreenWhenPraying", d.blackScreenWhenPraying),
            jumuaMinutes = c.int("jumuaTimeout")?.takeIf { it > 0 } ?: d.jumuaMinutes,
            jumuaBlackScreen = c.bool("jumuaBlackScreenEnabled", d.jumuaBlackScreen),
            jumuaReminder = c.bool("jumuaDhikrReminderEnabled", d.jumuaReminder),
            wakeForFajrMinutes = c.int("wakeForFajrTime"),
            randomHadith = c.bool("randomHadithEnabled", d.randomHadith),
            hadithLang = c.str("hadithLang") ?: d.hadithLang,
            temperatureEnabled = c.bool("temperatureEnabled", d.temperatureEnabled),
            temperatureUnit = c.str("temperatureUnit") ?: d.temperatureUnit,
            clock24h = c.str("timeDisplayFormat") != "12",
            hijriEnabled = c.bool("hijriDateEnabled", d.hijriEnabled),
            theme = c.str("theme"),
            backgroundColor = c.str("backgroundColor"),
            backgroundUrl = c.str("backgroundUrl"),
            showLogo = c.bool("showLogo", d.showLogo),
        )
    }

    private fun announcement(a: JSONObject, zone: ZoneId): Announcement? {
        val id = a.opt("id")?.takeIf { it != JSONObject.NULL }?.toString() ?: return null
        return Announcement(
            id = id,
            title = a.str("title"),
            content = a.str("content"),
            imageUrl = a.str("image"),
            start = a.str("startDate")?.let { dateTime(it) }?.atZone(zone),
            end = a.str("endDate")?.let { dateTime(it) }?.atZone(zone),
            seconds = a.int("duration")?.takeIf { it > 0 } ?: 15,
        )
    }

    private fun flash(f: JSONObject?): Flash? {
        val text = f?.str("content") ?: return null
        return Flash(
            text = text,
            color = f.str("color")?.takeIf { COLOR.matches(it) } ?: Flash("").color,
            rtl = f.str("orientation") == "rtl",
            start = f.str("startDate")?.let { date(it) },
            end = f.str("endDate")?.let { date(it) },
        )
    }

    /** 12 months, each `{"1": [...], …}` (or an array, day 1 first) → day of month → times. */
    private fun months(value: Any?): List<Map<Int, List<String>>> {
        if (value !is JSONArray) return emptyList()
        return (0 until value.length()).map { m ->
            val days = HashMap<Int, List<String>>()
            when (val month = value.opt(m)) {
                is JSONObject -> for (key in month.keys()) {
                    val day = key.trim().toIntOrNull() ?: continue
                    month.optJSONArray(key)?.let { days[day] = strings(it) }
                }
                is JSONArray -> for (i in 0 until month.length()) month.optJSONArray(i)?.let { days[i + 1] = strings(it) }
            }
            days
        }
    }

    private fun strings(a: JSONArray) = List(a.length()) { i -> a.opt(i)?.takeIf { it != JSONObject.NULL }?.toString() ?: "" }

    /** "YYYY-MM-DD HH:MM[:SS]", "YYYY-MM-DDTHH:MM" or just "YYYY-MM-DD" (midnight). */
    private fun dateTime(s: String): LocalDateTime? = try {
        val t = s.trim().replace('T', ' ')
        if (t.length <= 10) LocalDate.parse(t).atStartOfDay() else LocalDateTime.parse(t.take(16).replace(' ', 'T'))
    } catch (e: DateTimeException) {
        null
    }

    private fun date(s: String): LocalDate? = try {
        LocalDate.parse(s.trim().take(10))
    } catch (e: DateTimeException) {
        null
    }

    /** A non-blank string, or null for missing, JSON null and "". */
    private fun JSONObject.str(key: String): String? =
        opt(key)?.takeIf { it != JSONObject.NULL && it !is JSONObject && it !is JSONArray }?.toString()?.trim()?.ifEmpty { null }

    private fun JSONObject.int(key: String): Int? = toInt(opt(key))

    private fun JSONObject.double(key: String): Double? = when (val v = opt(key)) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }?.takeIf { !it.isNaN() }

    private fun JSONObject.bool(key: String, default: Boolean): Boolean = truthy(opt(key)) ?: default

    private fun toInt(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull() ?: v.trim().toDoubleOrNull()?.toInt()
        else -> null
    }

    /** Mawaqit mixes true, 1 and "1". */
    private fun truthy(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> when (v.trim().lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off", "" -> false
            else -> null
        }
        else -> null
    }
}
