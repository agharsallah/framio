package dev.framio.mawaqit.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

// Pure Kotlin, no Android imports: everything in core/ runs in JVM unit tests.

/** The prayers and times of a day. FAJR…ISHA are the five; the others are shown around them. */
enum class PrayerKey(val id: String) {
    IMSAK("imsak"), FAJR("fajr"), SHURUQ("shuruq"), DHUHR("dhuhr"), ASR("asr"), MAGHRIB("maghrib"), ISHA("isha"),
    JUMUA("jumua"), EID("eid");

    /** Index among the five prayers (fajr 0 … isha 4), or -1. Jumu'a counts as Dhuhr. */
    val five: Int
        get() = when (this) {
            FAJR -> 0; DHUHR, JUMUA -> 1; ASR -> 2; MAGHRIB -> 3; ISHA -> 4; else -> -1
        }

    companion object {
        val FIVE = listOf(FAJR, DHUHR, ASR, MAGHRIB, ISHA)
        fun of(id: String): PrayerKey? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One prayer of a day.
 * @param iqama null without an iqama (Imsak, Shuruq, Eid, or a mosque that publishes none)
 * @param iqamaOffset minutes after the adhan when the mosque gives the iqama as "+N", else null
 */
data class PrayerTime(
    val key: PrayerKey,
    val adhan: ZonedDateTime,
    val iqama: ZonedDateTime? = null,
    val iqamaOffset: Int? = null,
)

/** The prayers of one day. A prayer is null when the source has no valid time for it. */
data class PrayerDay(
    val date: LocalDate,
    val imsak: PrayerTime?,
    val fajr: PrayerTime?,
    val shuruq: PrayerTime?,
    val dhuhr: PrayerTime?,
    val asr: PrayerTime?,
    val maghrib: PrayerTime?,
    val isha: PrayerTime?,
    /** Fridays only, in order (1 to 3 Jumu'a). Empty on other days. */
    val jumua: List<PrayerTime> = emptyList(),
    /** Eid prayers, only on the days the source says there is one. */
    val eid: List<PrayerTime> = emptyList(),
) {
    /** Fajr, Dhuhr, Asr, Maghrib, Isha (nulls kept so index = PrayerKey.five). */
    val five: List<PrayerTime?> get() = listOf(fajr, dhuhr, asr, maghrib, isha)
}

/** Anything that gives prayer times for a day: a Mawaqit mosque calendar or the astronomical calculation. */
interface TimesSource {
    val zone: ZoneId
    fun day(date: LocalDate): PrayerDay?
}

/** A mosque's screen settings, as published in Mawaqit's `confData` / `/3.0/mosque/{uuid}/config`. */
data class MosqueConfig(
    val adhanEnabled: List<Boolean> = List(5) { true },
    val adhanVoice: String? = null,          // null = beep
    val adhanDurationSeconds: Int = 150,
    val customAthanUrl: String? = null,
    val customAthanDurationSeconds: Int? = null,
    val duaAfterAdhan: Boolean = true,
    val duaAfterPrayer: Boolean = true,
    val prayerMinutes: List<Int> = listOf(10, 8, 8, 8, 9), // duaAfterPrayerShowTimes
    val iqamaBeep: Boolean = false,
    val iqamaFullScreenCountdown: Boolean = true,
    val iqamaDisplaySeconds: Int = 45,
    val iqamaMoreImportant: Boolean = false,
    val alwaysHhMm: Boolean = false,
    val blackScreenWhenPraying: Boolean = false,
    val jumuaMinutes: Int = 40,
    val jumuaBlackScreen: Boolean = false,
    val jumuaReminder: Boolean = true,
    val wakeForFajrMinutes: Int? = null,
    val randomHadith: Boolean = false,
    val hadithLang: String = "ar",
    val temperatureEnabled: Boolean = false,
    val temperatureUnit: String = "C",
    val clock24h: Boolean = true,
    val hijriEnabled: Boolean = true,
    val theme: String? = null,
    val backgroundColor: String? = null,
    val backgroundUrl: String? = null,
    val showLogo: Boolean = true,
)

/** An announcement of the mosque (or one added from the remote panel). */
data class Announcement(
    val id: String,
    val title: String?,
    val content: String?,
    val imageUrl: String? = null,
    /** Shown from/until, in the mosque's zone. null = no bound. */
    val start: ZonedDateTime? = null,
    val end: ZonedDateTime? = null,
    val seconds: Int = 15,
)

/** The short message scrolling at the bottom of the screen. */
data class Flash(
    val text: String,
    val color: String = "#1F6F5C",
    val rtl: Boolean = false,
    val start: LocalDate? = null,
    val end: LocalDate? = null,
)

/** Everything read from a Mawaqit mosque, apart from the times (see [MawaqitTimes]). */
data class Mosque(
    val slug: String,
    val name: String,
    val address: String?,
    val zone: ZoneId,
    val latitude: Double,
    val longitude: Double,
    val countryCode: String?,
    val uuid: String?,               // from the page's weather URL; needed by the API endpoints
    val mawaqitId: Int?,             // numeric id, from the page's has-been-updated URL
    val lastUpdated: Long?,          // the page's `lastUpdated` (unix seconds), sent back to has-been-updated
    val config: MosqueConfig,
    val announcements: List<Announcement>,
    val flash: Flash?,
    val logoUrl: String?,
    val imageUrl: String?,
    val streamUrl: String?,
    val site: String?,
    val phone: String?,
    val paymentWebsite: String?,
    val hijriAdjustment: Int,
    val hijriForce30: Boolean,
    val eidTimes: List<String>,      // HH:MM; shown on Eid days
    val closed: String?,
)

/**
 * What happens around each prayer, after merging our settings with the mosque's screen settings.
 * Durations in seconds unless named minutes.
 */
data class Behavior(
    val adhanEnabled: List<Boolean> = List(5) { true },
    val adhanSeconds: Int = 150,
    val adhanScreen: Boolean = true,
    val duaAfterAdhan: Boolean = true,
    val duaSeconds: Int = 30,
    val iqamaCountdown: Boolean = true,
    val iqamaFullScreen: Boolean = true,
    val iqamaSeconds: Int = 45,
    val prayerMinutes: List<Int> = listOf(10, 8, 8, 8, 9),
    val blackScreen: Boolean = false,
    val azkar: Boolean = true,
    val azkarMinutes: Int = 5,
    val jumuaMinutes: Int = 40,
    val jumuaBlackScreen: Boolean = false,
    val jumuaReminder: Boolean = true,
    val reminderMinutes: Int = 0,     // a soft "adhan soon" phase before each adhan, 0 = none
    val reminderPrayers: List<Boolean> = List(5) { true }, // which of the five have it (Jumu'a: Dhuhr's)
    val nightMode: NightMode = NightMode.OFF,
    val nightAfterIshaMinutes: Int = 60,
    val nightBeforeFajrMinutes: Int = 30,
)

enum class NightMode(val id: String) {
    OFF("off"), DIM("dim"), BLACK("black");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/** What the screen shows right now. Order = the order the phases happen around a prayer. */
enum class PhaseKind(val id: String) {
    NORMAL("normal"),
    REMINDER("reminder"),         // [adhan - reminderMinutes, adhan)
    ADHAN("adhan"),               // [adhan, adhan + adhanSeconds)
    DUA_AFTER_ADHAN("dua"),       // then duaSeconds
    IQAMA_COUNTDOWN("countdown"), // until the iqama
    IQAMA("iqama"),               // [iqama, iqama + iqamaSeconds)
    PRAYER("prayer"),             // until iqama + prayerMinutes
    AZKAR("azkar"),               // then azkarMinutes
    JUMUA("jumua"),               // Friday: [jumua, jumua + jumuaMinutes) instead of the Dhuhr flow
    NIGHT("night");               // [isha + nightAfterIsha, fajr - nightBeforeFajr)

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id }
    }
}

/**
 * @param prayer the prayer the phase belongs to (null for NORMAL and NIGHT)
 * @param start when this phase began, [end] when it ends (the next change of screen)
 */
data class Phase(
    val kind: PhaseKind,
    val prayer: PrayerTime?,
    val start: ZonedDateTime?,
    val end: ZonedDateTime?,
)
