package dev.framio.pomodoro.core.time

import java.time.Instant
import java.time.LocalTime

/**
 * The part of the day the break landscape is painted for (its sky and light). Night starts at the evening start
 * of the settings (20:30 by default, when the theme turns warm, PLAN §3 principle 13), dusk three hours before it.
 */
public enum class DayPart {
    Dawn,
    Day,
    Dusk,
    Night,
    ;

    public companion object {
        private val DAWN: LocalTime = LocalTime.of(5, 0)
        private val DAY: LocalTime = LocalTime.of(8, 0)
        private val DEFAULT_EVENING: LocalTime = LocalTime.of(20, 30)

        /** [time] of day, with night from [evening] (null: the default 20:30) until 05:00. */
        public fun at(time: LocalTime, evening: LocalTime? = null): DayPart {
            val night = (evening ?: DEFAULT_EVENING).let { if (it.isBefore(DAY.plusHours(3))) DEFAULT_EVENING else it }
            val dusk = night.minusHours(3)
            return when {
                time.isBefore(DAWN) -> Night
                time.isBefore(DAY) -> Dawn
                time.isBefore(dusk) -> Day
                time.isBefore(night) -> Dusk
                else -> Night
            }
        }

        /** The part of the day [now], or [Day] while the wall clock isn't trusted yet. */
        public fun of(now: Now, evening: LocalTime? = null): DayPart {
            val wall = now.wallMs ?: return Day
            return at(Instant.ofEpochMilli(wall).atZone(now.zone).toLocalTime(), evening)
        }
    }
}
