package dev.framio.mawaqit.core

import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.min

data class HijriDate(val year: Int, val month: Int, val day: Int)

/**
 * The tabular ("Kuwaiti") Hijri calendar, ported as-is from Mawaqit's official Python client so the date matches
 * their screens, including the mosque's day [adjustment] and "force 30 days" flag.
 */
object Hijri {
    private const val PYTHON_ORDINAL_OF_EPOCH_DAY_0 = 719163 // date(1970, 1, 1).toordinal()
    private const val JULIAN_DAY_OF_ORDINAL_0 = 1721425
    private const val EPOCH = 1948084
    private const val CYCLE_DAYS = 10631
    private const val YEAR_DAYS = CYCLE_DAYS / 30.0
    private const val SHIFT = 8.01 / 60

    fun kuwaiti(d: LocalDate): HijriDate {
        val all = d.toEpochDay() + PYTHON_ORDINAL_OF_EPOCH_DAY_0 + JULIAN_DAY_OF_ORDINAL_0 - EPOCH
        val cycle = Math.floorDiv(all, CYCLE_DAYS.toLong()).toInt()
        var days = Math.floorMod(all, CYCLE_DAYS.toLong()).toInt()
        val year = floor((days - SHIFT) / YEAR_DAYS).toInt()
        days -= floor(year * YEAR_DAYS + SHIFT).toInt()
        val month = min(floor((days + 28.5001) / 29.5).toInt(), 12)
        return HijriDate(30 * cycle + year, month, days - floor(29.5001 * month - 29).toInt())
    }

    /** The date Mawaqit shows for [d]. [force30] is applied exactly as the Python client does. */
    fun of(d: LocalDate, adjustment: Int = 0, force30: Boolean = false): HijriDate {
        val h = kuwaiti(d.plusDays(adjustment.toLong()))
        return when {
            !force30 -> h
            h.day != 1 -> h.copy(day = 30)
            h.month == 1 -> HijriDate(h.year - 1, 12, 30)
            else -> HijriDate(h.year, h.month - 1, 30)
        }
    }

    fun isRamadan(d: LocalDate, adjustment: Int = 0, force30: Boolean = false) = of(d, adjustment, force30).month == 9

    private val MONTHS = mapOf(
        "en" to listOf(
            "Muharram", "Safar", "Rabi' al-Awwal", "Rabi' al-Thani", "Jumada al-Ula", "Jumada al-Akhirah",
            "Rajab", "Sha'ban", "Ramadan", "Shawwal", "Dhu al-Qi'dah", "Dhu al-Hijjah",
        ),
        "ar" to listOf(
            "محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى", "جمادى الآخرة",
            "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة",
        ),
        "fr" to listOf(
            "Mouharram", "Safar", "Rabia al Awal", "Rabia ath-Thani", "Joumada al Oula", "Joumada ath-Thania",
            "Rajab", "Chaabane", "Ramadan", "Chawwal", "Dhou al Qi'da", "Dhou al Hijja",
        ),
        "de" to listOf(
            "Muharram", "Safar", "Rabi' al-awwal", "Rabi' ath-thani", "Dschumada l-ula", "Dschumada th-thaniya",
            "Radschab", "Scha'ban", "Ramadan", "Schawwal", "Dhu l-qa'da", "Dhu l-hiddscha",
        ),
        "nl" to listOf(
            "Moeharram", "Safar", "Rabi' al-Awwal", "Rabi' al-Thani", "Djoemada al-Oela", "Djoemada al-Thani",
            "Radjab", "Sja'ban", "Ramadan", "Sjawwal", "Dhoe al-Qa'da", "Dhoe al-Hiddja",
        ),
        "tr" to listOf(
            "Muharrem", "Safer", "Rebiülevvel", "Rebiülahir", "Cemaziyelevvel", "Cemaziyelahir",
            "Recep", "Şaban", "Ramazan", "Şevval", "Zilkade", "Zilhicce",
        ),
    )

    /** [month] 1…12 in [lang] ("en", "ar", "fr", "de", "nl", "tr"; others fall back to English). */
    fun monthName(month: Int, lang: String): String {
        val names = MONTHS[lang.lowercase().take(2)] ?: MONTHS.getValue("en")
        return names.getOrElse(month - 1) { "" }
    }

    /** "26 Rabi' al-Thani 1448", or in Arabic "٢٦ ربيع الآخر ١٤٤٨ هـ". */
    fun format(h: HijriDate, lang: String): String {
        val name = monthName(h.month, lang)
        return if (lang.lowercase().startsWith("ar")) "${arabicDigits(h.day)} $name ${arabicDigits(h.year)} هـ"
        else "${h.day} $name ${h.year}"
    }

    private fun arabicDigits(n: Int) = n.toString().map { if (it in '0'..'9') '٠' + (it - '0') else it }.joinToString("")
}
