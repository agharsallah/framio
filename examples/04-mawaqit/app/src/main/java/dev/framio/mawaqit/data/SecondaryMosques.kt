package dev.framio.mawaqit.data

import android.content.Context
import android.util.Log
import dev.framio.mawaqit.core.MawaqitPage
import dev.framio.mawaqit.core.Secondary
import dev.framio.mawaqit.core.SecondaryEntry
import dev.framio.mawaqit.core.Settings
import dev.framio.mawaqit.core.TimesSource
import kotlinx.coroutines.CancellationException
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
import java.io.File
import java.time.Instant

/**
 * The secondary mosques (`source.secondary`): read from their public Mawaqit pages like the main one, cached in
 * `files/mosque/<slug>.html`, reloaded every 6 hours. Display only, so no "has-been-updated" polling: three more
 * mosques polled every 3 minutes would triple the frame's traffic for times that change once a year. Fetches are
 * spaced [STAGGER_MS] apart; a failure keeps the cached times and marks the entry with its error.
 *
 * Only the mosque's name and its calendars stay in memory (the page's confData and announcements are dropped).
 * Everything here runs on the main thread, network and parsing on [Dispatchers.IO].
 */
class SecondaryMosques(context: Context, private val settings: StateFlow<Settings>, private val scope: CoroutineScope) {

    private val dir = File(context.filesDir, "mosque").apply { mkdirs() }
    private val _entries = MutableStateFlow<List<SecondaryEntry>>(emptyList())
    val entries: StateFlow<List<SecondaryEntry>> = _entries.asStateFlow()

    private class Slot(val slug: String) {
        var name = slug
        var times: TimesSource? = null
        var updatedAt: Long? = null
        var error: String? = null
        var failures = 0
        var lastTry = 0L
        var loading = false
        var cacheRead = false
    }

    private var slots: List<Slot> = emptyList()
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        scope.launch {
            settings.map { Secondary.slugs(it.source) }.distinctUntilChanged().collectLatest { slugs ->
                val old = slots.associateBy { it.slug }
                val main = settings.value.source.mosque
                val dropped = old.keys - slugs.toSet() - main
                slots = slugs.map { old[it] ?: Slot(it) }
                withContext(Dispatchers.IO) { dropped.forEach { File(dir, "$it.html").delete() } }
                slots.filter { !it.cacheRead }.forEach { readCache(it) }
                publish()
                loop()
            }
        }
        // Each minute, just after it starts: the next prayer changes on a minute boundary
        scope.launch {
            while (true) {
                delay(60_000L - System.currentTimeMillis() % 60_000L + 500L)
                publish()
            }
        }
    }

    /** Reload every secondary mosque now (still spaced out): the panel's "Refresh data". */
    fun refresh() {
        refreshRequests.trySend(Unit)
    }

    private suspend fun loop() {
        var force = false
        while (true) {
            var fetched = false
            for (slot in slots) {
                if (!force && System.currentTimeMillis() < Secondary.nextAttempt(slot.updatedAt, slot.failures, slot.lastTry)) continue
                if (fetched) delay(STAGGER_MS)
                fetch(slot)
                fetched = true
            }
            force = false
            val next = slots.minOfOrNull { Secondary.nextAttempt(it.updatedAt, it.failures, it.lastTry) } ?: Long.MAX_VALUE
            val wait = (next - System.currentTimeMillis()).coerceIn(1_000L, Secondary.FULL_RELOAD_MS)
            if (withTimeoutOrNull(wait) { refreshRequests.receive() } != null) force = true
        }
    }

    private suspend fun readCache(slot: Slot) {
        slot.cacheRead = true
        val cache = File(dir, "${slot.slug}.html")
        val page = withContext(Dispatchers.IO) {
            if (!cache.exists()) null else try {
                MawaqitPage.parse(cache.readText(), slot.slug) to cache.lastModified()
            } catch (e: Exception) {
                Log.w(TAG, "cached ${slot.slug} unreadable", e)
                null
            }
        } ?: return
        slot.name = page.first.mosque.name
        slot.times = page.first.times
        slot.updatedAt = page.second
    }

    private suspend fun fetch(slot: Slot) {
        slot.lastTry = System.currentTimeMillis()
        slot.loading = true
        publish()
        try {
            val page = withContext(Dispatchers.IO) {
                val html = Net.get("$BASE/en/${slot.slug}")
                MawaqitPage.parse(html, slot.slug).also { File(dir, "${slot.slug}.html").writeText(html) }
            }
            slot.name = page.mosque.name
            slot.times = page.times
            slot.updatedAt = System.currentTimeMillis()
            slot.error = null
            slot.failures = 0
            Log.i(TAG, "loaded ${slot.slug}: ${slot.name}")
        } catch (e: CancellationException) {
            throw e // the list changed: not a failure of this mosque
        } catch (e: Exception) {
            slot.failures++
            slot.error = when {
                e is Net.HttpException && e.code == 404 -> "not_found"
                e is java.io.IOException -> "network"
                else -> "parse"
            }
            Log.w(TAG, "loading ${slot.slug} failed (${slot.error}), retry in ${Secondary.retryDelayMs(slot.failures) / 1000} s")
        } finally {
            slot.loading = false
            publish()
        }
    }

    private fun publish() {
        val now = Instant.now()
        _entries.value = slots.map { Secondary.entry(it.slug, it.name, it.times, now, it.updatedAt, it.error, it.loading) }
    }

    private companion object {
        const val TAG = "MawaqitSecondary"
        const val BASE = "https://mawaqit.net"
        const val STAGGER_MS = 15_000L
    }
}
