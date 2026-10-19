package dev.framio.meadow.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {

    @Test fun roundTripKeepsEveryField() {
        val s = Settings(childName = "Mia", age = 4, lang = Lang.DE, volume = 30, music = false, brightness = 50,
            dailyLimitMin = 30, breakEveryMin = 10, bedtime = "20:00", wakeUp = "07:15", bedtimeMode = false,
            nightLightMin = 15, backlightOffAtNight = false, knockToWake = false, reducedMotion = true, captions = false,
            hidden = setOf("paint", "echo"), morningRoutine = listOf("wake", "brush"), eveningRoutine = listOf("bath"), pin = "1234")
        val back = Settings.fromJson(s.toJson().put("pin", s.pin))
        assertEquals(s, back)
    }

    @Test fun partialUpdateChangesOnlyWhatIsSent() {
        val s = Settings(age = 3, volume = 55, pin = "4321").merge(JSONObject("""{"age":5}"""))
        assertEquals(5, s.age)
        assertEquals(55, s.volume)
        assertEquals("4321", s.pin)
    }

    @Test fun outOfRangeValuesAreClampedOrIgnored() {
        val s = Settings().merge(JSONObject("""{"age":9,"volume":-4,"brightness":0,"bedtime":"25:61","lang":"xx","childName":"${"a".repeat(40)}"}"""))
        assertEquals(5, s.age)
        assertEquals(0, s.volume)
        assertEquals(10, s.brightness)
        assertEquals("19:30", s.bedtime)
        assertEquals(Lang.EN, s.lang)
        assertEquals(24, s.childName.length)
    }

    @Test fun theDashboardCannotChangeThePin() {
        val s = Settings(pin = "1111").merge(JSONObject("""{"pin":"0000"}"""))
        assertEquals("1111", s.pin)
        assertFalse(s.toJson().has("pin"))
    }

    @Test fun captionsAreOnByDefaultAndForOlderFiles() {
        assertTrue(Settings().captions)
        // a settings.json written before captions existed
        assertTrue(Settings.fromJson(JSONObject("""{"age":4,"music":false}""")).captions)
        assertTrue(Settings().toJson().getBoolean("captions"))
    }

    @Test fun captionsSwitchOffAndOnWithAPartialUpdate() {
        val off = Settings(age = 4).merge(JSONObject("""{"captions":false}"""))
        assertFalse(off.captions)
        assertEquals(4, off.age)
        assertTrue(off.merge(JSONObject("""{"captions":true}""")).captions)
        assertFalse("other updates leave it alone", off.merge(JSONObject("""{"volume":20}""")).captions)
    }
}
