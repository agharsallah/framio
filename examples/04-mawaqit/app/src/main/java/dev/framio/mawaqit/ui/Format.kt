package dev.framio.mawaqit.ui

import android.text.format.DateFormat
import dev.framio.mawaqit.core.PrayerTime
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {
    private val h24 = DateTimeFormatter.ofPattern("HH:mm")
    private val h12 = DateTimeFormatter.ofPattern("h:mm")
    private val seconds = DateTimeFormatter.ofPattern("ss")
    private val dates = HashMap<String, DateTimeFormatter>()

    fun time(t: ZonedDateTime, clock24h: Boolean): String = t.format(if (clock24h) h24 else h12)

    fun seconds(t: ZonedDateTime): String = t.format(seconds)

    fun amPm(t: ZonedDateTime): String = if (t.hour < 12) "AM" else "PM"

    /** "Sunday 18 October 2026", in the display's language and its usual order. */
    fun date(t: ZonedDateTime, lang: String): String = dates.getOrPut(lang) {
        val locale = Locale(lang)
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMMyyyy"), locale)
    }.format(t).replaceFirstChar { it.titlecase(Locale(lang)) }

    /** 1:05:09 above an hour, 05:09 below. Rounded up: "00:00" only when it's due. */
    fun countdown(d: Duration): String {
        val total = ((d.toMillis() + 999) / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%02d:%02d".format(Locale.ROOT, m, s)
    }

    /** "+10" when the mosque gives the iqama relative to the adhan and the display wants it so, else its time. */
    fun iqama(p: PrayerTime, asOffset: Boolean, clock24h: Boolean): String? {
        val off = p.iqamaOffset
        if (asOffset && off != null) return "+$off"
        return p.iqama?.let { time(it, clock24h) }
    }

    /** "+10" beside the iqama's time: its delay after the adhan. Null when [iqama] already shows "+10". */
    fun iqamaDelay(p: PrayerTime, asOffset: Boolean): String? {
        if (asOffset && p.iqamaOffset != null) return null
        val minutes = p.iqama?.let { Duration.between(p.adhan, it).toMinutes() } ?: return null
        return if (minutes > 0) "+$minutes" else null
    }
}
