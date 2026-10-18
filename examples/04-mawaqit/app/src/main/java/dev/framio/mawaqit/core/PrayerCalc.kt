package dev.framio.mawaqit.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * A calculation method: the sun's depression below the horizon at Fajr and Isha (or Isha as minutes after Maghrib).
 * Maghrib is at sunset unless [maghribAngle] is set (Shia methods). [jafariMidnight] puts midnight half-way between
 * sunset and Fajr instead of sunset and sunrise.
 */
enum class CalcMethod(
    val id: String,
    val label: String,
    val fajrAngle: Double,
    val ishaAngle: Double?,
    val ishaMinutes: Int? = null,
    val maghribAngle: Double? = null,
    val maghribMinutes: Int = 0,
    val jafariMidnight: Boolean = false,
    /** Isha minutes during Ramadan, when they differ (Umm al-Qura). */
    val ishaMinutesRamadan: Int? = null,
) {
    MWL("MWL", "Muslim World League", 18.0, 17.0),
    ISNA("ISNA", "Islamic Society of North America", 15.0, 15.0),
    EGYPT("EGYPT", "Egyptian General Authority of Survey", 19.5, 17.5),
    MAKKAH("MAKKAH", "Umm al-Qura, Makkah", 18.5, null, ishaMinutes = 90, ishaMinutesRamadan = 120),
    KARACHI("KARACHI", "University of Islamic Sciences, Karachi", 18.0, 18.0),
    TEHRAN("TEHRAN", "Institute of Geophysics, University of Tehran", 17.7, 14.0, maghribAngle = 4.5, jafariMidnight = true),
    JAFARI("JAFARI", "Shia Ithna Ashari (Jafari)", 16.0, 14.0, maghribAngle = 4.0, jafariMidnight = true),
    FRANCE("FRANCE", "UOIF, France", 12.0, 12.0),
    GULF("GULF", "Gulf region", 19.5, null, ishaMinutes = 90),
    KUWAIT("KUWAIT", "Kuwait", 18.0, 17.5),
    QATAR("QATAR", "Qatar", 18.0, null, ishaMinutes = 90),
    SINGAPORE("SINGAPORE", "MUIS, Singapore", 20.0, 18.0),
    // Diyanet publishes 18/17 plus small per-prayer tunings that are not applied here.
    TURKEY("TURKEY", "Diyanet, Turkey", 18.0, 17.0),
    // The committee's seasonal, latitude-dependent rules are approximated by fixed 18/18 angles.
    MOONSIGHTING("MOONSIGHTING", "Moonsighting Committee", 18.0, 18.0),
    RUSSIA("RUSSIA", "Spiritual Administration of Muslims of Russia", 16.0, 15.0),
    DUBAI("DUBAI", "Dubai", 18.2, 18.2);

    companion object {
        fun of(id: String): CalcMethod = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: MWL
    }
}

/** How Fajr and Isha are bounded where twilight lasts all night (or the angle is never reached). */
enum class HighLatRule(val id: String) {
    /** Fajr no earlier than angle/60 of the night before sunrise; Isha likewise after sunset. */
    ANGLE("angle"),
    MIDDLE("middle"),
    SEVENTH("seventh"),
    NONE("none");

    companion object {
        fun of(id: String): HighLatRule = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: ANGLE
    }
}

/** Astronomical times of one day, rounded to the minute. A field is null when the sun never reaches its angle. */
data class RawTimes(
    val fajr: ZonedDateTime?,
    val sunrise: ZonedDateTime?,
    val dhuhr: ZonedDateTime,
    val asr: ZonedDateTime?,
    val sunset: ZonedDateTime?,
    val maghrib: ZonedDateTime?,
    val isha: ZonedDateTime?,
    val midnight: ZonedDateTime?,
)

/**
 * The PrayTimes.org algorithm (v2.3, one iteration, elevation 0). Dhuhr is the sun's zenith, without the extra
 * minute some calendars add. Times are computed in UTC and converted to the zone, so DST days are right.
 */
object PrayerCalc {
    private const val RISE_SET_ANGLE = 0.833

