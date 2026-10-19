package dev.framio.pomodoro.core.model

import dev.framio.pomodoro.core.engine.CoreJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes

class PresetsTest {
    @Test
    fun `classic is 25 5 with a 20 minute long break every 4, breaks start by themselves`() {
        val r = Presets.classic.rhythm as Rhythm.Fixed
        assertEquals(Rhythm.Fixed(25.minutes, 5.minutes, 20.minutes, 4, autoBreak = true, autoFocus = false), r)
        assertEquals(IntentionRule.Optional, Presets.classic.ritual.intention)
        with(Presets.classic.environment) {
            assertEquals(Display.Full, display)
            assertEquals(Face.Ring, face)
            assertTrue(showSeconds)
            assertEquals(CueStyle.Soft, cues)
        }
    }

    @Test
    fun `deep is 50 10 with 30 every 2, nothing automatic, pause capped at 10 minutes, calm and locked`() {
        val r = Presets.deep.rhythm as Rhythm.Fixed
        assertEquals(Rhythm.Fixed(50.minutes, 10.minutes, 30.minutes, 2, autoBreak = false, autoFocus = false, pauseCap = 10.minutes), r)
        with(Presets.deep.ritual) {
            assertEquals(IntentionRule.Required, intention)
            assertTrue(ifThen)
            assertTrue(showResumeNote)
            assertEquals(Presets.CHECK_PHONE, checklist.first().id)
        }
        with(Presets.deep.environment) {
            assertEquals(Display.Calm, display) // decided: Calm by default, Blackout one toggle away
            assertFalse(showSeconds)
            assertEquals(TouchPolicy.HoldToReveal(1200.milliseconds), touch)
            assertTrue(knockToPeek)
            assertEquals(CueStyle.Rising, cues)
            assertTrue(sunrise)
            assertNull(soundscape)
            assertNull(checkIn) // check-in is off by default
        }
    }

    @Test
    fun `flow counts up with the Smits tiers and a 90 minute nudge, gentle is 10 3 with no ritual`() {
        assertEquals(Rhythm.Flow(SmitsTiers, 90.minutes), Presets.flow.rhythm)
        assertEquals(Display.Calm, Presets.flow.environment.display)
        val g = Presets.gentle.rhythm as Rhythm.Fixed
        assertEquals(10.minutes, g.focus)
        assertEquals(3.minutes, g.short)
        assertEquals(0, g.longEvery)
        assertEquals(IntentionRule.Off, Presets.gentle.ritual.intention)
        assertEquals(CopyTone.Warm, Presets.gentle.environment.tone)
    }

    @Test
    fun `presets are built in, in carousel order, valid, with distinct accents`() {
        assertEquals(listOf("classic", "deep", "flow", "gentle"), Presets.all.map { it.id.value })
        assertTrue(Presets.all.all { it.builtIn })
        assertEquals(4, Presets.all.map { it.accent }.toSet().size)
        Presets.all.forEach { assertNull(it.name, ModeRules.problem(it)) }
    }
}

class SettingsTest {
    @Test
    fun `defaults follow the decisions`() {
        val s = Settings()
        assertEquals(6, s.dailyTarget)
        assertEquals(Presets.DEEP_ID, s.selectedMode)
        assertEquals(Theme.Ink, s.theme)
        assertFalse(s.reduceMotion)
    }

    @Test
    fun `normalized refreshes presets, keeps own modes, repairs the selection and ranges`() {
        val own = Presets.classic.duplicate(ModeId("mine"), "Mine")
        val stale = Presets.deep.copy(name = "Old deep")
        val s = Settings(modes = listOf(stale, own), selectedMode = ModeId("gone"), dailyTarget = 0, soundVolume = 3f, accent = 12).normalized()
        assertEquals(listOf(Presets.deep, own) + Presets.classic + Presets.flow + Presets.gentle, s.modes) // your order kept
        assertEquals(Presets.DEEP_ID, s.selectedMode)
        assertEquals(1, s.dailyTarget)
        assertEquals(1f, s.soundVolume)
        assertNull(s.accent)
    }

