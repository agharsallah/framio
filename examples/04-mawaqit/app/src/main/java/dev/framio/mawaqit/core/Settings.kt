package dev.framio.mawaqit.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the user can change, on the frame or remotely. Stored and sent as JSON (keys = property names).
 * A remote "settings.patch" is merged key by key into the current JSON, then read back with [Settings.fromJson],
 * so unknown keys are dropped and missing ones keep their default.
 */
data class Settings(
    val source: SourceSettings = SourceSettings(),
    val display: DisplaySettings = DisplaySettings(),
    val adhan: AdhanSettings = AdhanSettings(),
    val flow: FlowSettings = FlowSettings(),
    val night: NightSettings = NightSettings(),
    /** When the display is on screen: away after a while untouched, back by itself (see [PresenceSettings]). */
    val presence: PresenceSettings = PresenceSettings(),
    val messages: List<Message> = emptyList(),
    val flash: FlashSettings = FlashSettings(),
    val remote: RemoteSettings = RemoteSettings(),
    val adhkar: AdhkarSettings = AdhkarSettings(),
    /** The adhkar display, the frame's second page (see [AdhkarLoopSettings]). */
    val loop: AdhkarLoopSettings = AdhkarLoopSettings(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("source", source.toJson())
        .put("display", display.toJson())
        .put("adhan", adhan.toJson())
        .put("flow", flow.toJson())
        .put("night", night.toJson())
        .put("presence", presence.toJson())
        .put("messages", JSONArray(messages.map { it.toJson() }))
        .put("flash", flash.toJson())
        .put("remote", remote.toJson())
        .put("adhkar", adhkar.toJson())
        .put("loop", loop.toJson())

    companion object {
        fun fromJson(j: JSONObject?): Settings {
            if (j == null) return Settings()
            return Settings(
                source = SourceSettings.fromJson(j.optJSONObject("source")),
                display = DisplaySettings.fromJson(j.optJSONObject("display")),
                adhan = AdhanSettings.fromJson(j.optJSONObject("adhan")),
                flow = FlowSettings.fromJson(j.optJSONObject("flow")),
                night = NightSettings.fromJson(j.optJSONObject("night")),
                // photo-frame mode's "frame" before there was a presence policy
                presence = j.optJSONObject("presence")?.let(PresenceSettings::fromJson) ?: PresenceSettings.fromFrame(j.optJSONObject("frame")),
                messages = j.optJSONArray("messages").objects().map(Message::fromJson),
                flash = FlashSettings.fromJson(j.optJSONObject("flash")),
                remote = RemoteSettings.fromJson(j.optJSONObject("remote")),
                adhkar = AdhkarSettings.fromJson(j.optJSONObject("adhkar")),
                loop = AdhkarLoopSettings.fromJson(j.optJSONObject("loop")),
            )
        }

        /** Deep-merges [patch] into [base]: objects merge, everything else (arrays included) replaces. */
        fun merge(base: JSONObject, patch: JSONObject): JSONObject {
            val out = JSONObject(base.toString())
            for (key in patch.keys()) {
                val p = patch.get(key)
                val b = out.opt(key)
                out.put(key, if (p is JSONObject && b is JSONObject) merge(b, p) else p)
            }
            return out
        }
    }
}

data class SourceSettings(
    /** "mawaqit": times and screen settings of a Mawaqit mosque · "calc": computed from a position. */
    val kind: String = "mawaqit",
    /** The mosque's slug, as in mawaqit.net/en/<slug>. A pasted URL is reduced to its slug. */
    val mosque: String = "",
    /** Optional MAWAQIT API token (free account): enables mosque search by name or position. */
    val apiToken: String = "",
    /** Use the mosque's own screen settings (adhan per prayer, durations, black screen, Jumu'a…) over [FlowSettings]. */
    val followMosque: Boolean = true,
    val latitude: Double = 52.3676,
    val longitude: Double = 4.9041,
    val placeName: String = "Amsterdam",
    /** Calculation method id, see [CalcMethod]. */
    val method: String = "MWL",
    /** "standard" (Shafi'i, Maliki, Hanbali) or "hanafi". */
    val asr: String = "standard",
    /** "angle", "middle", "seventh" or "none". */
    val highLatitude: String = "angle",
    /** IANA zone for "calc"; "" = the frame's zone. */
    val timezone: String = "",
    /** Minutes added to Fajr, Shuruq, Dhuhr, Asr, Maghrib, Isha (both sources). */
    val offsets: List<Int> = List(6) { 0 },
    /** Iqama, in minutes after the adhan, of the five prayers: used by "calc", or always with [overrideIqama]. */
    val iqama: List<Int> = listOf(20, 10, 10, 5, 10),
    val overrideIqama: Boolean = false,
    /** Jumu'a time for "calc", HH:MM; "" = at Dhuhr. */
    val jumua: String = "",
    /** Up to 3 more Mawaqit mosques (slugs) whose times are shown next to the main one's. Display only: no adhan. */
    val secondary: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind).put("mosque", mosque).put("apiToken", apiToken).put("followMosque", followMosque)
        .put("latitude", latitude).put("longitude", longitude).put("placeName", placeName)
        .put("method", method).put("asr", asr).put("highLatitude", highLatitude).put("timezone", timezone)
        .put("offsets", JSONArray(offsets)).put("iqama", JSONArray(iqama)).put("overrideIqama", overrideIqama)
        .put("jumua", jumua).put("secondary", JSONArray(secondary))

    companion object {
        private val D = SourceSettings()
        fun fromJson(j: JSONObject?) = if (j == null) D else SourceSettings(
            kind = j.optString("kind", D.kind).takeIf { it == "mawaqit" || it == "calc" } ?: D.kind,
            mosque = mosqueSlug(j.optString("mosque", D.mosque)),
            apiToken = j.optString("apiToken", D.apiToken).trim(),
            followMosque = j.optBoolean("followMosque", D.followMosque),
            latitude = j.optDouble("latitude", D.latitude).coerceIn(-90.0, 90.0),
            longitude = j.optDouble("longitude", D.longitude).coerceIn(-180.0, 180.0),
            placeName = j.optString("placeName", D.placeName),
            method = j.optString("method", D.method),
            asr = j.optString("asr", D.asr),
            highLatitude = j.optString("highLatitude", D.highLatitude),
            timezone = j.optString("timezone", D.timezone).trim(),
            offsets = j.optJSONArray("offsets").ints(6, D.offsets, -120..120),
            iqama = j.optJSONArray("iqama").ints(5, D.iqama, 0..120),
            overrideIqama = j.optBoolean("overrideIqama", D.overrideIqama),
            jumua = j.optString("jumua", D.jumua).trim(),
            secondary = j.optJSONArray("secondary").strings().map(::mosqueSlug).filter { it.isNotEmpty() }.distinct().take(3),
        )

        /** "https://mawaqit.net/fr/m/grande-mosquee-de-paris?x" → "grande-mosquee-de-paris". */
        fun mosqueSlug(input: String): String {
            val s = input.trim().substringBefore('?').substringBefore('#').trimEnd('/')
            if (!s.contains('/')) return s
            val parts = s.substringAfter("mawaqit.net").split('/').filter { it.isNotEmpty() }
            return parts.lastOrNull { it != "m" && it != "w" && it.length > 2 } ?: ""
        }
    }
}

