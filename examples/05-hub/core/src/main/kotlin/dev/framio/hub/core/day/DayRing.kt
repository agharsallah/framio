package dev.framio.hub.core.day

import dev.framio.hub.core.feeds.RainSlot
import dev.framio.hub.core.time.SunTimes
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The Day Ring's geometry, ready to draw. A 24-hour dial with 00:00 at the bottom and 12:00 at the top, so
 * daylight sits on top like the sun's path. It always shows the next 24 hours: what lies just behind the
 * now marker is tomorrow.
 *
 * Angles are Canvas degrees: 0° is 3 o'clock, positive is clockwise. 00:00 is 90° (bottom).
 */
data class DayRing(
    val nowAngle: Float,
    /** Daylight and twilight on the track; night is the track itself. */
    val light: List<Arc>,
    val events: List<EventArc>,
    /** Events that didn't fit in [LANES] lanes: "+n" at their start. */
    val overflow: List<Overflow>,
    val rain: List<RainArc>,
    val timers: List<TimerMark>,
    /** Angle of the next local midnight. From there clockwise to the now marker, everything is tomorrow. */
    val midnightAngle: Float,
) {
    data class Arc(val start: Float, val sweep: Float, val kind: Light, val tomorrow: Boolean)

    data class EventArc(val id: String, val start: Float, val sweep: Float, val lane: Int, val color: Int, val badge: String, val tomorrow: Boolean)

    data class Overflow(val angle: Float, val count: Int, val tomorrow: Boolean)

    data class RainArc(val start: Float, val sweep: Float, val heavy: Boolean, val tomorrow: Boolean)

    data class TimerMark(val id: String, val angle: Float, val color: Int)

    enum class Light { Twilight, Day }

    companion object {
        const val LANES = 3
    }
}

/** An event to place: [badge] is the person's initial (colour is never the only cue). */
data class RingEvent(val id: String, val startMs: Long, val endMs: Long, val color: Int, val badge: String)

data class RingTimer(val id: String, val endMs: Long, val color: Int)

object DayRingModel {
    private const val DAY_MS = 24 * 3_600_000L

    /** The angle of a local wall-clock time: θ = 90° + minutes / 1440 × 360°. */
    fun angleOf(local: ZonedDateTime): Float {
        val minutes = local.hour * 60 + local.minute + local.second / 60f
        return norm(90f + minutes / 1440f * 360f)
    }

    fun angleOf(ms: Long, zone: ZoneId): Float = angleOf(Instant.ofEpochMilli(ms).atZone(zone))

    /**
     * Builds the ring for [nowMs]. [sun] holds the sun events of today and tomorrow (any order); events, rain
     * and timers are clipped to the next 24 hours. All-day events don't go on the ring.
     */
    fun build(
        nowMs: Long,
        zone: ZoneId,
        sun: List<SunTimes>,
        events: List<RingEvent>,
        rain: List<RainSlot>,
        timers: List<RingTimer>,
    ): DayRing {
        val end = nowMs + DAY_MS
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val window = Window(nowMs, end, midnight, zone)

        val light = mutableListOf<DayRing.Arc>()
        for (s in sun) {
            when {
                s.allDay == true -> light += window.arcs(s.noon.toEpochMilli() - DAY_MS / 2, s.noon.toEpochMilli() + DAY_MS / 2) { a, w, t -> DayRing.Arc(a, w, DayRing.Light.Day, t) }
                s.sunrise != null && s.sunset != null -> {
                    val rise = s.sunrise.toEpochMilli()
                    val set = s.sunset.toEpochMilli()
                    s.dawn?.let { light += window.arcs(it.toEpochMilli(), rise) { a, w, t -> DayRing.Arc(a, w, DayRing.Light.Twilight, t) } }
                    light += window.arcs(rise, set) { a, w, t -> DayRing.Arc(a, w, DayRing.Light.Day, t) }
                    s.dusk?.let { light += window.arcs(set, it.toEpochMilli()) { a, w, t -> DayRing.Arc(a, w, DayRing.Light.Twilight, t) } }
                }
            }
        }

        val placed = mutableListOf<DayRing.EventArc>()
        val overflow = mutableListOf<DayRing.Overflow>()
        val laneEnds = LongArray(DayRing.LANES) { Long.MIN_VALUE }
        for (e in events.filter { it.endMs > nowMs && it.startMs < end }.sortedWith(compareBy({ it.startMs }, { -it.endMs }))) {
            val start = maxOf(e.startMs, nowMs)
            val stop = minOf(maxOf(e.endMs, e.startMs + MIN_EVENT_MS), end)
            val lane = laneEnds.indexOfFirst { it <= start }
            if (lane < 0) {
                val angle = angleOf(start, zone)
                val i = overflow.indexOfFirst { kotlin.math.abs(it.angle - angle) < 6f }
                if (i >= 0) overflow[i] = overflow[i].copy(count = overflow[i].count + 1) else overflow += DayRing.Overflow(angle, 1, start >= midnight)
                continue
            }
            laneEnds[lane] = stop
            placed += window.arcs(start, stop) { a, w, t -> DayRing.EventArc(e.id, a, w, lane, e.color, e.badge, t) }
        }

        val rainArcs = rain.flatMap { r -> window.arcs(r.startMs, r.endMs) { a, w, t -> DayRing.RainArc(a, w, r.heavy, t) } }
        val marks = timers.filter { it.endMs in nowMs until end }.map { DayRing.TimerMark(it.id, angleOf(it.endMs, zone), it.color) }

        return DayRing(angleOf(now), light, placed, overflow, rainArcs, marks, angleOf(midnight, zone))
    }

    /** Short events still get a visible arc: 20 min = 5°. */
    private const val MIN_EVENT_MS = 20 * 60_000L

    private class Window(val from: Long, val to: Long, val midnight: Long, val zone: ZoneId) {
        /** Clips [start, end) to the window, splits it at midnight (today vs tomorrow) and makes arcs. */
        fun <T> arcs(start: Long, end: Long, make: (start: Float, sweep: Float, tomorrow: Boolean) -> T): List<T> {
            val s = maxOf(start, from)
            val e = minOf(end, to)
            if (e <= s) return emptyList()
            val parts = if (s < midnight && e > midnight) listOf(s to midnight, midnight to e) else listOf(s to e)
            return parts.mapNotNull { (a, b) ->
                val sweep = sweep(a, b)
                if (sweep <= 0f) null else make(angleOf(a, zone), sweep, a >= midnight)
            }
        }

        /** Clockwise degrees from a to b on the clock face (local wall time, so a DST night isn't drawn longer). */
        private fun sweep(a: Long, b: Long): Float {
            if (b - a >= DAY_MS) return 360f
            val d = norm(angleOf(b, zone) - angleOf(a, zone))
            return if (d == 0f && b > a) (b - a) / DAY_MS.toFloat() * 360f else d
        }
    }

    private fun norm(deg: Float): Float = ((deg % 360f) + 360f) % 360f
}
