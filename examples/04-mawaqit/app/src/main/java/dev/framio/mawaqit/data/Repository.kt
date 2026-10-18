package dev.framio.mawaqit.data

import android.content.Context
import android.util.Log
import dev.framio.mawaqit.core.AdjustedTimes
import dev.framio.mawaqit.core.CalcTimes
import dev.framio.mawaqit.core.Hijri
import dev.framio.mawaqit.core.MawaqitPage
import dev.framio.mawaqit.core.ParsedPage
import dev.framio.mawaqit.core.Mosque
import dev.framio.mawaqit.core.PrayerDay
import dev.framio.mawaqit.core.PrayerKey
import dev.framio.mawaqit.core.PrayerTime
import dev.framio.mawaqit.core.SearchHit
import dev.framio.mawaqit.core.Settings
import dev.framio.mawaqit.core.SourceSettings
import dev.framio.mawaqit.core.TimesSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId

/** Where the prayer times come from right now, and how fresh they are. */
data class SourceState(
    val times: TimesSource? = null,
    val mosque: Mosque? = null,
    /** When the data was fetched (ms), null for computed times. */
    val updatedAt: Long? = null,
    /** "no_mosque", "not_found", "network", "parse" — with data still shown when a cached copy exists. */
    val error: String? = null,
    val loading: Boolean = false,
)

data class Weather(val temperature: Int, val unit: String, val icon: String, val day: Boolean)

data class Hadith(val text: String, val lang: String)

/**
 * Loads the mosque from its public Mawaqit page (no account needed) and keeps it fresh the way Mawaqit's own
 * screens do: a cheap "has it been updated?" check every 3 minutes, a full reload every 6 hours. The last page is
 * cached on disk, so a reboot without Wi-Fi still shows the times.
 */
class Repository(context: Context, private val settings: StateFlow<Settings>, private val scope: CoroutineScope) {