data class DisplaySettings(
    /** "en", "ar", "fr", "de", "nl" or "tr". */
    val language: String = "en",
    /** "emerald", "night", "sand", "ocean" or "mosque" (the mosque's own background picture). */
    val theme: String = "emerald",
    /** "auto" (follows how the frame stands, from the accelerometer), "landscape" or "portrait". */
    val orientation: String = "auto",
    val clock24h: Boolean = true,
    val showSeconds: Boolean = true,
    val showHijri: Boolean = true,
    /** Days added to the mosque's Hijri date (moon sighting). */
    val hijriAdjust: Int = 0,
    val showImsak: Boolean = false,
    val showShuruq: Boolean = true,
    val showArabicNames: Boolean = true,
    val showWeather: Boolean = true,
    val temperatureUnit: String = "C",
    val showHadith: Boolean = true,
    /** Mawaqit hadith language: "ar", "en", "fr", "de", "nl", "tr"…, or a pair with Arabic like "en-ar". */
    val hadithLang: String = "en-ar",
    val showAnnouncements: Boolean = true,
    /** The secondary mosques' times as a slide (they stay one tap away on the mosque name either way). */
    val showMosquesSlide: Boolean = true,
    /** Now and then a short dhikr instead of the next slide ([adhkarSlidePercent] of them), never just before an adhan. */
    val showAdhkarSlides: Boolean = true,
    val adhkarSlidePercent: Int = 30,
    /** A hadith or announcement replaces the clock area for [slideSeconds], every [slideEveryMinutes]. */
    val slideSeconds: Int = 25,
    val slideEveryMinutes: Int = 4,
    /** Show the iqama as "+10" when the mosque gives it relative to the adhan. */
    val iqamaAsOffset: Boolean = false,
    /** Screen brightness by day, 1–100. */
    val brightness: Int = 100,
    /** The scrolling message at the bottom (ours or the mosque's). */
    val showFlash: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("language", language).put("theme", theme).put("orientation", orientation).put("clock24h", clock24h)
        .put("showSeconds", showSeconds).put("showHijri", showHijri).put("hijriAdjust", hijriAdjust)
        .put("showImsak", showImsak).put("showShuruq", showShuruq).put("showArabicNames", showArabicNames)
        .put("showWeather", showWeather).put("temperatureUnit", temperatureUnit).put("showHadith", showHadith)
        .put("hadithLang", hadithLang).put("showAnnouncements", showAnnouncements).put("slideSeconds", slideSeconds)
        .put("slideEveryMinutes", slideEveryMinutes).put("iqamaAsOffset", iqamaAsOffset).put("brightness", brightness)
        .put("showFlash", showFlash).put("showMosquesSlide", showMosquesSlide)
        .put("showAdhkarSlides", showAdhkarSlides).put("adhkarSlidePercent", adhkarSlidePercent)

    companion object {
        private val D = DisplaySettings()
        val LANGUAGES = listOf("en", "ar", "fr", "de", "nl", "tr")
        val THEMES = listOf("emerald", "night", "sand", "ocean", "mosque")
        fun fromJson(j: JSONObject?) = if (j == null) D else DisplaySettings(
            language = j.optString("language", D.language).takeIf { it in LANGUAGES } ?: D.language,
            theme = j.optString("theme", D.theme).takeIf { it in THEMES } ?: D.theme,
            orientation = j.optString("orientation", D.orientation).takeIf { it in listOf("auto", "landscape", "portrait") } ?: D.orientation,
            clock24h = j.optBoolean("clock24h", D.clock24h),
            showSeconds = j.optBoolean("showSeconds", D.showSeconds),
            showHijri = j.optBoolean("showHijri", D.showHijri),
            hijriAdjust = j.optInt("hijriAdjust", D.hijriAdjust).coerceIn(-3, 3),
            showImsak = j.optBoolean("showImsak", D.showImsak),
            showShuruq = j.optBoolean("showShuruq", D.showShuruq),
            showArabicNames = j.optBoolean("showArabicNames", D.showArabicNames),
            showWeather = j.optBoolean("showWeather", D.showWeather),
            temperatureUnit = j.optString("temperatureUnit", D.temperatureUnit).takeIf { it == "C" || it == "F" } ?: D.temperatureUnit,
            showHadith = j.optBoolean("showHadith", D.showHadith),
            hadithLang = j.optString("hadithLang", D.hadithLang),
            showAnnouncements = j.optBoolean("showAnnouncements", D.showAnnouncements),
            showMosquesSlide = j.optBoolean("showMosquesSlide", D.showMosquesSlide),
            showAdhkarSlides = j.optBoolean("showAdhkarSlides", D.showAdhkarSlides),
            adhkarSlidePercent = j.optInt("adhkarSlidePercent", D.adhkarSlidePercent).coerceIn(5, 100),
            slideSeconds = j.optInt("slideSeconds", D.slideSeconds).coerceIn(5, 300),
            slideEveryMinutes = j.optInt("slideEveryMinutes", D.slideEveryMinutes).coerceIn(1, 120),
            iqamaAsOffset = j.optBoolean("iqamaAsOffset", D.iqamaAsOffset),
            brightness = j.optInt("brightness", D.brightness).coerceIn(1, 100),
            showFlash = j.optBoolean("showFlash", D.showFlash),
        )
    }
}

