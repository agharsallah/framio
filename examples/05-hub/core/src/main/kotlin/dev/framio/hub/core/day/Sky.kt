package dev.framio.hub.core.day

import dev.framio.hub.core.model.NightMode
import dev.framio.hub.core.model.NightSettings
import dev.framio.hub.core.model.TimeWindow
import dev.framio.hub.core.time.LocalTimes
import dev.framio.hub.core.time.contains
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.pow

/**
 * The backdrop: a dark vertical gradient chosen by the sun's elevation and the cloud cover. Every stop keeps
 * warm-white text at ≥ 7:1 contrast (relative luminance ≤ 0.07), which a unit test checks.
 */
object SkyPalette {
    enum class Phase { Night, BlueHour, GoldenHour, Day, Overcast }

    const val INK = 0xFFF1ECE4.toInt()

    fun phase(elevation: Double, cloudCover: Int?): Phase = when {
        elevation < -12 -> Phase.Night
        elevation < -4 -> Phase.BlueHour
        elevation < 6 -> Phase.GoldenHour
        (cloudCover ?: 0) > 70 -> Phase.Overcast
        else -> Phase.Day
    }

    /** Top and bottom colours, ARGB. */
    fun colors(phase: Phase): Pair<Int, Int> = when (phase) {
        Phase.Night -> 0xFF0B1020.toInt() to 0xFF141A2A.toInt()
        Phase.BlueHour -> 0xFF16213A.toInt() to 0xFF2A2440.toInt()
        Phase.GoldenHour -> 0xFF2B2A44.toInt() to 0xFF4A3326.toInt()
        Phase.Day -> 0xFF1E3550.toInt() to 0xFF2C4A63.toInt()
        Phase.Overcast -> 0xFF2A3038.toInt() to 0xFF343B44.toInt()
    }

    /** WCAG relative luminance of an ARGB colour. */
    fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}

/** Where the evening is: day, night (backlight off), or the morning ramp back to day brightness. */
sealed interface NightPhase {
    data object Day : NightPhase
    data object Night : NightPhase

    /** The last minutes of night: [level] goes from 0 (off) to 1 (day brightness). */
    data class Ramp(val level: Float) : NightPhase
}

object NightPlan {
    /** The night window for the given settings; sunset mode starts at sunset + offset (schedule if no sunset). */
    fun window(night: NightSettings, sunset: Instant?, local: ZonedDateTime): TimeWindow {
        val start = if (night.mode == NightMode.Sunset && sunset != null) {
            val t = sunset.atZone(local.zone).toLocalTime().plusMinutes(night.sunsetOffsetMin.toLong())
            String.format(Locale.ROOT, "%02d:%02d", t.hour, t.minute)
        } else {
            night.start
        }
        return TimeWindow(start, night.end)
    }

    fun phase(night: NightSettings, sunset: Instant?, local: ZonedDateTime): NightPhase {
        if (!night.enabled) return NightPhase.Day
        val window = window(night, sunset, local)
        val time = local.toLocalTime()
        if (!window.contains(time)) return NightPhase.Day
        val end = LocalTimes.parse(window.end) ?: return NightPhase.Night
        val minutesToEnd = Duration.between(time, end).toMinutes().let { if (it < 0) it + 1440 else it }
        val ramp = night.rampMin
        return if (ramp > 0 && minutesToEnd <= ramp) NightPhase.Ramp(1f - minutesToEnd.toFloat() / ramp) else NightPhase.Night
    }
}
