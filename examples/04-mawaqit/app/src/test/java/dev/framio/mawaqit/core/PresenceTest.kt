package dev.framio.mawaqit.core

import dev.framio.mawaqit.core.Presence.Reason
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

class PresenceTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val day = LocalDate.of(2026, 10, 20)
    private fun at(h: Int, m: Int, d: LocalDate = day) = ZonedDateTime.of(d, LocalTime.of(h, m), zone)
    private val on = PresenceSettings(enabled = true, idleMinutes = 10, returnAfterMinutes = 2)
    private val min = 60_000L

    // ------------------------------------------------------------------ settings

    @Test
    fun offByDefaultAndRoundTrips() {
        assertFalse(Settings().presence.enabled)
        val p = PresenceSettings(true, "home", 1, 0, 7, false, true, "random", 45, 20, 50, 90, "07:30", "23:15")
        assertEquals(p, PresenceSettings.fromJson(JSONObject(p.toJson().toString())))
        val s = Settings(presence = p)
        assertEquals(s, Settings.fromJson(JSONObject(s.toJson().toString())))
        assertFalse(s.toJson().has("frame")) // the old section isn't written any more
    }

    @Test
    fun outOfRangeValuesAreClampedOrDropped() {
        val p = PresenceSettings.fromJson(JSONObject().put("away", "moon").put("idleMinutes", 999).put("periodic", "often")
            .put("minMinutes", 1).put("maxMinutes", 0).put("showSeconds", 1).put("from", "25:00").put("to", "6:5").put("everyMinutes", -3))
        assertEquals("off", p.away)
        assertEquals(240, p.idleMinutes)
        assertEquals("off", p.periodic)
        assertEquals(5, p.minMinutes)
        assertEquals(5, p.maxMinutes) // never below min
        assertEquals(10, p.showSeconds)
        assertEquals("08:00", p.from)
        assertEquals("21:00", p.to) // "6:5" isn't HH:MM
        assertEquals(5, p.everyMinutes)
        assertEquals("06:05", PresenceSettings.fromJson(JSONObject().put("from", "06:05")).from)
    }

    @Test
    fun photoFrameModeIsMigrated() {
        // the user's frame: photo-frame mode off → the policy off, its defaults
        val off = Settings.fromJson(JSONObject().put("frame", JSONObject().put("enabled", false).put("leadMinutes", 9)))
        assertEquals(PresenceSettings(), off.presence)
        // on: away to the photos with the same minutes
        val frame = JSONObject().put("enabled", true).put("leadMinutes", 7).put("returnAfterMinutes", 3).put("idleMinutes", 0)
        val p = Settings.fromJson(JSONObject().put("frame", frame)).presence
        assertEquals(PresenceSettings(enabled = true, away = "photos", leadMinutes = 7, returnAfterMinutes = 3, idleMinutes = 0), p)
        // a presence section wins over a leftover frame
        val both = Settings.fromJson(JSONObject().put("frame", frame).put("presence", JSONObject().put("away", "home")))
        assertEquals("home", both.presence.away)
        assertFalse(both.presence.enabled)
        // and nothing at all: the defaults
        assertEquals(PresenceSettings(), Settings.fromJson(JSONObject()).presence)
    }

    @Test
    fun anOldFramePatchLandsInPresence() {
        val base = Settings().toJson()
        val patch = PresenceSettings.upgradePatch(JSONObject().put("frame", JSONObject().put("enabled", true).put("idleMinutes", 4)))
        assertFalse(patch.has("frame"))
        val s = Settings.fromJson(Settings.merge(base, patch))
        assertTrue(s.presence.enabled)
        assertEquals("photos", s.presence.away)
        assertEquals(4, s.presence.idleMinutes)
        // off keeps the away mode chosen before; an explicit presence key wins over the frame one
        val off = PresenceSettings.upgradePatch(JSONObject().put("frame", JSONObject().put("enabled", false))
            .put("presence", JSONObject().put("idleMinutes", 8)).put("display", JSONObject().put("theme", "night")))
        assertEquals(JSONObject().put("enabled", false).put("idleMinutes", 8).toString(), off.getJSONObject("presence").toString())
        assertEquals("night", off.getJSONObject("display").getString("theme"))
        val plain = JSONObject().put("display", JSONObject())
        assertTrue(plain === PresenceSettings.upgradePatch(plain))
    }

    @Test
    fun reminderPrayersAndChimeRoundTrip() {
        val a = AdhanSettings.fromJson(JSONObject().put("reminderMinutes", 20).put("reminderPrayers", JSONArray(listOf(false, true)))
            .put("reminderChime", false))
        assertEquals(listOf(false, true, true, true, true), a.reminderPrayers)
        assertFalse(a.reminderChime)
        assertEquals(a, AdhanSettings.fromJson(JSONObject(a.toJson().toString())))
        assertEquals(List(5) { true }, AdhanSettings().reminderPrayers)
    }

    // ------------------------------------------------------------------ what holds the display

    @Test
    fun everyScreenAroundAPrayerHoldsIt() {
        for (k in listOf(PhaseKind.ADHAN, PhaseKind.DUA_AFTER_ADHAN, PhaseKind.IQAMA_COUNTDOWN, PhaseKind.IQAMA, PhaseKind.PRAYER, PhaseKind.AZKAR, PhaseKind.JUMUA)) {
            assertEquals(k.id, Reason.PRAYER, Presence.holder(on, k, null, message = false, adhkarHolds = false, periodic = false))
        }
        assertEquals(Reason.BEFORE_ADHAN, Presence.holder(on, PhaseKind.REMINDER, 5 * min, false, false, false))
        assertNull(Presence.holder(on, PhaseKind.NORMAL, 30 * min, false, false, false))
    }

    @Test
    fun leadMinutesBringItBeforeTheAdhan() {
        val p = on.copy(leadMinutes = 5)
        assertNull(Presence.holder(p, PhaseKind.NORMAL, 5 * min, false, false, false))
        assertEquals(Reason.BEFORE_ADHAN, Presence.holder(p, PhaseKind.NORMAL, 5 * min - 1, false, false, false))
        assertEquals(Reason.BEFORE_ADHAN, Presence.holder(p, PhaseKind.NORMAL, 0, false, false, false))
        assertNull(Presence.holder(on, PhaseKind.NORMAL, 0, false, false, false)) // no lead: the adhan itself
    }

    @Test
    fun nightHoldsTheClockOnlyWhenAwayIsAnotherApp() {
        // as photo-frame mode: the night shows the dim clock instead of the photos
        assertEquals(Reason.NIGHT, Presence.holder(on.copy(away = "photos"), PhaseKind.NIGHT, null, false, false, false))
        assertEquals(Reason.NIGHT, Presence.holder(on.copy(away = "home"), PhaseKind.NIGHT, null, false, false, false))
        // "off": the night is quiet too, nothing holds it (not even a message or the periodic adhkar)
        assertNull(Presence.holder(on, PhaseKind.NIGHT, null, false, false, false))
    }

    @Test
    fun messagesRemindersAndAdhkarHoldInThatOrder() {
        assertEquals(Reason.MESSAGE, Presence.holder(on, PhaseKind.NORMAL, null, message = true, adhkarHolds = true, periodic = true))
        assertEquals(Reason.REMINDER, Presence.holder(on, PhaseKind.NORMAL, null, message = false, adhkarHolds = true, periodic = true))
        assertEquals(Reason.ADHKAR, Presence.holder(on, PhaseKind.NORMAL, null, message = false, adhkarHolds = false, periodic = true))
        assertEquals(Reason.PRAYER, Presence.holder(on, PhaseKind.ADHAN, null, message = true, adhkarHolds = true, periodic = true))
    }

    @Test
    fun theTogglesDecideWhatBringsItBack() {
        assertTrue(Presence.brings(on, Reason.MESSAGE))
        assertTrue(Presence.brings(on, Reason.REMINDER))
        val quiet = on.copy(messages = false, reminders = false)
        assertFalse(Presence.brings(quiet, Reason.MESSAGE))
        assertFalse(Presence.brings(quiet, Reason.REMINDER))
        for (r in listOf(Reason.PRAYER, Reason.BEFORE_ADHAN, Reason.ADHKAR, Reason.REMOTE, Reason.NIGHT)) assertTrue(Presence.brings(quiet, r))
    }

    // ------------------------------------------------------------------ going away

    @Test
    fun goesAwayAfterTheIdleTime() {
        val t0 = 1_000_000L
        assertFalse(Presence.goesAway(on, t0 + 10 * min - 1, lastTouchMs = t0, lastHeldMs = 0, auto = false, lastReason = null))
        assertTrue(Presence.goesAway(on, t0 + 10 * min, t0, 0, false, null))
        // the idle time restarts after what held it (the azkar ended 3 min ago)
        assertFalse(Presence.goesAway(on, t0 + 12 * min, t0, t0 + 3 * min, false, Reason.PRAYER))
        assertTrue(Presence.goesAway(on, t0 + 13 * min, t0, t0 + 3 * min, false, Reason.PRAYER))
        // 0: only what came back by itself goes away
        assertFalse(Presence.goesAway(on.copy(idleMinutes = 0), t0 + 1000 * min, t0, 0, false, null))
    }

    @Test
    fun backByItselfGoesAwayAfterTheReturnTime() {
        val t0 = 5_000_000L
        assertFalse(Presence.goesAway(on, t0 + 2 * min - 1, lastTouchMs = 0, lastHeldMs = t0, auto = true, lastReason = Reason.PRAYER))
        assertTrue(Presence.goesAway(on, t0 + 2 * min, 0, t0, true, Reason.PRAYER))
        assertTrue(Presence.goesAway(on.copy(returnAfterMinutes = 0), t0, 0, t0, true, Reason.MESSAGE))
        // the periodic adhkar leave as soon as their time is over
        assertTrue(Presence.goesAway(on, t0, 0, t0, true, Reason.ADHKAR))
    }

    // ------------------------------------------------------------------ periodic adhkar

    @Test
    fun everyNMinutesWithinTheHours() {
        val p = on.copy(periodic = "every", everyMinutes = 45, from = "08:00", to = "21:00")
        assertEquals(at(10, 45), Presence.nextPeriodic(p, at(10, 0), Random(1)))
        // past the hours: the next morning's start
        assertEquals(at(8, 0, day.plusDays(1)), Presence.nextPeriodic(p, at(20, 30), Random(1)))
        // before them: this morning's
        assertEquals(at(8, 0), Presence.nextPeriodic(p, at(6, 0), Random(1)))
        assertNull(Presence.nextPeriodic(on, at(10, 0), Random(1)))
    }

    @Test
    fun randomIntervalsStayWithinTheirBounds() {
        val p = on.copy(periodic = "random", minMinutes = 20, maxMinutes = 50, from = "00:00", to = "00:00")
        val r = Random(42)
        val seen = HashSet<Long>()
        repeat(500) {
            val next = Presence.nextPeriodic(p, at(12, 0), r)!!
            val m = java.time.Duration.between(at(12, 0), next).toMinutes()
            assertTrue("$m", m in 20..50)
            seen += m
        }
        assertTrue(20L in seen && 50L in seen) // both ends inclusive
        // seeded: the same sequence again
        val r1 = Random(7)
        val r2 = Random(7)
        assertEquals(List(5) { Presence.nextPeriodic(p, at(12, 0), r1) }, List(5) { Presence.nextPeriodic(p, at(12, 0), r2) })
    }

    @Test
    fun quietHoursAcrossMidnight() {
        val p = on.copy(periodic = "every", from = "22:00", to = "06:00")
        assertTrue(Presence.inHours(p, LocalTime.of(23, 0)))
        assertTrue(Presence.inHours(p, LocalTime.of(5, 59)))
        assertFalse(Presence.inHours(p, LocalTime.of(6, 0)))
        assertFalse(Presence.inHours(p, LocalTime.of(12, 0)))
        assertTrue(Presence.inHours(p, LocalTime.of(22, 0)))
        assertTrue(Presence.inHours(on.copy(from = "09:00", to = "09:00"), LocalTime.of(3, 0))) // equal: all day
        assertEquals(at(22, 0), Presence.intoHours(p, at(12, 0)))
    }

    @Test
    fun noPeriodicAdhkarAtNightOrOutOfHours() {
        val p = on.copy(periodic = "every", from = "08:00", to = "21:00")
        assertTrue(Presence.periodicAllowed(p, at(12, 0), PhaseKind.NORMAL))
        assertFalse(Presence.periodicAllowed(p, at(12, 0), PhaseKind.NIGHT))
        assertFalse(Presence.periodicAllowed(p, at(22, 0), PhaseKind.NORMAL))
        assertFalse(Presence.periodicAllowed(on, at(12, 0), PhaseKind.NORMAL)) // off
    }

    // ------------------------------------------------------------------ before the adhan

    @Test
    fun minutesBeforeTheAdhanPerPrayer() {
        val b = Behavior(reminderMinutes = 20, reminderPrayers = listOf(false, true, true, true, true))
        assertEquals(0, Presence.beforeAdhanMinutes(on, b, PrayerKey.FAJR))
        assertEquals(20, Presence.beforeAdhanMinutes(on, b, PrayerKey.DHUHR))
        assertEquals(20, Presence.beforeAdhanMinutes(on, b, PrayerKey.JUMUA)) // Dhuhr's toggle
        assertEquals(5, Presence.beforeAdhanMinutes(on.copy(leadMinutes = 5), b, PrayerKey.FAJR))
        assertEquals(20, Presence.beforeAdhanMinutes(on.copy(leadMinutes = 5), b, PrayerKey.ASR))
        assertEquals(30, Presence.beforeAdhanMinutes(on.copy(leadMinutes = 30), b, PrayerKey.ASR))
    }

    @Test
    fun theReminderPhaseFollowsThePrayerToggles() {
        val row = listOf("06:30", "08:00", "13:00", "16:00", "18:45", "20:15")
        val cal = List(12) { (1..31).associateWith { row } }
        val times = MawaqitTimes(cal, List(12) { (1..31).associateWith { emptyList<String>() } }, false, null, null, null, false, 0, zone)
        val b = Behavior(reminderMinutes = 20, reminderPrayers = listOf(true, false, true, true, true))
        assertEquals(PhaseKind.NORMAL, Timeline.phaseAt(at(12, 45), times, b).kind) // Dhuhr: none
        val asr = Timeline.phaseAt(at(15, 45), times, b)
        assertEquals(PhaseKind.REMINDER, asr.kind)
        assertEquals(at(15, 40), asr.start)
        assertEquals(PrayerKey.ASR, asr.prayer?.key)
        assertEquals(PhaseKind.REMINDER, Timeline.phaseAt(at(6, 15), times, b).kind)
        assertEquals(PhaseKind.REMINDER, Timeline.phaseAt(at(12, 45), times, b.copy(reminderPrayers = List(5) { true })).kind)
    }

    @Test
    fun arabicMinutesHaveTheirPlurals() {
        assertEquals("دقيقة", PresenceText.minutes("ar", 1))
        assertEquals("دقيقتين", PresenceText.minutes("ar", 2))
        assertEquals("5 دقائق", PresenceText.minutes("ar", 5))
        assertEquals("20 دقيقة", PresenceText.minutes("ar", 20))
        assertEquals("20 min", PresenceText.minutes("en", 20))
        assertEquals("20 Min.", PresenceText.minutes("de", 20))
        assertEquals("Dhuhr in 20 min", Strings.of("en").inPrefix(PrayerKey.DHUHR) + " " + PresenceText.minutes("en", 20))
    }
}
