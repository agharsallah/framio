package dev.framio.hub.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** A local day's sun events. Null where the sun never crosses that height (polar day or night). */
data class SunTimes(
    val dawn: Instant?,
    val sunrise: Instant?,
    val noon: Instant,
    val sunset: Instant?,
    val dusk: Instant?,
    /** True when the sun stays up all day (polar day), false when it stays down; null on normal days. */
    val allDay: Boolean? = null,
)

/**
 * NOAA's solar position algorithm (the one behind its Solar Calculator spreadsheet): sunrise, sunset, civil
 * twilight and the sun's elevation, computed offline from the coordinates. Good to about a minute between
 * ±72° latitude, which is all the sky colours and the daylight arc need.
 */
object Sun {
    private const val SUNRISE_ZENITH = 90.833 // refraction and the sun's radius
    private const val CIVIL_ZENITH = 96.0

    /** The sun's elevation above the horizon, in degrees, at [instant] (no refraction). */
    fun elevation(instant: Instant, latitude: Double, longitude: Double): Double {
        val ms = instant.toEpochMilli()
        val jd = ms / 86_400_000.0 + 2_440_587.5
        val (decl, eqTime) = declinationAndEquationOfTime(jd)
        val utcMinutes = ((ms % 86_400_000 + 86_400_000) % 86_400_000) / 60_000.0
        val trueSolarTime = ((utcMinutes + eqTime + 4 * longitude) % 1440 + 1440) % 1440
        val hourAngle = if (trueSolarTime / 4 < 0) trueSolarTime / 4 + 180 else trueSolarTime / 4 - 180
        val lat = rad(latitude)
        val cosZenith = sin(lat) * sin(decl) + cos(lat) * cos(decl) * cos(rad(hourAngle))
        return 90 - deg(acos(cosZenith.coerceIn(-1.0, 1.0)))
    }

    /** Sun events for the local calendar [date] in [zone]. */
    fun times(date: LocalDate, latitude: Double, longitude: Double, zone: ZoneId): SunTimes {
        // Work from the UTC day whose solar noon falls on the local date (matters far from Greenwich)
        var utcDate = date
        var noon = solarNoon(utcDate, longitude)
        val local = noon.atZone(zone).toLocalDate()
        if (local != date) {
            utcDate = if (local.isAfter(date)) date.minusDays(1) else date.plusDays(1)
            noon = solarNoon(utcDate, longitude)
        }
        val sunrise = event(utcDate, latitude, longitude, SUNRISE_ZENITH, rising = true)
        val sunset = event(utcDate, latitude, longitude, SUNRISE_ZENITH, rising = false)
        val allDay = if (sunrise == null) elevation(noon, latitude, longitude) > 0 else null
        return SunTimes(
            dawn = event(utcDate, latitude, longitude, CIVIL_ZENITH, rising = true),
            sunrise = sunrise,
            noon = noon,
            sunset = sunset,
            dusk = event(utcDate, latitude, longitude, CIVIL_ZENITH, rising = false),
            allDay = allDay,
        )
    }

    private fun solarNoon(utcDate: LocalDate, longitude: Double): Instant {
        var minutes = 720 - 4 * longitude
        repeat(2) { // refine with the equation of time at the estimate
            val (_, eqTime) = declinationAndEquationOfTime(julianDay(utcDate, minutes))
            minutes = 720 - 4 * longitude - eqTime
        }
        return at(utcDate, minutes)
    }

    /** UTC minutes of [utcDate] when the sun's centre is at [zenith] degrees, or null if it never is. */
    private fun event(utcDate: LocalDate, latitude: Double, longitude: Double, zenith: Double, rising: Boolean): Instant? {
        var minutes = 720 - 4 * longitude
        repeat(3) { // iterate: declination and equation of time at the event itself, not at noon
            val (decl, eqTime) = declinationAndEquationOfTime(julianDay(utcDate, minutes))
            val lat = rad(latitude)
            val cosHa = cos(rad(zenith)) / (cos(lat) * cos(decl)) - tan(lat) * tan(decl)
            if (cosHa !in -1.0..1.0) return null
            val ha = deg(acos(cosHa))
            minutes = 720 - 4 * (longitude + if (rising) ha else -ha) - eqTime
        }
        return at(utcDate, minutes)
    }

    private fun at(utcDate: LocalDate, minutes: Double): Instant =
        utcDate.atStartOfDay(ZoneOffset.UTC).toInstant().plusMillis((minutes * 60_000).toLong())

    private fun julianDay(utcDate: LocalDate, minutes: Double) =
        utcDate.toEpochDay() + 2_440_587.5 + minutes / 1440

    /** Declination (radians) and equation of time (minutes) for a Julian day. */
    private fun declinationAndEquationOfTime(jd: Double): Pair<Double, Double> {
        val t = (jd - 2_451_545.0) / 36_525.0
        val l0 = ((280.46646 + t * (36_000.76983 + t * 0.0003032)) % 360 + 360) % 360
        val m = 357.52911 + t * (35_999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val c = sin(rad(m)) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(rad(2 * m)) * (0.019993 - 0.000101 * t) +
            sin(rad(3 * m)) * 0.000289
        val omega = 125.04 - 1934.136 * t
        val lambda = l0 + c - 0.00569 - 0.00478 * sin(rad(omega))
        val eps0 = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60
        val eps = eps0 + 0.00256 * cos(rad(omega))
        val decl = asin(sin(rad(eps)) * sin(rad(lambda)))
        val y = tan(rad(eps / 2)).let { it * it }
        val eqTime = 4 * deg(
            y * sin(2 * rad(l0)) - 2 * e * sin(rad(m)) + 4 * e * y * sin(rad(m)) * cos(2 * rad(l0)) -
                0.5 * y * y * sin(4 * rad(l0)) - 1.25 * e * e * sin(2 * rad(m)),
        )
        return decl to eqTime
    }

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
}
