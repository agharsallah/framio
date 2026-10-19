package dev.framio.meadow.core

import java.util.Calendar

/**
 * Wall-clock helpers. The frame has no RTC: it boots at 1 January until Wi-Fi brings NTP time, so
 * anything time-of-day based (bedtime, "today") first checks [plausible]. Pure, unit-tested.
 */
object Clock {

    /** Earliest wall-clock time we believe: anything before is the unsynced boot clock. */
    private const val SANE_SINCE_MS = 1_735_689_600_000L // 2025-01-01

    fun plausible(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= SANE_SINCE_MS

    /** "HH:MM" → minutes after midnight, or null. */
    fun parseMinutes(s: String): Int? {
        val parts = s.trim().split(':')
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        return if (h in 0..23 && m in 0..59) h * 60 + m else null
    }

    fun minuteOfDay(nowMs: Long = System.currentTimeMillis()): Int {
        val c = Calendar.getInstance().apply { timeInMillis = nowMs }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** Is [now] (minutes after midnight) inside the night window [bed, wake)? Handles windows across midnight. */
    fun inNight(now: Int, bed: Int, wake: Int): Boolean = when {
        bed == wake -> false
        bed < wake -> now in bed until wake
        else -> now >= bed || now < wake
    }

    /** Local calendar day as "yyyy-MM-dd", the key for daily play time. */
    fun dayKey(nowMs: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance().apply { timeInMillis = nowMs }
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** Morning, afternoon, evening or night, for greetings. */
    fun partOfDay(minute: Int): PartOfDay = when (minute) {
        in 5 * 60 until 12 * 60 -> PartOfDay.Morning
        in 12 * 60 until 17 * 60 -> PartOfDay.Afternoon
        in 17 * 60 until 21 * 60 -> PartOfDay.Evening
        else -> PartOfDay.Night
    }
}

enum class PartOfDay { Morning, Afternoon, Evening, Night }
