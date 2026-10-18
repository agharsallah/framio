package dev.framio.mawaqit.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class MawaqitPageTest {
    private val paris = ZoneId.of("Europe/Paris")
    private val html = javaClass.classLoader!!.getResource("paris.html")!!.readText()

    @Test
    fun parsesTheParisPage() {
        val page = MawaqitPage.parse(html, "grande-mosquee-de-paris")
        val m = page.mosque
        assertEquals("grande-mosquee-de-paris", m.slug)
        assertEquals("GRANDE MOSQUÉE DE PARIS", m.name)
        assertEquals(paris, m.zone)
        assertEquals("05b4d393-fb76-4d9b-b2a4-f98ab4c4b64f", m.uuid)
        assertEquals(256, m.mawaqitId)
        assertEquals(1790798435L, m.lastUpdated)
        assertEquals(48.842377464254, m.latitude, 1e-9)
        assertEquals(2.3548269608521, m.longitude, 1e-9)
        assertEquals("FR", m.countryCode)
        assertNull(m.address) // no localisation in this confData
        assertEquals(listOf("07:30", "08:15"), m.eidTimes)
        assertEquals(-1, m.hijriAdjustment)
        assertFalse(m.hijriForce30)
        assertNull(m.logoUrl)
        assertNull(m.streamUrl) // ""
        assertNull(m.closed)
        assertEquals("https://www.grandemosqueedeparis.fr", m.site)
        assertTrue(m.announcements.isEmpty())
        assertNull(m.flash)
        assertEquals("GRANDE MOSQUÉE DE PARIS", page.confJson.getString("name"))
    }

    @Test
    fun mapsTheScreenSettings() {
        val c = MawaqitPage.parse(html, "grande-mosquee-de-paris").mosque.config
        assertEquals(List(5) { true }, c.adhanEnabled)
        assertNull(c.adhanVoice)
        assertEquals(150, c.adhanDurationSeconds)
        assertEquals(listOf(10, 8, 8, 8, 9), c.prayerMinutes)
        assertEquals(45, c.iqamaDisplaySeconds)
        assertEquals(40, c.jumuaMinutes)
        assertTrue(c.jumuaReminder)
        assertFalse(c.jumuaBlackScreen)
        assertFalse(c.iqamaBeep)
        assertTrue(c.iqamaFullScreenCountdown)
        assertTrue(c.temperatureEnabled)
        assertEquals("fr-ar", c.hadithLang)
        assertEquals("autumn", c.theme)
        assertEquals("#1b1b1b", c.backgroundColor)
        assertTrue(c.clock24h)
        assertTrue(c.hijriEnabled)
        assertNull(c.wakeForFajrMinutes)
    }

    @Test
    fun readsTheCalendar() {
        val t = MawaqitPage.parse(html, "grande-mosquee-de-paris").times
        val jan1 = t.day(LocalDate.of(2026, 1, 1))!!
        assertEquals(ZonedDateTime.of(2026, 1, 1, 7, 5, 0, 0, paris), jan1.fajr!!.adhan)
        assertEquals(ZonedDateTime.of(2026, 1, 1, 7, 13, 0, 0, paris), jan1.fajr!!.iqama) // +8
        assertEquals(8, jan1.fajr!!.iqamaOffset)
        assertEquals(jan1.maghrib!!.adhan, jan1.maghrib!!.iqama) // +0
        val friday = t.day(LocalDate.of(2026, 10, 23))!!
        assertEquals(listOf("13:50", "14:30"), friday.jumua.map { it.adhan.toLocalTime().toString() })
        assertTrue(t.day(LocalDate.of(2026, 10, 22))!!.jumua.isEmpty())
        assertEquals(paris, t.zone)
        for (d in 1..365) assertTrue("day $d", t.day(LocalDate.ofYearDay(2026, d))?.isha != null)
    }

    @Test
    fun failsWithoutConfData() {
        for (bad in listOf("<html>nothing</html>", "let confData = {\"a\": [1, 2", "var confData = {\"a\" 1};")) {
            try {
                MawaqitPage.parse(bad, "x")
                fail("parsed $bad")
            } catch (e: MawaqitPageException) {
                assertTrue(e.message!!.contains("x"))
            }
        }
    }

    @Test
    fun extractsABalancedObjectWithBracesInStrings() {
        val page = """<script>var confData = {"name":"A } { \" b","label":null,"footer":"};","timezone":"Asia/Riyadh"};
            var other = {"x": 1};</script>"""
        val parsed = MawaqitPage.parse(page, "a")
        assertEquals("A } { \" b", parsed.mosque.name)
        assertEquals(ZoneId.of("Asia/Riyadh"), parsed.mosque.zone)
        assertEquals("};", parsed.confJson.getString("footer"))
        assertNull(parsed.mosque.uuid)
        assertNull(parsed.mosque.mawaqitId)
        assertNull(parsed.mosque.lastUpdated)
        assertNull(parsed.times.day(LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun parseConfFromTheApi() {
        val conf = JSONObject(
            """{
            "uuid": "u-1", "id": 42, "name": "Masjid", "label": "", "localisation": "1 Main St", "phone": "+31 20",
            "timezone": "Europe/Amsterdam", "latitude": "52.1", "longitude": 4.9, "iqamaEnabled": false,
            "adhanEnabledByPrayer": ["1", "0", 1, false, "1"], "adhanVoice": "adhan-afassy", "duaAfterPrayerShowTimes": ["12", "x"],
            "timeDisplayFormat": "12", "wakeForFajrTime": 20, "jumuaAsDuhr": true, "imsakNbMinBeforeFajr": 10,
            "aidPrayerTime": "--:--", "aidPrayerTime2": "08:00",
            "calendar": [{"5": ["06:30", "08:00", "13:00", "16:00", "18:45", "20:15"]}],
            "iqamaCalendar": [{"5": ["+20", "+10", "+10", "+5", "+10"]}],
            "announcements": [
              {"id": 7, "title": "Iftar", "content": "Sunday", "image": null, "startDate": "2026-01-02 18:00",
               "endDate": "2026-01-09 23:59", "duration": 30, "isMobile": true, "isDesktop": true},
              {"id": 8, "title": "App only", "isDesktop": false},
              {"id": 9, "title": "No flags", "duration": 0, "startDate": "nonsense"}
            ],
            "flash": {"content": "Collecte vendredi", "startDate": "2026-01-01", "endDate": "2026-01-31", "color": "#AA0000", "orientation": "rtl"}
            }""",
        )
        val p = MawaqitPage.parseConf(conf, "masjid", lastUpdated = 5)
        val m = p.mosque
        assertEquals("Masjid", m.name)
        assertEquals("1 Main St", m.address)
        assertEquals("+31 20", m.phone)
        assertEquals("u-1", m.uuid)
        assertEquals(42, m.mawaqitId)
        assertEquals(5L, m.lastUpdated)
        assertEquals(52.1, m.latitude, 1e-9)
        assertEquals(listOf("08:00"), m.eidTimes)
        assertEquals(listOf(true, false, true, false, true), m.config.adhanEnabled)
        assertEquals("adhan-afassy", m.config.adhanVoice)
        assertEquals(listOf(12, 8, 8, 8, 9), m.config.prayerMinutes)
        assertFalse(m.config.clock24h)
        assertEquals(20, m.config.wakeForFajrMinutes)

        assertEquals(listOf("7", "9"), m.announcements.map { it.id })
        val a = m.announcements[0]
        assertEquals("Iftar", a.title)
        assertNull(a.imageUrl)
        assertEquals(30, a.seconds)
        assertEquals(ZonedDateTime.of(2026, 1, 2, 18, 0, 0, 0, ZoneId.of("Europe/Amsterdam")), a.start)
        assertEquals(ZonedDateTime.of(2026, 1, 9, 23, 59, 0, 0, ZoneId.of("Europe/Amsterdam")), a.end)
        assertEquals(15, m.announcements[1].seconds)
        assertNull(m.announcements[1].start)

        assertEquals(Flash("Collecte vendredi", "#AA0000", true, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)), m.flash)

        val day = p.times.day(LocalDate.of(2026, 1, 5))!!
        assertNull(day.fajr!!.iqama) // iqamaEnabled false
        assertEquals(ZonedDateTime.of(2026, 1, 5, 6, 20, 0, 0, ZoneId.of("Europe/Amsterdam")), day.imsak!!.adhan)
        assertNull(p.times.day(LocalDate.of(2026, 2, 5))) // only January given
    }

    @Test
    fun blankFlashIsNone() {
        val conf = JSONObject("""{"timezone": "Europe/Paris", "flash": {"content": "  ", "color": "red"}}""")
        assertNull(MawaqitPage.parseConf(conf, "x").mosque.flash)
        val noColor = JSONObject("""{"timezone": "Europe/Paris", "flash": {"content": "Hi", "color": "red"}}""")
        assertEquals(Flash("Hi"), MawaqitPage.parseConf(noColor, "x").mosque.flash)
    }

    @Test
    fun parsesSearchHits() {
        val hits = MawaqitPage.parseSearch(
            """[
            {"uuid": "a", "name": "Mosquee A", "label": "Mosquée A", "slug": "mosquee-a", "localisation": "Paris", "proximity": 1234, "latitude": 1, "longitude": 2},
            {"uuid": "b", "name": "B", "label": null, "slug": null, "localisation": null},
            {"name": "no uuid"}
            ]""",
        )
        assertEquals(
            listOf(SearchHit("mosquee-a", "a", "Mosquée A", "Paris", 1234), SearchHit(null, "b", "B", null, null)),
            hits,
        )
        assertTrue(MawaqitPage.parseSearch("[]").isEmpty())
        try {
            MawaqitPage.parseSearch("""{"error": "token"}""")
            fail()
        } catch (e: MawaqitPageException) {
        }
    }
}
