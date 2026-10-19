package dev.framio.hub.core.feeds

import kotlinx.serialization.Serializable
import kotlin.math.min
import kotlin.math.pow

/**
 * Everything fetched from outside: weather and calendar occurrences, with when each was fetched and the
 * last error per source. Cached as `today.json`, so the first frame after a start has real data.
 */
@Serializable
data class Feeds(
    val weather: Weather? = null,
    val events: List<AgendaEvent> = emptyList(),
    /** Calendar id → when it was last fetched successfully (wall ms). */
    val calendarsFetched: Map<String, Long> = emptyMap(),
    /** Source ("weather", "calendar.<id>") → a short error for Diagnostics. */
    val errors: Map<String, String> = emptyMap(),
)

/**
 * When to fetch again: every 15 min after a success; after failures, 1, 2, 4… min up to 2 h, ±20 % jitter,
 * so a dozen frames on one network never retry in step. [random] is 0..1, injected for tests.
 */
object RefreshPolicy {
    const val EVERY_MS = 15 * 60_000L
    const val MAX_BACKOFF_MS = 2 * 3_600_000L
    private const val FIRST_RETRY_MS = 60_000L

    fun nextDelayMs(consecutiveFailures: Int, random: Double): Long {
        val base = if (consecutiveFailures <= 0) {
            EVERY_MS
        } else {
            min(MAX_BACKOFF_MS.toDouble(), FIRST_RETRY_MS * 2.0.pow(consecutiveFailures - 1)).toLong()
        }
        val jitter = 1 + (random.coerceIn(0.0, 1.0) * 0.4 - 0.2)
        return (base * jitter).toLong()
    }
}
