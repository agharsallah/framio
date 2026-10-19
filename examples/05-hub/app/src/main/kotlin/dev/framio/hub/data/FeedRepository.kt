package dev.framio.hub.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.AtomicFile
import dev.framio.hub.BuildConfig
import dev.framio.hub.core.engine.HubClock
import dev.framio.hub.core.engine.HubJson
import dev.framio.hub.core.engine.calendarSecret
import dev.framio.hub.core.feeds.AgendaEvent
import dev.framio.hub.core.feeds.Feeds
import dev.framio.hub.core.feeds.Ics
import dev.framio.hub.core.feeds.OpenMeteo
import dev.framio.hub.core.feeds.RefreshPolicy
import dev.framio.hub.core.model.HubState
import dev.framio.hub.platform.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Weather and calendars: everything the Hub reads from outside, derived (never owned) data. Keeps its last
 * good copy in `cacheDir/today.json`, so the first frame after a start shows real data without network.
 *
 * Each source fails on its own: a dead calendar leaves its events as they were and records an error; it never
 * blocks the weather or crashes anything. Refreshes every 15 min, backs off after failures (up to 2 h, with
 * jitter), and retries at once when the network comes back.
 */
class FeedRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    private val clock: HubClock,
    private val secrets: SecretsStore,
    private val state: StateFlow<HubState>,
    private val trusted: Flow<Boolean>,
    private val onHttpDate: (Long) -> Unit = {},
) {
    private val cache = AtomicFile(File(context.cacheDir, "today.json"))
    private val _feeds = MutableStateFlow(Feeds())
    val feeds: StateFlow<Feeds> = _feeds.asStateFlow()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var failures = 0

    /** Reads the cache now (small, fast) so the first frame has data; call before the UI subscribes. */
    fun loadCache() {
        try {
            if (cache.baseFile.exists()) _feeds.value = HubJson.decodeFromString(Feeds.serializer(), cache.readFully().decodeToString())
        } catch (e: Exception) {
            Logs.warn("feeds", "cache unreadable: ${e.message}")
        }
    }

    /** Starts refreshing. Waits for a trusted clock: TLS fails with the frame's boot-time clock anyway. */
    fun start() {
        scope.launch {
            trusted.first { it }
            watchNetwork()
            // a change of location or calendars refreshes at once
            scope.launch {
                state.map { it.settings.location to it.calendars }.distinctUntilChanged().collect { wake.trySend(Unit) }
            }
            while (true) {
                val ok = refresh()
                failures = if (ok) 0 else failures + 1
                val wait = RefreshPolicy.nextDelayMs(failures, Random.nextDouble())
                withTimeoutOrNull(wait) { wake.receive() }
            }
        }
    }

    fun refreshNow() {
        wake.trySend(Unit)
    }

    /** Fetches every source; true when all of them worked. */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val s = state.value
        val now = clock.now().wallMs ?: return@withContext false
        val errors = HashMap(_feeds.value.errors)
        var allOk = true
        supervisorScope {
            // weather
            val location = s.settings.location
            if (location != null) {
                try {
                    val body = get(OpenMeteo.url(location))
                    val weather = OpenMeteo.parse(body, now)
                    _feeds.value = _feeds.value.copy(weather = weather)
                    errors.remove("weather")
                    Logs.event("feeds", "weather ok: ${weather.hourly.size} h, ${weather.quarters.size} quarters")
                } catch (e: Exception) {
                    allOk = false
                    errors["weather"] = short(e)
                    Logs.warn("feeds", "weather failed: ${short(e)}")
                }
            }
            // calendars, one by one; [now − 1 h, now + 8 days]
            val from = now - 3_600_000
            val to = now + 8 * 24 * 3_600_000L
            val events = _feeds.value.events.filter { e -> s.calendars.any { it.id == e.calendarId } }.toMutableList()
            val fetched = HashMap(_feeds.value.calendarsFetched)
            for (cal in s.calendars) {
                val url = secrets.get(calendarSecret(cal.id)) ?: continue
                try {
                    val body = get(url, maxBytes = MAX_ICS_BYTES)
                    val parsed = withContext(Dispatchers.Default) { Ics.parse(body, cal.id, from, to, clock.now().zone) }
                    events.removeAll { it.calendarId == cal.id }
                    events += parsed
                    fetched[cal.id] = now
                    errors.remove("calendar.${cal.id}")
                    Logs.event("feeds", "calendar ${cal.id} ok: ${parsed.size} events from ${Logs.redact(url)}")
                } catch (e: Exception) {
                    allOk = false
                    errors["calendar.${cal.id}"] = short(e)
                    Logs.warn("feeds", "calendar ${cal.id} failed: ${short(e)}")
                }
            }
            errors.keys.removeAll { k -> k.startsWith("calendar.") && s.calendars.none { "calendar.${it.id}" == k } }
            _feeds.value = _feeds.value.copy(
                events = events.sortedBy(AgendaEvent::startMs),
                calendarsFetched = fetched.filterKeys { id -> s.calendars.any { it.id == id } },
                errors = errors,
            )
        }
        save()
        allOk
    }

    private fun get(url: String, maxBytes: Long = 2_000_000): String {
        val request = Request.Builder().url(url).header("User-Agent", "FramioHub/${BuildConfig.VERSION_NAME}").build()
        http.newCall(request).execute().use { response ->
            response.headers.getDate("Date")?.let { onHttpDate(it.time) }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            if (body.contentLength() > maxBytes) throw IOException("too large (${body.contentLength()} bytes)")
            val source = body.source()
            source.request(maxBytes + 1)
            if (source.buffer.size > maxBytes) throw IOException("too large")
            return source.readUtf8()
        }
    }

    private fun save() {
        val out = cache.startWrite()
        try {
            out.write(HubJson.encodeToString(Feeds.serializer(), _feeds.value).encodeToByteArray())
            cache.finishWrite(out)
        } catch (e: IOException) {
            cache.failWrite(out)
        }
    }

    private fun watchNetwork() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (failures > 0) {
                        Logs.event("feeds", "network back, refreshing")
                        wake.trySend(Unit)
                    }
                }
            },
        )
    }

    private fun short(e: Exception) = (e.message ?: e.javaClass.simpleName).take(120)

    companion object {
        /** A family calendar is tens of KB; 4 MB means something is wrong. */
        const val MAX_ICS_BYTES = 4_000_000L

        fun client(context: Context): OkHttpClient = OkHttpClient.Builder()
            .cache(Cache(File(context.cacheDir, "http"), 10L * 1024 * 1024)) // ETag/If-Modified-Since: a 304 costs almost nothing
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
    }
}