    private val cacheDir = File(context.filesDir, "mosque").apply { mkdirs() }
    private val _state = MutableStateFlow(SourceState(loading = true))
    val state: StateFlow<SourceState> = _state.asStateFlow()
    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather.asStateFlow()
    private val _hadith = MutableStateFlow<Hadith?>(null)
    val hadith: StateFlow<Hadith?> = _hadith.asStateFlow()

    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        scope.launch {
            settings.map { it.source }.distinctUntilChanged().collectLatest { source ->
                when (source.kind) {
                    "calc" -> _state.value = SourceState(times = calcTimes(source))
                    else -> followMosque(source)
                }
            }
        }
        scope.launch { weatherLoop() }
        scope.launch { hadithLoop() }
    }

    fun refresh() {
        refreshRequests.trySend(Unit)
    }

    private fun calcTimes(s: SourceSettings): TimesSource {
        val zone = s.timezone.takeIf { it.isNotEmpty() }?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        return CalcTimes(s, zone)
    }

    private suspend fun followMosque(source: SourceSettings) {
        val slug = source.mosque
        if (slug.isEmpty()) {
            _state.value = SourceState(error = "no_mosque")
            return
        }
        val cache = File(cacheDir, "$slug.html")
        var page = if (cache.exists()) parseOrNull(cache.readText(), slug) else null
        page?.let { publish(it, source, cache.lastModified(), error = null) } ?: run { _state.value = SourceState(loading = true) }

        var lastFull = if (page != null) cache.lastModified() else 0L
        var failures = 0
        while (true) {
            val due = System.currentTimeMillis() - lastFull > FULL_RELOAD_MS || page == null
            val updated = !due && page?.mosque?.let { hasBeenUpdated(it) } == true
            if (due || updated) {
                try {
                    val html = withContext(Dispatchers.IO) { Net.get(pageUrl(slug)) }
                    val fresh = MawaqitPage.parse(html, slug)
                    withContext(Dispatchers.IO) { cache.writeText(html) }
                    page = fresh
                    lastFull = System.currentTimeMillis()
                    failures = 0
                    publish(fresh, source, lastFull, error = null)
                    Log.i(TAG, "loaded $slug: ${fresh.mosque.name}")
                } catch (e: Exception) {
                    failures++
                    val code = when {
                        e is Net.HttpException && e.code == 404 -> "not_found"
                        e is java.io.IOException -> "network"
                        else -> "parse"
                    }
                    Log.w(TAG, "loading $slug failed ($code)", e)
                    _state.value = _state.value.copy(error = code, loading = false)
                }
            }
            // Retry sooner after a failure (30 s, 1, 2, 4 … min), else check for updates every 3 minutes
            val wait = if (failures > 0) (30_000L shl (failures - 1).coerceAtMost(4)) else CHECK_MS
            if (withTimeoutOrNull(wait) { refreshRequests.receive() } != null) lastFull = 0L
        }
    }

    private fun parseOrNull(html: String, slug: String) = try {
        MawaqitPage.parse(html, slug)
    } catch (e: Exception) {
        Log.w(TAG, "cached page unreadable", e)
        null
    }

    private fun publish(page: ParsedPage, source: SourceSettings, at: Long, error: String?) {
        val adjusted = AdjustedTimes(page.times, source.offsets, source.iqama.takeIf { source.overrideIqama })
        val withEid = EidTimes(adjusted, page.mosque)
        _state.value = SourceState(times = withEid, mosque = page.mosque, updatedAt = at, error = error)
    }

    /** Mawaqit's screens poll `/id/<id>/has-been-updated?lastUpdatedDate=<unix s>`: true after any back-office change. */
    private suspend fun hasBeenUpdated(m: Mosque): Boolean {
        val id = m.mawaqitId ?: return false
        return try {
            val body = withContext(Dispatchers.IO) { Net.get("$BASE/id/$id/has-been-updated?lastUpdatedDate=${m.lastUpdated ?: 0}", timeoutMs = 10_000) }
            JSONObject(body).optBoolean("hasBeenUpdated")
        } catch (e: Exception) {
            Log.d(TAG, "update check failed: ${e.message}")
            false
        }
    }

    private suspend fun weatherLoop() {
        while (true) {
            val s = settings.value
            if (s.display.showWeather) {
                _weather.value = try {
                    withContext(Dispatchers.IO) { fetchWeather(s) }
                } catch (e: Exception) {
                    Log.d(TAG, "weather failed: ${e.message}")
                    _weather.value
                }
            }
            delay(WEATHER_MS)
        }
    }

    private fun fetchWeather(s: Settings): Weather? {
        val unit = s.display.temperatureUnit
        val mosque = _state.value.mosque
        if (s.source.kind == "mawaqit" && mosque?.uuid != null) {
            // {"temperature":10,"unit":"C","feeling":"middle","icon":"sunny"}
            val j = JSONObject(Net.get("$BASE/api/2.0/mosque/${mosque.uuid}/weather?unit=$unit"))
            if (!j.has("temperature")) return null
            return Weather(j.getInt("temperature"), j.optString("unit", unit), iconOf(j.optString("icon")), day = true)
        }
        val lat = mosque?.latitude ?: s.source.latitude
        val lon = mosque?.longitude ?: s.source.longitude
        val u = if (unit == "F") "&temperature_unit=fahrenheit" else ""
        val j = JSONObject(Net.get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day$u"))
            .getJSONObject("current")
        return Weather(Math.round(j.getDouble("temperature_2m")).toInt(), unit, iconOfWmo(j.optInt("weather_code")), j.optInt("is_day", 1) == 1)
    }

    private suspend fun hadithLoop() {
        while (true) {
            val d = settings.value.display
            if (d.showHadith) {
                try {
                    val lang = URLEncoder.encode(d.hadithLang, "UTF-8")
                    val body = withContext(Dispatchers.IO) { Net.get("$BASE/api/2.0/hadith/random?lang=$lang&maxLength=420") }
                    if (body.isNotBlank()) {
                        val j = JSONObject(body)
                        _hadith.value = Hadith(j.getString("text").trim(), j.optString("lang", "ar"))
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "hadith failed: ${e.message}")
                }
            }
            delay(HADITH_MS)
        }
    }

    /** The official API's search: needs the user's free MAWAQIT token. */
    suspend fun search(token: String, word: String?, lat: Double?, lon: Double?): List<SearchHit> = withContext(Dispatchers.IO) {
        val q = if (lat != null && lon != null) "lat=$lat&lon=$lon" else "word=" + URLEncoder.encode(word.orEmpty(), "UTF-8")
        MawaqitPage.parseSearch(Net.get("$BASE/api/2.0/mosque/search?$q&itemsPerPage=10", mapOf("Api-Access-Token" to token, "Accept" to "application/json")))
    }

    /** Reads a mosque without switching to it: the panel's "Check" button. */
    suspend fun check(slug: String): Mosque = withContext(Dispatchers.IO) { MawaqitPage.parse(Net.get(pageUrl(slug)), slug).mosque }

    private fun pageUrl(slug: String) = "$BASE/en/$slug"

    private companion object {
        const val TAG = "MawaqitRepo"
        const val BASE = "https://mawaqit.net"
        const val CHECK_MS = 3 * 60_000L
        const val FULL_RELOAD_MS = 6 * 3_600_000L
        const val WEATHER_MS = 30 * 60_000L
        const val HADITH_MS = 10 * 60_000L

        fun iconOf(mawaqit: String) = when {
            "storm" in mawaqit || "thunder" in mawaqit -> "storm"
            "snow" in mawaqit -> "snow"
            "rain" in mawaqit || "shower" in mawaqit || "drizzle" in mawaqit -> "rain"
            "fog" in mawaqit || "mist" in mawaqit -> "fog"
            "cloud" in mawaqit -> "cloudy"
            else -> "clear"
        }

        /** WMO weather interpretation codes, as Open-Meteo returns them. */
        fun iconOfWmo(code: Int) = when (code) {
            0, 1 -> "clear"
            2, 3 -> "cloudy"
            45, 48 -> "fog"
            in 51..67, in 80..82 -> "rain"
            in 71..77, 85, 86 -> "snow"
            in 95..99 -> "storm"
            else -> "cloudy"
        }
    }
}

/**
 * Mawaqit publishes the Eid prayer times but not the Eid dates: they are 1 Shawwal and 10 Dhu al-Hijjah of the
 * mosque's Hijri calendar (same adjustment as its screens).
 */
private class EidTimes(private val base: TimesSource, private val mosque: Mosque) : TimesSource {
    override val zone: ZoneId get() = base.zone

    override fun day(date: LocalDate): PrayerDay? {
        val day = base.day(date) ?: return null
        if (mosque.eidTimes.isEmpty()) return day
        val h = Hijri.of(date, mosque.hijriAdjustment, mosque.hijriForce30)
        val eid = (h.month == 10 && h.day == 1) || (h.month == 12 && h.day == 10)
        if (!eid) return day
        val times = mosque.eidTimes.mapNotNull { t ->
            runCatching { java.time.LocalTime.parse(t) }.getOrNull()?.let { PrayerTime(PrayerKey.EID, date.atTime(it).atZone(zone)) }
        }
        return day.copy(eid = times)
    }
}