data class AdhanSettings(
    /** Play the adhan at Fajr, Dhuhr, Asr, Maghrib, Isha (ignored for the mosque's choice when following it). */
    val enabled: List<Boolean> = List(5) { true },
    /** "mosque" (the mosque's voice / recording), one of [VOICES], or "beep". */
    val voice: String = "adhan-afassy",
    /** Voice for Fajr; "same" = the Fajr version of [voice] when there is one. */
    val fajrVoice: String = "same",
    /** 0–100, of the music stream. */
    val volume: Int = 60,
    val iqamaBeep: Boolean = true,
    /** The "adhan soon" reminder this many minutes before each adhan (notice on screen, chime), 0 = none. */
    val reminderMinutes: Int = 0,
    /** Which of Fajr, Dhuhr (and Jumu'a), Asr, Maghrib, Isha have it. */
    val reminderPrayers: List<Boolean> = List(5) { true },
    /** The short chime when it starts. */
    val reminderChime: Boolean = true,
    /** Adhans uploaded by the user (files/audio/custom-<id>.*); [voice] / [fajrVoice] pick one as "custom:<id>". */
    val custom: List<CustomAdhan> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", JSONArray(enabled)).put("voice", voice).put("fajrVoice", fajrVoice).put("volume", volume)
        .put("iqamaBeep", iqamaBeep).put("reminderMinutes", reminderMinutes)
        .put("reminderPrayers", JSONArray(reminderPrayers)).put("reminderChime", reminderChime)
        .put("custom", JSONArray(custom.map { it.toJson() }))

    companion object {
        private val D = AdhanSettings()
        /** The adhans on cdn.mawaqit.net/audio/<id>.mp3 (each has a "-fajr" version). */
        val VOICES = listOf("adhan-afassy", "adhan-madina", "adhan-maquah", "adhan-egypt", "adhan-algeria", "adhan-quds")
        private fun customIds(j: JSONObject) = j.optJSONArray("custom").objects().mapNotNull(CustomAdhan::fromJson).map { "custom:${it.id}" }
        fun fromJson(j: JSONObject?) = if (j == null) D else AdhanSettings(
            enabled = j.optJSONArray("enabled").booleans(5, D.enabled),
            voice = j.optString("voice", D.voice).takeIf { it == "mosque" || it == "beep" || it in VOICES || it in customIds(j) } ?: D.voice,
            fajrVoice = j.optString("fajrVoice", D.fajrVoice)
                .takeIf { it == "same" || it == "beep" || it.removeSuffix("-fajr") in VOICES || it in customIds(j) } ?: D.fajrVoice,
            volume = j.optInt("volume", D.volume).coerceIn(0, 100),
            iqamaBeep = j.optBoolean("iqamaBeep", D.iqamaBeep),
            reminderMinutes = j.optInt("reminderMinutes", D.reminderMinutes).coerceIn(0, 60),
            reminderPrayers = j.optJSONArray("reminderPrayers").booleans(5, D.reminderPrayers),
            reminderChime = j.optBoolean("reminderChime", D.reminderChime),
            custom = j.optJSONArray("custom").objects().mapNotNull(CustomAdhan::fromJson).distinctBy { it.id }.take(10),
        )
    }
}

