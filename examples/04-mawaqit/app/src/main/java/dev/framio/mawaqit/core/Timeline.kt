package dev.framio.mawaqit.core

import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * The screen's state machine: which [Phase] is shown at a given time.
 *
 * Around each of the five prayers: REMINDER → ADHAN → DUA_AFTER_ADHAN → IQAMA_COUNTDOWN → IQAMA → PRAYER → AZKAR,
 * each skipped when disabled or empty. On a Friday with Jumu'a, the Dhuhr flow is REMINDER → JUMUA → AZKAR from the
 * first Jumu'a (no adhan screen at the Dhuhr time). A flow is cut where the next prayer's flow begins. NIGHT fills
 * what the flows leave free between Isha + [Behavior.nightAfterIshaMinutes] and the next Fajr −
 * [Behavior.nightBeforeFajrMinutes]; everything else is NORMAL.
 *
 * Phases ignore [Behavior.adhanEnabled]: that only mutes the adhan, the screens stay.
 */
object Timeline {
    private class Segment(val kind: PhaseKind, val prayer: PrayerTime?, val start: ZonedDateTime, val end: ZonedDateTime)

    private class Cache(val date: LocalDate, val source: TimesSource, val behavior: Behavior, val segments: List<Segment>)

    // Called every second: the segments of yesterday…tomorrow are built once per day, source and behavior.
    @Volatile
    private var cache: Cache? = null

    /** The phase at [now]; its start and end are exact (null when nothing bounds it within yesterday…tomorrow). */
    fun phaseAt(now: ZonedDateTime, source: TimesSource, b: Behavior): Phase {
        val at = now.withZoneSameInstant(source.zone)
        var previousEnd: ZonedDateTime? = null
        for (s in segments(at.toLocalDate(), source, b)) {
            if (at.isBefore(s.start)) return Phase(PhaseKind.NORMAL, null, previousEnd, s.start)
            if (at.isBefore(s.end)) return Phase(s.kind, s.prayer, s.start, s.end)
            previousEnd = s.end
        }
        return Phase(PhaseKind.NORMAL, null, previousEnd, null)
    }

    /**
     * The next of Fajr, Dhuhr, Asr, Maghrib and Isha by adhan after [now], with the first Jumu'a instead of Dhuhr on
     * Fridays. From yesterday, for an Isha after midnight.
     */
    fun nextPrayer(now: ZonedDateTime, source: TimesSource): PrayerTime? {
        val today = now.withZoneSameInstant(source.zone).toLocalDate()
        return (-1L..2L).asSequence()
            .mapNotNull { source.day(today.plusDays(it)) }
            .flatMap { d -> sequenceOf(d.fajr, d.jumua.firstOrNull() ?: d.dhuhr, d.asr, d.maghrib, d.isha) }
            .filterNotNull()
            .filter { it.adhan.isAfter(now) }
            .minByOrNull { it.adhan.toInstant() }
    }

    private fun segments(today: LocalDate, source: TimesSource, b: Behavior): List<Segment> {
        cache?.let { if (it.date == today && it.source === source && it.behavior == b) return it.segments }
        return build(today, source, b).also { cache = Cache(today, source, b, it) }
    }

    private fun build(today: LocalDate, source: TimesSource, b: Behavior): List<Segment> {
        val days = (-1L..1L).mapNotNull { source.day(today.plusDays(it)) }
        val flows = days.flatMap { flows(it, b) }.sortedBy { it.first().start.toInstant() }

        val out = ArrayList<Segment>()
        for ((i, flow) in flows.withIndex()) {
            val cut = flows.getOrNull(i + 1)?.first()?.start
            for (s in flow) {
                if (cut != null && !s.start.isBefore(cut)) break
                out += if (cut != null && s.end.isAfter(cut)) Segment(s.kind, s.prayer, s.start, cut) else s
            }
        }
        if (b.nightMode == NightMode.OFF) return out

        val nights = ArrayList<Segment>()
        for (d in days) {
            val isha = d.isha ?: continue
            val fajr = days.firstOrNull { it.date == d.date.plusDays(1) }?.fajr ?: continue
            val start = isha.adhan.plusMinutes(b.nightAfterIshaMinutes.toLong())
            val end = fajr.adhan.minusMinutes(b.nightBeforeFajrMinutes.toLong())
            if (start.isBefore(end)) nights += free(start, end, out)
        }
        return (out + nights).sortedBy { it.start.toInstant() }
    }