    @Test
    fun `settings survive a round trip and old files without new keys`() {
        val s = Settings(theme = Theme.Forest, eveningStart = LocalTime.of(21, 15), modes = Presets.all + Presets.flow.duplicate(ModeId("f2")))
        assertEquals(s, CoreJson.decodeSettings(CoreJson.encodeSettings(s)))
        val old = CoreJson.decodeSettings("""{"theme":"paper","future":{"x":1}}""")
        assertNotNull(old)
        assertEquals(Theme.Paper, old?.theme)
        assertEquals(Presets.all, old?.modes)
        assertNull(CoreJson.decodeSettings("{not json"))
    }

    @Test
    fun `unknown enum values fall back to defaults instead of losing the file`() {
        val s = CoreJson.decodeSettings("""{"theme":"neon","dailyTarget":4}""")
        assertEquals(Theme.Ink, s?.theme)
        assertEquals(4, s?.dailyTarget)
    }

    @Test
    fun `serial names are stable`() {
        val json = Json.parseToJsonElement(CoreJson.encodeSettings(Settings())).jsonObject
        val deep = (json["modes"] as kotlinx.serialization.json.JsonArray)[1].jsonObject
        assertEquals("deep", deep["id"]?.jsonPrimitive?.content)
        val rhythm = deep["rhythm"] as JsonObject
        assertEquals("fixed", rhythm["type"]?.jsonPrimitive?.content)
        assertEquals("PT50M", rhythm["focus"]?.jsonPrimitive?.content)
        val env = deep["environment"] as JsonObject
        assertEquals("calm", env["display"]?.jsonPrimitive?.content)
        assertEquals("hold", (env["touch"] as JsonObject)["type"]?.jsonPrimitive?.content)
        assertEquals("20:00", json["eveningStart"]?.jsonPrimitive?.content)
    }
}

class ModeRulesTest {
    private val base = Presets.classic.duplicate(ModeId("x"), "Mine")

    @Test
    fun `editor limits`() {
        assertNull(ModeRules.problem(base))
        assertNotNull(ModeRules.problem(base.copy(name = " ")))
        assertNotNull(ModeRules.problem(base.copy(accent = 8)))
        assertNotNull(ModeRules.problem(base.copy(rhythm = Rhythm.Fixed(focus = 30.minutes / 60))))
        assertNotNull(ModeRules.problem(base.copy(rhythm = Rhythm.Fixed(longEvery = 13))))
        assertNotNull(ModeRules.problem(base.copy(rhythm = Rhythm.Flow(tiers = listOf(Tier(25.minutes, 5.minutes))))))
        assertNotNull(ModeRules.problem(base.copy(rhythm = Rhythm.Flow(tiers = listOf(Tier(50.minutes, 5.minutes), Tier(25.minutes, 8.minutes), Tier(null, 10.minutes))))))
        assertNull(ModeRules.problem(base.copy(rhythm = Rhythm.Flow(nudgeAfter = null))))
    }

    @Test
    fun `the editor's boundaries - focus at least 1 min, long breaks never or every 1 to 12`() {
        fun fixed(f: Rhythm.Fixed) = ModeRules.problem(base.copy(rhythm = f))
        assertNull(fixed(Rhythm.Fixed(focus = 1.minutes)))
        assertNotNull(fixed(Rhythm.Fixed(focus = 59.seconds)))
        assertNull(fixed(Rhythm.Fixed(focus = 4.hours)))
        assertNotNull(fixed(Rhythm.Fixed(focus = 4.hours + 1.minutes)))
        assertNull(fixed(Rhythm.Fixed(longEvery = 0))) // never (Gentle start)
        assertNull(fixed(Rhythm.Fixed(longEvery = 1)))
        assertNull(fixed(Rhythm.Fixed(longEvery = 12)))
        assertNotNull(fixed(Rhythm.Fixed(longEvery = -1)))
        assertNotNull(fixed(Rhythm.Fixed(short = 0.minutes)))
        assertNotNull(fixed(Rhythm.Fixed(long = 121.minutes)))
        assertNotNull(fixed(Rhythm.Fixed(pauseCap = 0.minutes)))
        assertNull(fixed(Rhythm.Fixed(pauseCap = 10.minutes)))
        assertNotNull(ModeRules.problem(base.copy(rhythm = Rhythm.Flow(nudgeAfter = 9.minutes))))
        assertNotNull(ModeRules.problem(base.copy(rhythm = Rhythm.Flow(tiers = listOf(Tier(null, 5.minutes), Tier(null, 8.minutes))))))
    }