    fun compute(
        date: LocalDate,
        lat: Double,
        lon: Double,
        zone: ZoneId,
        method: CalcMethod,
        hanafi: Boolean = false,
        highLat: HighLatRule = HighLatRule.ANGLE,
    ): RawTimes? {
        if (!lat.isFinite() || !lon.isFinite() || abs(lat) >= 90 || abs(lon) > 180) return null
        // Julian day at local mean midnight.
        val jd = date.toEpochDay() + 2440587.5 - lon / 360

        fun midDay(t: Double) = fix(12 - sun(jd + t).eqt, 24.0)

        /** Hours (local mean time) when the sun is [angle] below the horizon; ccw = before noon. NaN if never. */
        fun sunAngleTime(angle: Double, t: Double, ccw: Boolean = false): Double {
            val decl = sun(jd + t).decl
            val cosH = (-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))
            if (cosH < -1 || cosH > 1) return Double.NaN
            val h = Math.toDegrees(acos(cosH)) / 15
            return midDay(t) + if (ccw) -h else h
        }

        fun asrTime(factor: Int, t: Double): Double {
            val decl = sun(jd + t).decl
            // Beyond 90° the noon sun is below the horizon: there is no shadow to measure.
            if (abs(lat - decl) >= 90) return Double.NaN
            val altitude = Math.toDegrees(atan(1 / (factor + dtan(abs(lat - decl)))))
            return sunAngleTime(-altitude, t)
        }

        // First guesses as day fractions, refined once like PrayTimes.
        val sunrise = sunAngleTime(RISE_SET_ANGLE, 6 / 24.0, ccw = true)
        val sunset = sunAngleTime(RISE_SET_ANGLE, 18 / 24.0)
        var fajr = sunAngleTime(method.fajrAngle, 5 / 24.0, ccw = true)
        val dhuhr = midDay(12 / 24.0)
        val asr = asrTime(if (hanafi) 2 else 1, 13 / 24.0)
        var maghrib = method.maghribAngle?.let { sunAngleTime(it, 18 / 24.0) } ?: Double.NaN
        var isha = method.ishaAngle?.let { sunAngleTime(it, 18 / 24.0) } ?: Double.NaN

        if (highLat != HighLatRule.NONE) {
            val night = fix(sunrise - sunset, 24.0)
            fun portion(angle: Double) = night * when (highLat) {
                HighLatRule.ANGLE -> angle / 60
                HighLatRule.MIDDLE -> 0.5
                else -> 1 / 7.0
            }
            fun bound(time: Double, base: Double, angle: Double, ccw: Boolean): Double {
                val p = portion(angle)
                val diff = if (ccw) fix(base - time, 24.0) else fix(time - base, 24.0)
                return if (time.isNaN() || diff > p) base + if (ccw) -p else p else time
            }
            fajr = bound(fajr, sunrise, method.fajrAngle, ccw = true)
            method.ishaAngle?.let { isha = bound(isha, sunset, it, ccw = false) }
            method.maghribAngle?.let { maghrib = bound(maghrib, sunset, it, ccw = false) }
        }
        if (method.maghribAngle == null) maghrib = sunset + method.maghribMinutes / 60.0
        method.ishaMinutes?.let { minutes ->
            val m = method.ishaMinutesRamadan?.takeIf { Hijri.isRamadan(date) } ?: minutes
            isha = maghrib + m / 60.0
        }
        val midnight = sunset + fix((if (method.jafariMidnight) fajr else sunrise) - sunset, 24.0) / 2