/** The screens around a prayer, used when not following the mosque (or with source "calc"). */
data class FlowSettings(
    val adhanScreen: Boolean = true,
    val adhanSeconds: Int = 150,
    val duaAfterAdhan: Boolean = true,
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
) {
    fun toJson(): JSONObject = JSONObject()
        .put("adhanScreen", adhanScreen).put("adhanSeconds", adhanSeconds).put("duaAfterAdhan", duaAfterAdhan)
        .put("iqamaCountdown", iqamaCountdown).put("iqamaFullScreen", iqamaFullScreen).put("iqamaSeconds", iqamaSeconds)
        .put("prayerMinutes", JSONArray(prayerMinutes)).put("blackScreen", blackScreen).put("azkar", azkar)
        .put("azkarMinutes", azkarMinutes).put("jumuaMinutes", jumuaMinutes).put("jumuaBlackScreen", jumuaBlackScreen)
        .put("jumuaReminder", jumuaReminder)

    companion object {
        private val D = FlowSettings()
        fun fromJson(j: JSONObject?) = if (j == null) D else FlowSettings(
            adhanScreen = j.optBoolean("adhanScreen", D.adhanScreen),
            adhanSeconds = j.optInt("adhanSeconds", D.adhanSeconds).coerceIn(10, 600),
            duaAfterAdhan = j.optBoolean("duaAfterAdhan", D.duaAfterAdhan),
            iqamaCountdown = j.optBoolean("iqamaCountdown", D.iqamaCountdown),
            iqamaFullScreen = j.optBoolean("iqamaFullScreen", D.iqamaFullScreen),
            iqamaSeconds = j.optInt("iqamaSeconds", D.iqamaSeconds).coerceIn(5, 600),
            prayerMinutes = j.optJSONArray("prayerMinutes").ints(5, D.prayerMinutes, 0..60),
            blackScreen = j.optBoolean("blackScreen", D.blackScreen),
            azkar = j.optBoolean("azkar", D.azkar),
            azkarMinutes = j.optInt("azkarMinutes", D.azkarMinutes).coerceIn(1, 30),
            jumuaMinutes = j.optInt("jumuaMinutes", D.jumuaMinutes).coerceIn(10, 120),
            jumuaBlackScreen = j.optBoolean("jumuaBlackScreen", D.jumuaBlackScreen),
            jumuaReminder = j.optBoolean("jumuaReminder", D.jumuaReminder),
        )
    }
}