    @Test
    fun `ritual and environment limits`() {
        fun ritual(r: Ritual) = ModeRules.problem(base.copy(ritual = r))
        val item = ChecklistItem("own-1", "Desk clear")
        assertNull(ritual(Ritual(checklist = listOf(item))))
        assertNotNull(ritual(Ritual(checklist = listOf(item, item.copy(label = "Again")))))
        assertNotNull(ritual(Ritual(checklist = listOf(item.copy(label = "  ")))))
        assertNotNull(ritual(Ritual(checklist = listOf(item.copy(label = "x".repeat(ModeRules.MAX_ITEM + 1))))))
        assertNotNull(ritual(Ritual(checklist = (1..9).map { ChecklistItem("i$it", "Item $it") })))
        fun env(e: Environment) = ModeRules.problem(base.copy(environment = e))
        assertNull(env(Environment(checkIn = 15.minutes)))
        assertNotNull(env(Environment(checkIn = 1.minutes)))
        assertNotNull(env(Environment(touch = TouchPolicy.HoldToReveal(100.milliseconds))))
        assertNotNull(ModeRules.problem(base.copy(name = "x".repeat(ModeRules.MAX_NAME + 1))))
    }

    @Test
    fun `new ids and copy names never collide`() {
        val modes = Presets.all + base.copy(id = ModeId("own-1")) + base.copy(id = ModeId("own-3"), name = "Deep 2")
        assertEquals(ModeId("own-2"), ModeRules.newId(modes))
        assertEquals("Deep 3", ModeRules.copyName("Deep", modes))
        assertEquals("Classic 2", ModeRules.copyName("Classic", modes))
        assertTrue(ModeRules.copyName("x".repeat(40), modes).length <= ModeRules.MAX_NAME)
        assertEquals("own-2", ModeRules.newItemId(listOf(ChecklistItem("own-1", "a"), ChecklistItem("phone", "b"))))
    }
}

class InkCodecTest {
    @Test
    fun `strokes round trip within a quantum and stay JSON safe`() {
        val strokes = listOf(
            Stroke(floatArrayOf(0f, 0f, 0.5f, 0.25f, 1f, 1f, 0.999f, 0.001f)),
            Stroke(floatArrayOf(0.3f, 0.7f)),
        )
        val text = InkCodec.encode(strokes)
        assertTrue(text.all { it.isLetterOrDigit() || it in ".!#$%&*" })
        val back = InkCodec.decode(text) ?: error("not decoded")
        assertEquals(2, back.size)
        for ((a, b) in strokes.zip(back)) {
            assertEquals(a.points.size, b.points.size)
            a.points.indices.forEach { assertEquals(a.points[it], b.points[it], 1f / InkCodec.SCALE) }
        }
        assertEquals(text, InkCodec.encode(back))
    }

    @Test
    fun `garbage is refused, empty is no strokes`() {
        assertEquals(emptyList<Stroke>(), InkCodec.decode(""))
        assertNull(InkCodec.decode("0")) // one value: half a point
        assertNull(InkCodec.decode("ABC")) // ends mid-value
        assertNull(InkCodec.decode("0~0"))
    }

    @Test
    fun `a handwritten word is a few hundred characters`() {
        val points = FloatArray(400) { i -> if (i % 2 == 0) i / 400f else 0.5f + 0.1f * kotlin.math.sin(i / 10f) }
        val text = InkCodec.encode(listOf(Stroke(points)))
        assertTrue(text.length.toString(), text.length < 900)
    }
}