        val utcMidnight = date.atStartOfDay(ZoneOffset.UTC)
        fun at(hours: Double): ZonedDateTime? {
            if (hours.isNaN()) return null
            val minutes = floor((hours - lon / 15) * 60 + 0.5).toLong()
            return utcMidnight.plusMinutes(minutes).withZoneSameInstant(zone)
        }
        return RawTimes(
            fajr = at(fajr),
            sunrise = at(sunrise),
            dhuhr = at(dhuhr) ?: return null,
            asr = at(asr),
            sunset = at(sunset),
            maghrib = at(maghrib),
            isha = at(isha),
            midnight = at(midnight),
        )
    }

    private class Sun(val decl: Double, val eqt: Double)

    /** Declination (degrees) and equation of time (hours) at Julian day [jd]. */
    private fun sun(jd: Double): Sun {
        val d = jd - 2451545.0
        val g = fix(357.529 + 0.98560028 * d, 360.0)
        val q = fix(280.459 + 0.98564736 * d, 360.0)
        val l = fix(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g), 360.0)
        val e = 23.439 - 0.00000036 * d
        val ra = Math.toDegrees(atan2(dcos(e) * dsin(l), dcos(l))) / 15
        return Sun(decl = Math.toDegrees(asin(dsin(e) * dsin(l))), eqt = q / 15 - fix(ra, 24.0))
    }

    private fun fix(a: Double, b: Double): Double = if (a.isNaN()) a else a - b * floor(a / b)
    private fun dsin(d: Double) = sin(Math.toRadians(d))
    private fun dcos(d: Double) = cos(Math.toRadians(d))
    private fun dtan(d: Double) = tan(Math.toRadians(d))
}

/**
 * Prayer times computed from [SourceSettings]' position and method. Imsak is 10 minutes before Fajr; the iqama is
 * "+N" minutes after each adhan; Jumu'a is at [SourceSettings.jumua], or at Dhuhr when that is not a valid HH:MM.
 */
class CalcTimes(private val s: SourceSettings, override val zone: ZoneId) : TimesSource {
    private val method = CalcMethod.of(s.method)
    private val hanafi = s.asr.equals("hanafi", ignoreCase = true)
    private val highLat = HighLatRule.of(s.highLatitude)
    private val jumuaTime = parseHhMm(s.jumua)

    // The UI asks for the same few days over and over; null results are cached too. A plain HashMap emptied when
    // full: a LinkedHashMap subclass gets Kotlin bridges to getOrDefault/remove(k, v), which API 23 doesn't have.
    private val cache = HashMap<LocalDate, PrayerDay?>()

    override fun day(date: LocalDate): PrayerDay? = synchronized(cache) {
        if (cache.containsKey(date)) return@synchronized cache[date]
        if (cache.size >= CACHE_DAYS) cache.clear()
        compute(date).also { cache[date] = it }
    }

    private fun compute(date: LocalDate): PrayerDay? {
        val raw = PrayerCalc.compute(date, s.latitude, s.longitude, zone, method, hanafi, highLat) ?: return null
        fun shifted(t: ZonedDateTime?, offset: Int) = t?.plusMinutes(s.offsets.getOrElse(offset) { 0 }.toLong())
        fun prayer(key: PrayerKey, t: ZonedDateTime?): PrayerTime? {
            t ?: return null
            val iqama = s.iqama.getOrNull(key.five)?.takeIf { key.five >= 0 && it >= 0 }
            return PrayerTime(key, t, iqama?.let { t.plusMinutes(it.toLong()) }, iqama)
        }
        val fajr = shifted(raw.fajr, 0)
        val dhuhr = shifted(raw.dhuhr, 2)
        val jumua = if (date.dayOfWeek != DayOfWeek.FRIDAY) emptyList() else
            listOfNotNull((jumuaTime?.let { ZonedDateTime.of(date, it, zone) } ?: dhuhr)?.let { PrayerTime(PrayerKey.JUMUA, it) })
        return PrayerDay(
            date = date,
            imsak = fajr?.let { PrayerTime(PrayerKey.IMSAK, it.minusMinutes(IMSAK_MINUTES)) },
            fajr = prayer(PrayerKey.FAJR, fajr),
            shuruq = shifted(raw.sunrise, 1)?.let { PrayerTime(PrayerKey.SHURUQ, it) },
            dhuhr = prayer(PrayerKey.DHUHR, dhuhr),
            asr = prayer(PrayerKey.ASR, shifted(raw.asr, 3)),
            maghrib = prayer(PrayerKey.MAGHRIB, shifted(raw.maghrib, 4)),
            isha = prayer(PrayerKey.ISHA, shifted(raw.isha, 5)),
            jumua = jumua,
        )
    }

    private companion object {
        const val CACHE_DAYS = 8
        const val IMSAK_MINUTES = 10L

        fun parseHhMm(v: String): LocalTime? {
            val parts = v.trim().split(':')
            if (parts.size != 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            return if (h in 0..23 && m in 0..59) LocalTime.of(h, m) else null
        }
    }
}