data class NightSettings(
    /** "off", "dim" (low brightness, small clock) or "black" (backlight off; touch or knock wakes it). */
    val mode: String = "dim",
    val afterIshaMinutes: Int = 60,
    val beforeFajrMinutes: Int = 30,
    /** Brightness in "dim", 1–100. */
    val brightness: Int = 8,
    val wakeOnKnock: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("mode", mode).put("afterIshaMinutes", afterIshaMinutes).put("beforeFajrMinutes", beforeFajrMinutes)
        .put("brightness", brightness).put("wakeOnKnock", wakeOnKnock)

    companion object {
        private val D = NightSettings()
        fun fromJson(j: JSONObject?) = if (j == null) D else NightSettings(
            mode = NightMode.of(j.optString("mode", D.mode)).id,
            afterIshaMinutes = j.optInt("afterIshaMinutes", D.afterIshaMinutes).coerceIn(0, 600),
            beforeFajrMinutes = j.optInt("beforeFajrMinutes", D.beforeFajrMinutes).coerceIn(0, 600),
            brightness = j.optInt("brightness", D.brightness).coerceIn(1, 100),
            wakeOnKnock = j.optBoolean("wakeOnKnock", D.wakeOnKnock),
        )
    }
}

/** An announcement written on the remote panel. Dates are "YYYY-MM-DD" in the frame's zone, "" = no bound. */
data class Message(
    val id: String,
    val title: String = "",
    val text: String = "",
    val start: String = "",
    val end: String = "",
    val seconds: Int = 20,
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("text", text).put("start", start).put("end", end)
        .put("seconds", seconds).put("enabled", enabled)

    companion object {
        fun fromJson(j: JSONObject) = Message(
            id = j.optString("id").ifEmpty { Integer.toHexString(j.toString().hashCode()) },
            title = j.optString("title").take(120),
            text = j.optString("text").take(1200),
            start = j.optString("start"),
            end = j.optString("end"),
            seconds = j.optInt("seconds", 20).coerceIn(5, 300),
            enabled = j.optBoolean("enabled", true),
        )
    }
}

/** Our own scrolling message; shown instead of the mosque's when [text] is not empty. */
data class FlashSettings(
    val text: String = "",
    val color: String = "#1F6F5C",
    val rtl: Boolean = false,
    /** "YYYY-MM-DD" last day, "" = until removed. */
    val end: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("text", text).put("color", color).put("rtl", rtl).put("end", end)

    companion object {
        private val D = FlashSettings()
        fun fromJson(j: JSONObject?) = if (j == null) D else FlashSettings(
            text = j.optString("text", D.text).take(300),
            color = j.optString("color", D.color).takeIf { Regex("#[0-9a-fA-F]{6}").matches(it) } ?: D.color,
            rtl = j.optBoolean("rtl", D.rtl),
            end = j.optString("end", D.end),
        )
    }
}

/**
 * Remote control. On the LAN: the frame's web panel on [port], with [pin].
 * From anywhere: commands through an ntfy topic, end-to-end encrypted with [ntfyKey] (AES-GCM, see [Envelope]).
 * [pin], [ntfyTopic] and [ntfyKey] are generated on first start.
 */
data class RemoteSettings(
    val pin: String = "",
    val port: Int = 8090,
    val ntfyEnabled: Boolean = false,
    val ntfyServer: String = "https://ntfy.sh",
    val ntfyTopic: String = "",
    val ntfyKey: String = "",
    /** Where the remote panel is hosted for use away from home (shown as a QR code); "" = not hosted. */
    val panelUrl: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("pin", pin).put("port", port).put("ntfyEnabled", ntfyEnabled).put("ntfyServer", ntfyServer)
        .put("ntfyTopic", ntfyTopic).put("ntfyKey", ntfyKey).put("panelUrl", panelUrl)

    companion object {
        private val D = RemoteSettings()
        fun fromJson(j: JSONObject?) = if (j == null) D else RemoteSettings(
            pin = j.optString("pin", D.pin).filter(Char::isDigit).take(8),
            port = j.optInt("port", D.port).coerceIn(1024, 65535),
            ntfyEnabled = j.optBoolean("ntfyEnabled", D.ntfyEnabled),
            ntfyServer = j.optString("ntfyServer", D.ntfyServer).trim().trimEnd('/').ifEmpty { D.ntfyServer },
            ntfyTopic = j.optString("ntfyTopic", D.ntfyTopic).filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64),
            ntfyKey = j.optString("ntfyKey", D.ntfyKey).trim(),
            panelUrl = j.optString("panelUrl", D.panelUrl).trim(),
        )
    }
}