    /** NIGHT over [start, end) minus the prayer flows in [busy] (sorted, not overlapping). */
    private fun free(start: ZonedDateTime, end: ZonedDateTime, busy: List<Segment>): List<Segment> {
        val pieces = ArrayList<Segment>()
        var cursor = start
        for (s in busy) {
            if (!s.end.isAfter(cursor)) continue
            if (!s.start.isBefore(end)) break
            if (s.start.isAfter(cursor)) pieces += Segment(PhaseKind.NIGHT, null, cursor, s.start)
            cursor = s.end
        }
        if (cursor.isBefore(end)) pieces += Segment(PhaseKind.NIGHT, null, cursor, end)
        return pieces
    }

    private fun flows(day: PrayerDay, b: Behavior): List<List<Segment>> =
        PrayerKey.FIVE.indices.mapNotNull { i ->
            val jumua = day.jumua.firstOrNull()
            if (i == 1 && jumua != null) jumuaFlow(jumua, b) else day.five[i]?.let { prayerFlow(it, b) }
        }.filter { it.isNotEmpty() }

    private fun prayerFlow(p: PrayerTime, b: Behavior): List<Segment> {
        val out = ArrayList<Segment>()
        fun add(kind: PhaseKind, start: ZonedDateTime, end: ZonedDateTime) {
            if (start.isBefore(end)) out += Segment(kind, p, start, end)
        }
        val adhan = p.adhan
        if (b.reminderMinutes > 0 && b.reminderPrayers.getOrElse(p.key.five) { true }) add(PhaseKind.REMINDER, adhan.minusMinutes(b.reminderMinutes.toLong()), adhan)
        var t = adhan
        if (b.adhanScreen) t = t.plusSeconds(b.adhanSeconds.toLong()).also { add(PhaseKind.ADHAN, t, it) }
        if (b.duaAfterAdhan) t = t.plusSeconds(b.duaSeconds.toLong()).also { add(PhaseKind.DUA_AFTER_ADHAN, t, it) }

        val prayerStart: ZonedDateTime
        val iqama = p.iqama
        if (iqama != null) {
            if (b.iqamaCountdown) add(PhaseKind.IQAMA_COUNTDOWN, t, iqama)
            // An iqama during the adhan screens (like Maghrib "+0") comes right after them.
            prayerStart = later(iqama, t)
            t = prayerStart.plusSeconds(b.iqamaSeconds.toLong()).also { add(PhaseKind.IQAMA, prayerStart, it) }
        } else {
            prayerStart = t
        }
        val prayerEnd = prayerStart.plusMinutes(b.prayerMinutes.getOrElse(p.key.five) { 0 }.toLong())
        add(PhaseKind.PRAYER, t, prayerEnd)
        t = later(t, prayerEnd)
        if (b.azkar) add(PhaseKind.AZKAR, t, t.plusMinutes(b.azkarMinutes.toLong()))
        return out
    }

    private fun jumuaFlow(j: PrayerTime, b: Behavior): List<Segment> {
        val out = ArrayList<Segment>()
        fun add(kind: PhaseKind, start: ZonedDateTime, end: ZonedDateTime) {
            if (start.isBefore(end)) out += Segment(kind, j, start, end)
        }
        if (b.reminderMinutes > 0 && b.reminderPrayers.getOrElse(PrayerKey.DHUHR.five) { true }) add(PhaseKind.REMINDER, j.adhan.minusMinutes(b.reminderMinutes.toLong()), j.adhan)
        val end = j.adhan.plusMinutes(b.jumuaMinutes.toLong())
        add(PhaseKind.JUMUA, j.adhan, end)
        if (b.azkar) add(PhaseKind.AZKAR, end, end.plusMinutes(b.azkarMinutes.toLong()))
        return out
    }

    private fun later(a: ZonedDateTime, b: ZonedDateTime) = if (a.isAfter(b)) a else b
}
