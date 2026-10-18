package dev.framio.mawaqit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class EffectiveTest {

    private val zone = ZoneId.of("Europe/Paris")

    private fun mosque(config: MosqueConfig = MosqueConfig(), flash: Flash? = null, announcements: List<Announcement> = emptyList()) = Mosque(
        slug = "m", name = "M", address = null, zone = zone, latitude = 48.8, longitude = 2.3, countryCode = "FR", uuid = null,
        mawaqitId = null, lastUpdated = null, config = config, announcements = announcements, flash = flash, logoUrl = null,
        imageUrl = null, streamUrl = null, site = null, phone = null, paymentWebsite = null, hijriAdjustment = 0,
        hijriForce30 = false, eidTimes = emptyList(), closed = null,
    )

    private val strict = MosqueConfig(
        adhanEnabled = listOf(true, false, false, false, true), adhanDurationSeconds = 200, blackScreenWhenPraying = true,
        prayerMinutes = listOf(12, 9, 9, 9, 11), duaAfterPrayer = false, jumuaMinutes = 55, iqamaDisplaySeconds = 30,
        clock24h = false,
    )

    @Test
    fun followingTheMosqueTakesItsFlow() {
        val b = Effective.behavior(Settings(), mosque(strict))
        assertEquals(listOf(true, false, false, false, true), b.adhanEnabled)
        assertEquals(200, b.adhanSeconds)
        assertTrue(b.blackScreen)
        assertFalse(b.azkar)
        assertEquals(55, b.jumuaMinutes)
        assertEquals(30, b.iqamaSeconds)
        assertFalse(Effective.clock24h(Settings(), mosque(strict)))
    }

    @Test
    fun notFollowingKeepsOurs() {
        val s = Settings(source = SourceSettings(followMosque = false), flow = FlowSettings(blackScreen = false, jumuaMinutes = 30))
        val b = Effective.behavior(s, mosque(strict))
        assertEquals(List(5) { true }, b.adhanEnabled)
        assertFalse(b.blackScreen)
        assertEquals(30, b.jumuaMinutes)
        assertTrue(Effective.clock24h(s, mosque(strict)))
    }

    @Test
    fun calcSourceNeverFollowsAMosque() {
        val s = Settings(source = SourceSettings(kind = "calc"))
        assertFalse(Effective.behavior(s, mosque(strict)).blackScreen)
    }

    @Test
    fun nightAndReminderAlwaysComeFromOurSettings() {
        val s = Settings(night = NightSettings(mode = "black", afterIshaMinutes = 90), adhan = AdhanSettings(reminderMinutes = 7))
        val b = Effective.behavior(s, mosque(strict))
        assertEquals(NightMode.BLACK, b.nightMode)
        assertEquals(90, b.nightAfterIshaMinutes)
        assertEquals(7, b.reminderMinutes)
    }

    @Test
    fun fajrGetsTheFajrVersionOfACdnVoice() {
        val s = Settings(adhan = AdhanSettings(voice = "adhan-egypt"))
        assertEquals(Effective.Sound.Cdn("adhan-egypt-fajr"), Effective.adhanSound(s, null, PrayerKey.FAJR))
        assertEquals(Effective.Sound.Cdn("adhan-egypt"), Effective.adhanSound(s, null, PrayerKey.ASR))
        val explicit = s.copy(adhan = s.adhan.copy(fajrVoice = "adhan-quds-fajr"))
        assertEquals(Effective.Sound.Cdn("adhan-quds-fajr"), Effective.adhanSound(explicit, null, PrayerKey.FAJR))
        assertEquals(Effective.Sound.Beep, Effective.adhanSound(s.copy(adhan = s.adhan.copy(fajrVoice = "beep")), null, PrayerKey.FAJR))
    }

    @Test
    fun mosqueVoicePrefersItsRecordingThenItsChoiceThenBeep() {
        val s = Settings(adhan = AdhanSettings(voice = "mosque"))
        val recorded = mosque(MosqueConfig(customAthanUrl = "https://cdn/x.mp3", adhanVoice = "adhan-madina"))
        assertEquals(Effective.Sound.Url("https://cdn/x.mp3"), Effective.adhanSound(s, recorded, PrayerKey.DHUHR))
        assertEquals(Effective.Sound.Cdn("adhan-madina"), Effective.adhanSound(s, mosque(MosqueConfig(adhanVoice = "adhan-madina")), PrayerKey.ASR))
        assertEquals(Effective.Sound.Beep, Effective.adhanSound(s, mosque(MosqueConfig(adhanVoice = null)), PrayerKey.ASR))
    }

    @Test
    fun ourFlashWinsWithinItsDates() {
        val day = LocalDate.of(2026, 10, 18)
        val theirs = Flash("Mosque flash", start = day.minusDays(1), end = day.plusDays(1))
        assertEquals("Mosque flash", Effective.flash(Settings(), mosque(flash = theirs), day)?.text)
        val ours = Settings(flash = FlashSettings(text = "Ours", end = "2026-10-18"))
        assertEquals("Ours", Effective.flash(ours, mosque(flash = theirs), day)?.text)
        assertEquals("Mosque flash", Effective.flash(ours, mosque(flash = theirs), day.plusDays(1))?.text) // ours expired
        assertNull(Effective.flash(Settings(), mosque(flash = theirs), day.plusDays(2)))
    }

    @Test
    fun showFlashOffHidesOursAndTheMosques() {
        val day = LocalDate.of(2026, 10, 18)
        val off = DisplaySettings(showFlash = false)
        assertNull(Effective.flash(Settings(display = off), mosque(flash = Flash("Mosque flash")), day))
        assertNull(Effective.flash(Settings(display = off, flash = FlashSettings(text = "Ours")), null, day))
    }

    @Test
    fun announcementsRespectDatesAndSwitch() {
        val now = ZonedDateTime.of(2026, 10, 18, 12, 0, 0, 0, zone)
        val s = Settings(
            messages = listOf(
                Message("a", "Now", "x"),
                Message("b", "Later", "x", start = "2026-10-19"),
                Message("c", "Off", "x", enabled = false),
                Message("d", "", ""), // empty
            ),
        )
        val theirs = Announcement("t", "Theirs", "y", end = now.minusMinutes(1))
        assertEquals(listOf("a"), Effective.announcements(s, mosque(announcements = listOf(theirs)), now).map { it.id })
        assertTrue(Effective.announcements(s.copy(display = DisplaySettings(showAnnouncements = false)), null, now).isEmpty())
    }
}