/** An adhan the user uploaded. [id] names the file; [seconds] is its length, measured when it was stored. */
data class CustomAdhan(val id: String, val name: String, val seconds: Int) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("seconds", seconds)

    companion object {
        private val ID = Regex("[a-z0-9]{4,32}")
        fun fromJson(j: JSONObject): CustomAdhan? {
            val id = j.optString("id")
            if (!ID.matches(id)) return null
            return CustomAdhan(id, j.optString("name").take(60).ifBlank { "My adhan" }, j.optInt("seconds", 0).coerceIn(0, 1800))
        }
    }
}

/**
 * The adhkar (remembrances) section. [schedules] are the times of the day each collection is reminded of, or opened
 * (see [AdhkarTime]); settings from before them (morningReminder, morningAfterFajrMinutes, eveningReminder,
 * eveningAfterAsrMinutes, sleepTime) are read once into the same times ([AdhkarReminders.legacy]).
 * [pushEnabled]: scheduled reminders also go to the user's phone as a plain-text ntfy message on [pushTopic].
 */
data class AdhkarSettings(
    val enabled: Boolean = true,
    val schedules: List<AdhkarTime> = AdhkarReminders.legacy(null),
    /** A soft chime when a reminder appears. */
    val chime: Boolean = true,
    /** Show the translation / transliteration under the Arabic. */
    val translation: Boolean = true,
    val transliteration: Boolean = true,
    /** A very soft tick on each count (there is no vibrator to feel it). */
    val tapSound: Boolean = true,
    /** Opt-in: not encrypted, only the reminder's text, to a topic of the user's choice (subscribed in the ntfy app). */
    val pushEnabled: Boolean = false,
    val pushServer: String = "https://ntfy.sh",
    val pushTopic: String = "",
) {
    /** The first enabled before-sleep time: where that collection's own time (3 h) starts. */
    val sleepSchedule: AdhkarTime? get() = schedules.firstOrNull { it.enabled && it.category == AdhkarCategory.SLEEP }

    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled).put("schedules", JSONArray(schedules.map { it.toJson() }))
        .put("chime", chime).put("translation", translation).put("transliteration", transliteration).put("tapSound", tapSound)
        .put("pushEnabled", pushEnabled).put("pushServer", pushServer).put("pushTopic", pushTopic)

    companion object {
        private val D = AdhkarSettings()
        fun fromJson(j: JSONObject?) = if (j == null) D else AdhkarSettings(
            enabled = j.optBoolean("enabled", D.enabled),
            schedules = j.optJSONArray("schedules")?.let { a ->
                a.objects().mapIndexedNotNull { i, o -> AdhkarTime.fromJson(o, i) }.distinctBy { it.id }.take(AdhkarReminders.MAX)
            } ?: AdhkarReminders.legacy(j),
            chime = j.optBoolean("chime", D.chime),
            translation = j.optBoolean("translation", D.translation),
            transliteration = j.optBoolean("transliteration", D.transliteration),
            tapSound = j.optBoolean("tapSound", D.tapSound),
            pushEnabled = j.optBoolean("pushEnabled", D.pushEnabled),
            pushServer = j.optString("pushServer", D.pushServer).trim().trimEnd('/').takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: D.pushServer,
            pushTopic = j.optString("pushTopic", D.pushTopic).filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64),
        )
    }
}

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it, null) }

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONArray?.ints(size: Int, default: List<Int>, range: IntRange): List<Int> =
    if (this == null) default else List(size) { i -> (if (i < length()) optInt(i, default[i]) else default[i]).coerceIn(range) }

internal fun JSONArray?.booleans(size: Int, default: List<Boolean>): List<Boolean> =
    if (this == null) default else List(size) { i -> if (i < length()) optBoolean(i, default[i]) else default[i] }
