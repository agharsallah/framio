package dev.framio.mawaqit.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdhanAudioTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun ascii(s: String, size: Int = AudioType.HEAD) = s.toByteArray(Charsets.ISO_8859_1).copyOf(size)

    @Test
    fun sniffsTheFormatsAndroid6Plays() {
        assertEquals(AudioType.MP3, AudioType.sniff(ascii("ID3\u0004\u0000")))
        assertEquals(AudioType.MP3, AudioType.sniff(bytes(0xFF, 0xFB, 0x90, 0x64))) // MPEG-1 layer III, 128 kb/s, 44.1 kHz
        assertEquals(AudioType.MP3, AudioType.sniff(bytes(0xFF, 0xF3, 0x48, 0xC4))) // MPEG-2 layer III (say -o … mp3)
        assertEquals(AudioType.AAC, AudioType.sniff(bytes(0xFF, 0xF1, 0x50, 0x80))) // ADTS, MPEG-4, no CRC
        assertEquals(AudioType.M4A, AudioType.sniff(ascii("\u0000\u0000\u0000\u0020ftypM4A \u0000\u0000\u0000\u0000")))
        assertEquals(AudioType.M4A, AudioType.sniff(ascii("\u0000\u0000\u0000\u001cftypmp42")))
        assertEquals(AudioType.WAV, AudioType.sniff(ascii("RIFF\u0024\u0008\u0000\u0000WAVEfmt ")))
        assertEquals(AudioType.FLAC, AudioType.sniff(ascii("fLaC\u0000\u0000\u0000\"")))
        val ogg = "OggS\u0000\u0002" + "\u0000".repeat(20) + "\u0001\u001e\u0001vorbis\u0000\u0000\u0000\u0000"
        assertEquals(AudioType.OGG, AudioType.sniff(ascii(ogg)))
    }

    @Test
    fun refusesWhatTheFrameCantPlay() {
        val opus = "OggS\u0000\u0002" + "\u0000".repeat(20) + "\u0001\u0013OpusHead\u0001\u0002"
        assertNull(AudioType.sniff(ascii(opus))) // Ogg Opus: Android 10+
        assertNull(AudioType.sniff(ascii("<!doctype html><html>")))
        assertNull(AudioType.sniff(ascii("RIFF\u0024\u0008\u0000\u0000AVI LIST"))) // RIFF, but a video
        assertNull(AudioType.sniff(ascii("PK\u0003\u0004")))
        assertNull(AudioType.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0))) // JPEG: 0xFF but no audio sync
        assertNull(AudioType.sniff(bytes(0xFF, 0xFB, 0xF0, 0x00))) // MPEG sync with the "bad" bitrate index
        assertNull(AudioType.sniff(bytes(0xFF, 0xEB, 0x90, 0x00))) // reserved MPEG version
        assertNull(AudioType.sniff(ByteArray(0)))
        assertNull(AudioType.sniff(ascii("ID")))
    }

    @Test
    fun namesAreCleanedAndFilesNamedByIdOnly() {
        assertEquals("Adhan Makkah", AdhanFiles.cleanName("../../etc/Adhan  Makkah.MP3"))
        assertEquals("Fajr", AdhanFiles.cleanName("C:\\Users\\x\\Fajr.m4a"))
        assertEquals("My adhan", AdhanFiles.cleanName("  \u0000\u0007 "))
        assertEquals("My adhan", AdhanFiles.cleanName(null))
        assertEquals(60, AdhanFiles.cleanName("x".repeat(200)).length)
        val id = AdhanFiles.newId { it - 1 }
        assertTrue(Regex("[a-z0-9]{8}").matches(id))
        assertEquals("custom-$id.ogg", AdhanFiles.fileName(id, AudioType.OGG))
        assertEquals(AudioType.FLAC, AudioType.ofExt("FLAC"))
    }

    private val mine = CustomAdhan("abcd1234", "Grandpa", 95)
    private val fajr = CustomAdhan("fajr0001", "Fajr calm", 140)
    private fun settings(voice: String, fajrVoice: String = "same", custom: List<CustomAdhan> = listOf(mine, fajr)) =
        Settings(adhan = AdhanSettings(voice = voice, fajrVoice = fajrVoice, custom = custom))

    @Test
    fun customVoiceResolvesToItsFileForEveryPrayer() {
        val s = settings("custom:abcd1234")
        assertEquals(Effective.Sound.Custom("abcd1234"), Effective.adhanSound(s, null, PrayerKey.DHUHR))
        assertEquals(Effective.Sound.Custom("abcd1234"), Effective.adhanSound(s, null, PrayerKey.FAJR)) // no "-fajr" version
    }

    @Test
    fun fajrCanUseAnotherCustomFile() {
        val s = settings("adhan-madina", fajrVoice = "custom:fajr0001")
        assertEquals(Effective.Sound.Custom("fajr0001"), Effective.adhanSound(s, null, PrayerKey.FAJR))
        assertEquals(Effective.Sound.Cdn("adhan-madina"), Effective.adhanSound(s, null, PrayerKey.ISHA))
    }

    @Test
    fun aDeletedAdhanFallsBackToTheDefaultVoice() {
        val s = settings("custom:gone0000")
        assertEquals(Effective.Sound.Cdn("adhan-afassy"), Effective.adhanSound(s, null, PrayerKey.ASR))
        assertEquals(Effective.Sound.Cdn("adhan-afassy-fajr"), Effective.adhanSound(s, null, PrayerKey.FAJR))
        // what the store actually does: the reference is dropped when the settings are read back
        val json = JSONObject().put("adhan", JSONObject().put("voice", "custom:gone0000").put("fajrVoice", "custom:gone0000")
            .put("custom", JSONArray().put(mine.toJson())))
        val read = Settings.fromJson(json).adhan
        assertEquals("adhan-afassy", read.voice)
        assertEquals("same", read.fajrVoice)
        assertEquals(listOf(mine), read.custom)
    }

    @Test
    fun adhanScreenFollowsTheCustomFileLength() {
        val notFollowing = settings("custom:abcd1234").let { it.copy(source = it.source.copy(followMosque = false)) }
        assertEquals(95, Effective.behavior(notFollowing, null).adhanSeconds)
        val mosque = Mosque(
            slug = "m", name = "M", address = null, zone = java.time.ZoneId.of("Europe/Paris"), latitude = 0.0, longitude = 0.0,
            countryCode = null, uuid = null, mawaqitId = null, lastUpdated = null, config = MosqueConfig(adhanDurationSeconds = 200),
            announcements = emptyList(), flash = null, logoUrl = null, imageUrl = null, streamUrl = null, site = null, phone = null,
            paymentWebsite = null, hijriAdjustment = 0, hijriForce30 = false, eidTimes = emptyList(), closed = null,
        )
        assertEquals(95, Effective.behavior(settings("custom:abcd1234"), mosque).adhanSeconds) // the voice is ours, so is its length
        assertEquals(200, Effective.behavior(settings("adhan-egypt", fajrVoice = "custom:fajr0001"), mosque).adhanSeconds)
        assertEquals(150, Effective.behavior(settings("custom:abcd1234", custom = listOf(mine.copy(seconds = 0))).let { it.copy(source = it.source.copy(followMosque = false)) }, null).adhanSeconds)
        assertEquals(600, Effective.customAdhanSeconds(settings("custom:abcd1234", custom = listOf(mine.copy(seconds = 1500)))))
    }
}
