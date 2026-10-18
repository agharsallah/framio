package dev.framio.mawaqit.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {

    @Test
    fun defaultsSurviveJsonRoundTrip() {
        val s = Settings(messages = listOf(Message("m1", "Iftar", "Tonight at the mosque", "2026-03-01", "2026-03-30")))
        assertEquals(s, Settings.fromJson(JSONObject(s.toJson().toString())))
    }

    @Test
    fun missingOrNullJsonGivesDefaults() {
        assertEquals(Settings(), Settings.fromJson(null))
        assertEquals(Settings(), Settings.fromJson(JSONObject()))
        assertEquals(DisplaySettings(), Settings.fromJson(JSONObject().put("display", "not an object")).display)
    }

    @Test
    fun patchMergesObjectsAndReplacesArrays() {
        val base = Settings().toJson()
        val patch = JSONObject()
            .put("display", JSONObject().put("theme", "night"))
            .put("adhan", JSONObject().put("enabled", JSONArray(listOf(false, true, true, true, false))))
        val merged = Settings.fromJson(Settings.merge(base, patch))
        assertEquals("night", merged.display.theme)
        assertEquals(DisplaySettings().language, merged.display.language) // sibling kept
        assertEquals(listOf(false, true, true, true, false), merged.adhan.enabled)
        assertEquals(Settings().flow, merged.flow) // untouched group
    }

    @Test
    fun outOfRangeAndUnknownValuesAreClampedOrDropped() {
        val j = JSONObject()
            .put("display", JSONObject().put("language", "xx").put("theme", "neon").put("brightness", 500).put("hijriAdjust", -9))
            .put("adhan", JSONObject().put("volume", -4).put("voice", "../../etc/passwd").put("enabled", JSONArray(listOf(false))))
            .put("source", JSONObject().put("kind", "other").put("latitude", 123.0).put("offsets", JSONArray(listOf(999, -999))))
            .put("remote", JSONObject().put("port", 80).put("ntfyTopic", "a b/c?d").put("pin", "12ab34"))
            .put("unknown", true)
        val s = Settings.fromJson(j)
        assertEquals("en", s.display.language)
        assertEquals("emerald", s.display.theme)
        assertEquals(100, s.display.brightness)
        assertEquals(-3, s.display.hijriAdjust)
        assertEquals(0, s.adhan.volume)
        assertEquals("adhan-afassy", s.adhan.voice)
        assertEquals(listOf(false, true, true, true, true), s.adhan.enabled) // short array: the rest keep defaults
        assertEquals("mawaqit", s.source.kind)
        assertEquals(90.0, s.source.latitude, 0.0)
        assertEquals(listOf(120, -120, 0, 0, 0, 0), s.source.offsets)
        assertEquals(1024, s.remote.port)
        assertEquals("abcd", s.remote.ntfyTopic)
        assertEquals("1234", s.remote.pin)
        assertFalse(s.toJson().has("unknown"))
    }

    @Test
    fun mosqueUrlIsReducedToItsSlug() {
        val cases = mapOf(
            "grande-mosquee-de-paris" to "grande-mosquee-de-paris",
            "https://mawaqit.net/fr/grande-mosquee-de-paris" to "grande-mosquee-de-paris",
            "https://mawaqit.net/en/m/grande-mosquee-de-paris?view=desktop" to "grande-mosquee-de-paris",
            "mawaqit.net/ar/w/mosquee-x/" to "mosquee-x",
            "  la-grande-mosquee  " to "la-grande-mosquee",
            "https://mawaqit.net/" to "",
        )
        for ((input, slug) in cases) assertEquals(input, slug, SourceSettings.mosqueSlug(input))
    }

    @Test
    fun fajrVoiceAcceptsFajrVariants() {
        val s = AdhanSettings.fromJson(JSONObject().put("fajrVoice", "adhan-madina-fajr"))
        assertEquals("adhan-madina-fajr", s.fajrVoice)
        assertTrue(AdhanSettings.fromJson(JSONObject().put("fajrVoice", "nope")).fajrVoice == "same")
    }
}
