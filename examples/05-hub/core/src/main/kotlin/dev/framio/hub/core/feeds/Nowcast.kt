package dev.framio.hub.core.feeds

/** A stretch of time with precipitation, for the dots on the ring. */
data class RainSlot(val startMs: Long, val endMs: Long, val mmPerHour: Double, val heavy: Boolean)

/** The weather part of the day sentence, as structure; `:app` turns it into words in the right language. */
sealed interface WeatherLine {
    /** It's raining now; dry from [untilMs], or null if not within the horizon. */
    data class RainUntil(val untilMs: Long?, val kind: WeatherKind) : WeatherLine

    /** Dry now, wet from [atMs]. [soon] when it's within the 15-minute nowcast's two hours: "Rain from 08:15". */
    data class RainFrom(val atMs: Long, val kind: WeatherKind, val soon: Boolean) : WeatherLine

    /** Dry for at least the next [hours]. */
    data class Dry(val hours: Int) : WeatherLine

    /** Tonight's low is at or below 0°. */
    data class Frost(val min: Double) : WeatherLine
}

/**
 * Turns the forecast into rain windows and the weather clause. Uses Open-Meteo's 15-minute precipitation
 * where it has it, hourly data beyond. A quarter counts as wet from 0.05 mm (0.2 mm/h); an hour without
 * quarter data counts as wet from 0.2 mm or a 50 % probability.
 */
object Nowcast {
    const val QUARTER_MS = 15 * 60_000L
    const val HORIZON_MS = 12 * 3_600_000L
    const val SOON_MS = 2 * 3_600_000L
    private const val WET_QUARTER_MM = 0.05
    private const val WET_HOUR_MM = 0.2
    private const val WET_PROBABILITY = 50
    private const val HEAVY_MM_PER_HOUR = 2.5

    /** Wet stretches between [fromMs] and [toMs], merged, at 15-minute resolution. */
    fun slots(w: Weather, fromMs: Long, toMs: Long): List<RainSlot> {
        val raw = mutableListOf<RainSlot>()
        val quarterEnd = w.quarters.lastOrNull()?.atMs?.plus(QUARTER_MS) ?: Long.MIN_VALUE
        for (q in w.quarters) {
            if (q.atMs + QUARTER_MS <= fromMs || q.atMs >= toMs) continue
            if (q.precipitationMm >= WET_QUARTER_MM) raw += RainSlot(q.atMs, q.atMs + QUARTER_MS, q.precipitationMm * 4, q.precipitationMm * 4 >= HEAVY_MM_PER_HOUR)
        }
        for (h in w.hourly) {
            val end = h.atMs + 3_600_000
            if (end <= maxOf(fromMs, quarterEnd) || h.atMs >= toMs) continue
            val mm = h.precipitationMm ?: 0.0
            if (mm >= WET_HOUR_MM || (h.probability ?: 0) >= WET_PROBABILITY) {
                raw += RainSlot(maxOf(h.atMs, quarterEnd), end, mm, mm >= HEAVY_MM_PER_HOUR)
            }
        }
        return merge(raw.sortedBy { it.startMs }).map { it.copy(startMs = maxOf(it.startMs, fromMs), endMs = minOf(it.endMs, toMs)) }
    }

    private fun merge(sorted: List<RainSlot>): List<RainSlot> {
        val out = mutableListOf<RainSlot>()
        for (s in sorted) {
            val last = out.lastOrNull()
            if (last != null && s.startMs <= last.endMs) {
                out[out.lastIndex] = last.copy(endMs = maxOf(last.endMs, s.endMs), mmPerHour = maxOf(last.mmPerHour, s.mmPerHour), heavy = last.heavy || s.heavy)
            } else {
                out += s
            }
        }
        return out
    }

    /** The weather clause for [nowMs], or null without data. */
    fun line(w: Weather, nowMs: Long): WeatherLine? {
        if (w.hourly.isEmpty() && w.quarters.isEmpty()) return null
        val slots = slots(w, nowMs, nowMs + HORIZON_MS)
        val wetNow = slots.firstOrNull()?.let { it.startMs <= nowMs } ?: false
        return if (wetNow) {
            WeatherLine.RainUntil(slots.first().endMs.takeIf { it < nowMs + HORIZON_MS }, kindAt(w, nowMs))
        } else {
            val next = slots.firstOrNull()
            if (next == null) WeatherLine.Dry((HORIZON_MS / 3_600_000).toInt()) else WeatherLine.RainFrom(next.startMs, kindAt(w, next.startMs), next.startMs - nowMs <= SOON_MS)
        }
    }

    /** "Frost tonight" from mid-afternoon on, when the night's low is at or below 0°. */
    fun frost(w: Weather, nowMs: Long, localHour: Int): WeatherLine.Frost? {
        if (localHour < 15) return null
        val tonight = w.hourly.filter { it.atMs in nowMs..nowMs + HORIZON_MS }.mapNotNull { it.temperature }.minOrNull() ?: return null
        return if (tonight <= 0.0) WeatherLine.Frost(tonight) else null
    }

    /** What falls at [ms]: snow, showers or rain; rain when the code says it's dry (the amount says otherwise). */
    private fun kindAt(w: Weather, ms: Long): WeatherKind {
        val kind = WeatherKind.of(w.hourAt(ms)?.code)
        return if (kind.isWet) kind else WeatherKind.Rain
    }

    /** Hours since the forecast was fetched; the Hub calls it stale after 2. */
    fun ageMs(w: Weather, nowWallMs: Long) = nowWallMs - w.fetchedWallMs

    const val STALE_MS = 2 * 3_600_000L
}
