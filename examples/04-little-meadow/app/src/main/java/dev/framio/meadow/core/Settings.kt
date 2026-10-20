package dev.framio.meadow.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything a grown-up can change, on the frame (behind the parent gate) or from the dashboard.
 * Immutable: change it with [SettingsStore.update]. Unknown or out-of-range JSON values fall back
 * to the defaults, so an old or hand-edited file never breaks the app.
 */
data class Settings(
    val childName: String = "",
    /** 2..5: sets the starting difficulty and which activities show. */
    val age: Int = 3,
    val lang: Lang = Lang.EN,
    /** 0..100, applied to the music stream. */
    val volume: Int = 55,
    val music: Boolean = true,
    /** 10..100, the window's backlight level while the app is in front. */
    val brightness: Int = 75,
    /** Minutes of play per day; 0 = no limit. AAP: about an hour a day for ages 2 to 5. */
    val dailyLimitMin: Int = 45,
    /** A movement break ("wiggle break") after this many minutes in a row; 0 = never. */
    val breakEveryMin: Int = 15,
    /** "HH:MM". Between bedtime and wake-up only the quiet corner is open, dimmer and warmer. */
    val bedtime: String = "19:30",
    val wakeUp: String = "06:30",
    val bedtimeMode: Boolean = true,
    /** Night light: minutes until the screen fades to dark. */
    val nightLightMin: Int = 20,
    /** Turn the backlight fully off when the night light ends (needs root, which the frame has). */
    val backlightOffAtNight: Boolean = true,
    /** Knock on the frame to wake it from rest (accelerometer). */
    val knockToWake: Boolean = true,
    /** Calmer motion everywhere: no hops, slower fades. */
    val reducedMotion: Boolean = false,
    /** Show what the friends say as a caption (for grown-ups and early readers). */
    val captions: Boolean = true,
    /** Voice, sounds and music silenced from the play screen's mute button (captions keep showing). */
    val muted: Boolean = false,
    /** Activity ids the grown-up switched off. */
    val hidden: Set<String> = emptySet(),
    /** Step ids of the picture routines (see the habits world). */
    val morningRoutine: List<String> = listOf("wake", "toilet", "wash", "dress", "breakfast", "brush"),
    val eveningRoutine: List<String> = listOf("tidy", "bath", "pyjamas", "brush", "story", "sleep"),
    /** The dashboard's PIN, made up on first start. */
    val pin: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("childName", childName)
        .put("age", age)
        .put("lang", lang.code)
        .put("volume", volume)
        .put("music", music)
        .put("brightness", brightness)
        .put("dailyLimitMin", dailyLimitMin)
        .put("breakEveryMin", breakEveryMin)
        .put("bedtime", bedtime)
        .put("wakeUp", wakeUp)
        .put("bedtimeMode", bedtimeMode)
        .put("nightLightMin", nightLightMin)
        .put("backlightOffAtNight", backlightOffAtNight)
        .put("knockToWake", knockToWake)
        .put("reducedMotion", reducedMotion)
        .put("captions", captions)
        .put("muted", muted)
        .put("hidden", JSONArray(hidden.sorted()))
        .put("morningRoutine", JSONArray(morningRoutine))
        .put("eveningRoutine", JSONArray(eveningRoutine))

    /** A copy with the fields present in [j] replaced (the dashboard sends partial updates). The PIN never changes here. */
    fun merge(j: JSONObject): Settings {
        val d = this
        return Settings(
            childName = j.optString("childName", d.childName).take(24).trim(),
            age = j.optInt("age", d.age).coerceIn(2, 5),
            lang = if (j.has("lang")) Lang.of(j.optString("lang")) else d.lang,
            volume = j.optInt("volume", d.volume).coerceIn(0, 100),
            music = j.optBoolean("music", d.music),
            brightness = j.optInt("brightness", d.brightness).coerceIn(10, 100),
            dailyLimitMin = j.optInt("dailyLimitMin", d.dailyLimitMin).coerceIn(0, 240),
            breakEveryMin = j.optInt("breakEveryMin", d.breakEveryMin).coerceIn(0, 120),
            bedtime = j.optString("bedtime", d.bedtime).takeIf(::isClock) ?: d.bedtime,
            wakeUp = j.optString("wakeUp", d.wakeUp).takeIf(::isClock) ?: d.wakeUp,
            bedtimeMode = j.optBoolean("bedtimeMode", d.bedtimeMode),
            nightLightMin = j.optInt("nightLightMin", d.nightLightMin).coerceIn(1, 120),
            backlightOffAtNight = j.optBoolean("backlightOffAtNight", d.backlightOffAtNight),
            knockToWake = j.optBoolean("knockToWake", d.knockToWake),
            reducedMotion = j.optBoolean("reducedMotion", d.reducedMotion),
            captions = j.optBoolean("captions", d.captions),
            muted = j.optBoolean("muted", d.muted),
            hidden = j.optJSONArray("hidden")?.strings()?.toSet() ?: d.hidden,
            morningRoutine = j.optJSONArray("morningRoutine")?.strings()?.take(10) ?: d.morningRoutine,
            eveningRoutine = j.optJSONArray("eveningRoutine")?.strings()?.take(10) ?: d.eveningRoutine,
            pin = d.pin,
        )
    }

    companion object {
        fun fromJson(j: JSONObject): Settings = Settings().merge(j).copy(pin = j.optString("pin", ""))

        fun isClock(s: String): Boolean = Clock.parseMinutes(s) != null
    }
}

fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it, null) }
