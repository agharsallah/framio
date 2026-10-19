package dev.framio.pomodoro.core.model

import dev.framio.pomodoro.core.engine.CoreJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime
import kotlin.time.Duration.Companion.minutes

/**
 * Old settings files must keep decoding: an M2-era file (before the ritual's `skippable`, the M5 appearance and sound
 * keys, and with presets as they were then) loses nothing the user chose and gains today's defaults.
 */
class SettingsMigrationTest {
    /** `files/datastore/settings.json` as M2 wrote it (encodeDefaults, explicitNulls off), with one hand-made mode. */
    private val m2 = """
        {"theme":"paper","dailyTarget":8,"eveningStart":"20:30","reduceMotion":true,"soundVolume":0.4,
         "modes":[
          {"id":"classic","name":"Classic","accent":0,
           "rhythm":{"type":"fixed","focus":"PT25M","short":"PT5M","long":"PT20M","longEvery":4,"autoBreak":true,"autoFocus":false},
           "ritual":{"intention":"optional","ifThen":false,"checklist":[],"resumeNote":true},
           "environment":{"display":"full","face":"ring","seconds":true,"touch":{"type":"open"},"knock":false,"cues":"soft","sunrise":false,"tone":"neutral"},
           "builtIn":true},
          {"id":"deep","name":"Deep (old)","accent":1,
           "rhythm":{"type":"fixed","focus":"PT45M","short":"PT10M","long":"PT30M","longEvery":2,"autoBreak":false,"autoFocus":false,"pauseCap":"PT10M"},
           "ritual":{"intention":"required","ifThen":true,"checklist":[{"id":"phone","label":"Phone"}],"resumeNote":true},
           "environment":{"display":"calm","face":"ring","seconds":false,"touch":{"type":"hold","hold":"PT1.2S"},"knock":true,"cues":"rising","sunrise":true,"tone":"neutral"},
           "builtIn":true},
          {"id":"mine","name":"Reading","accent":5,
           "rhythm":{"type":"fixed","focus":"PT40M","short":"PT8M","long":"PT20M","longEvery":3,"autoBreak":true,"autoFocus":false},
           "ritual":{"intention":"optional","ifThen":false,"checklist":[],"resumeNote":true},
           "environment":{"display":"full","face":"disc","seconds":false,"touch":{"type":"open"},"knock":false,"cues":"soft","soundscape":"rain","sunrise":false,"tone":"neutral"},
           "builtIn":false}
         ],
         "selectedMode":"mine"}
    """.trimIndent()

    @Test
    fun `an M2 settings file decodes, keeps every choice, and takes today's presets`() {
        val s = CoreJson.decodeSettings(m2) ?: error("not decoded")
        assertEquals(Theme.Paper, s.theme)
        assertEquals(8, s.dailyTarget)
        assertEquals(LocalTime.of(20, 30), s.eveningStart) // stored then, so kept (the new default is 20:00)
        assertEquals(true, s.reduceMotion)
        assertEquals(0.4f, s.soundVolume)
        assertEquals(ModeId("mine"), s.selectedMode)
        // Presets are today's (the old Deep's 45 min and name are gone), the own mode is intact, the order is kept
        assertEquals(Presets.deep, s.mode(Presets.DEEP_ID))
        val mine = s.mode(ModeId("mine")) ?: error("own mode lost")
        assertEquals("Reading", mine.name)
        assertEquals(40.minutes, (mine.rhythm as Rhythm.Fixed).focus)
        assertEquals(Soundscape.Rain, mine.environment.soundscape)
        assertEquals(true, mine.ritual.skippable) // M3 key, absent then
        assertEquals(listOf("classic", "deep", "mine", "flow", "gentle"), s.modes.map { it.id.value })
        // M5 keys take their defaults
        assertEquals(Settings.DEFAULT_SOUNDSCAPE_VOLUME, s.soundscapeVolume)
        assertEquals(Settings.DEFAULT_MORNING, s.eveningEnd)
        assertNull(s.face)
        assertEquals(TimerSize.Medium, s.timerSize)
        assertFalse(s.systemKeyboard)
        assertEquals(true, s.evening)
    }

    @Test
    fun `an M5 file round-trips every new key`() {
        val s = Settings(
            evening = false, eveningEnd = LocalTime.of(7, 30), soundscapeVolume = 0.2f, face = Face.Horizon,
            timerSize = TimerSize.Large, systemKeyboard = true,
        )
        assertEquals(s, CoreJson.decodeSettings(CoreJson.encodeSettings(s)))
    }

    @Test
    fun `a file from the future with unknown keys and values still decodes`() {
        val s = CoreJson.decodeSettings("""{"timerSize":"huge","face":"hologram","haptics":true,"soundscapeVolume":0.5}""") ?: error("not decoded")
        assertEquals(TimerSize.Medium, s.timerSize)
        assertNull(s.face)
        assertEquals(0.5f, s.soundscapeVolume)
    }

    @Test
    fun `out-of-range numbers are repaired, not refused`() {
        val s = CoreJson.decodeSettings("""{"soundscapeVolume":7,"soundVolume":-1,"dailyTarget":99}""") ?: error("not decoded")
        assertEquals(1f, s.soundscapeVolume)
        assertEquals(0f, s.soundVolume)
        assertEquals(Settings.MAX_TARGET, s.dailyTarget)
    }

    @Test
    fun `normalizing keeps your order, drops duplicates, and caps your own modes`() {
        val own = (1..6).map { Presets.classic.duplicate(ModeId("o$it"), "O$it") }
        val s = Settings(modes = listOf(own[0], Presets.flow, own[0], Presets.classic) + own.drop(1)).normalized()
        assertEquals(Settings.MAX_MODES, s.modes.size)
        assertEquals(listOf("o1", "flow", "classic", "o2", "o3", "o4", "deep", "gentle"), s.modes.map { it.id.value })
        // A mode that claims to be built in but isn't a preset can't sneak in as read-only
        val fake = Presets.classic.copy(id = ModeId("fake"), builtIn = true)
        assertNull(Settings(modes = Presets.all + fake).normalized().mode(ModeId("fake")))
    }
}
