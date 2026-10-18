package dev.framio.mawaqit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** The shipped `assets/adhkar.json`, as the app reads it. */
class AdhkarTest {
    private val raw = File("src/main/assets/adhkar.json").readText()
    private val book = AdhkarBook.parse(raw)
    private val arabicLetter = Regex("[\\u0621-\\u064A]")

    @Test
    fun everyCategoryIsThere() {
        val sizes = AdhkarCategory.entries.associateWith { book.items(it).size }
        assertEquals(26, sizes[AdhkarCategory.MORNING])
        assertEquals(24, sizes[AdhkarCategory.EVENING])
        assertEquals(15, sizes[AdhkarCategory.AFTER_PRAYER])
        assertEquals(15, sizes[AdhkarCategory.SLEEP])
        assertEquals(4, sizes[AdhkarCategory.WAKING])
        assertEquals(8, sizes[AdhkarCategory.DHIKR])
        assertEquals(7, sizes[AdhkarCategory.ISTIGHFAR])
        assertEquals(3, sizes[AdhkarCategory.SALAWAT])
        assertEquals(15, sizes[AdhkarCategory.QURAN])
        assertEquals(10, sizes[AdhkarCategory.DISTRESS])
        assertEquals(21, sizes[AdhkarCategory.EVERYDAY])
        assertEquals(4, book.tasbih.size)
        assertEquals(listOf(true, true, true, true, true, false, false, false, false, false, false), AdhkarCategory.entries.map { it.daily })
    }

    @Test
    fun everyDhikrHasArabicACountAndATranslation() {
        for (c in AdhkarCategory.entries) for (d in book.items(c)) {
            assertTrue("${d.id} Arabic", d.arabic.isNotBlank() && arabicLetter.containsMatchIn(d.arabic))
            assertTrue("${d.id} count", d.count > 0 && (d.countFajrMaghrib ?: 1) > 0)
            assertFalse("${d.id} translation", d.translation.isNullOrBlank())
        }
    }

    @Test
    fun idsAreUnique() {
        // JSONObject would silently keep the last of two equal keys: look at the file itself
        val ids = Regex("\"([a-z]{1,2}\\d+(?:_\\d+)?[a-c]?)\":\\{\"ar\"").findAll(raw).map { it.groupValues[1] }.toList()
        assertEquals(130, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        for (c in AdhkarCategory.entries) {
            val inCategory = book.items(c).map { it.id }
            assertEquals("$c has a dhikr twice", inCategory.size, inCategory.toSet().size)
        }
    }

    @Test
    fun morningAndEveningHaveTheirOwnWording() {
        val morning = book.items(AdhkarCategory.MORNING).map { it.id }
        val evening = book.items(AdhkarCategory.EVENING).map { it.id }
        assertTrue("me7" in morning && "me7" !in evening) // asbahna…
        assertTrue("me8" in evening && "me8" !in morning) // amsayna…
        assertEquals("Muslim 2723", book.items(AdhkarCategory.MORNING).first { it.id == "me7" }.reference)
        val istighfar = book.items(AdhkarCategory.MORNING).first { it.id == "me11" } // Sayyid al-Istighfar
        assertEquals("Al-Bukhari 6306", istighfar.reference)
        assertNotNull(istighfar.virtue("en"))
        assertTrue(arabicLetter.containsMatchIn(istighfar.virtue("ar")!!))
    }

    @Test
    fun afterPrayerDependsOnThePrayer() {
        val dhuhr = book.items(AdhkarCategory.AFTER_PRAYER, PrayerKey.DHUHR)
        val fajr = book.items(AdhkarCategory.AFTER_PRAYER, PrayerKey.FAJR)
        val maghrib = book.items(AdhkarCategory.AFTER_PRAYER, PrayerKey.MAGHRIB)
        assertEquals(13, dhuhr.size)
        assertEquals(15, fajr.size)
        assertEquals(14, maghrib.size) // ten tahlil, no dua for knowledge
        val ikhlas = fajr.first { it.id == "ap10" }
        assertEquals(3, ikhlas.count(PrayerKey.FAJR))
        assertEquals(1, ikhlas.count(PrayerKey.ASR))
        assertEquals(listOf(33, 33, 33), listOf("ap5", "ap6", "ap7").map { id -> dhuhr.first { it.id == id }.count(PrayerKey.DHUHR) })
        assertEquals("ap1", dhuhr.first().id) // astaghfirullah first, as on Mawaqit's screen
    }

    @Test
    fun theSleepTasbihIsSplitIntoItsThreeCounts() {
        val sleep = book.items(AdhkarCategory.SLEEP)
        assertEquals(listOf(33, 33, 34), listOf("hm126a", "hm126b", "hm126c").map { id -> sleep.first { it.id == id }.count })
        assertTrue(sleep.none { it.arabic.startsWith("و") || it.arabic.startsWith("َ") })
    }

    @Test
    fun theNewCollectionsCarryTheirOccasionAndTheirSources() {
        // the dhikr is cut out of its hadith, which stays as the virtue
        val subhan = book.items(AdhkarCategory.DHIKR).first { it.id == "hm274" }
        assertEquals(100, subhan.count)
        assertEquals("Glorified is Allah and praised is He", subhan.translation)
        assertTrue(subhan.virtueAr!!.length > subhan.arabic.length && subhan.virtueAr!!.contains(subhan.arabic))
        // Sayyid al-Istighfar is the same item in the morning and in forgiveness
        assertTrue(book.items(AdhkarCategory.ISTIGHFAR).any { it.id == "me11" })
        val mosque = book.items(AdhkarCategory.EVERYDAY).first { it.id == "fd8" }
        assertEquals("Entering the mosque", mosque.title("en"))
        assertTrue(arabicLetter.containsMatchIn(mosque.title("ar")!!))
        assertEquals("Entering the mosque", mosque.title("fr")) // no French title: the English one
        val quran = book.items(AdhkarCategory.QURAN)
        assertTrue(quran.all { it.translation!!.startsWith("Our Lord!") || it.translation!!.startsWith("My Lord!") })
        assertEquals("Taa-Haa 20:25-28", quran.first { it.id == "q20_25" }.reference)
        assertTrue(book.items(AdhkarCategory.DISTRESS).all { it.title != null && it.titleAr != null })
        assertNull(book.items(AdhkarCategory.MORNING).first().title)
    }

    @Test
    fun virtueFallsBackToEnglish() {
        val d = Dhikr("x", "سُبْحَانَ اللهِ", 1, virtue = "v")
        assertEquals("v", d.virtue("ar"))
        assertNull(d.copy(virtue = null).virtue("fr"))
    }

    @Test
    fun streakCountsDaysInARow() {
        val today = LocalDate.of(2026, 10, 18)
        var p = AdhkarProgress()
        assertEquals(0, p.streak("morning", today))
        p = p.withDone(today.minusDays(2), "morning").withDone(today.minusDays(1), "morning")
        assertEquals(2, p.streak("morning", today)) // today still open: yesterday's run counts
        p = p.withDone(today, "morning")
        assertEquals(3, p.streak("morning", today))
        assertEquals(1, p.withDone(today.minusDays(5), "evening").withDone(today, "evening").streak("evening", today))
        assertEquals(0, p.streak("morning", today.plusDays(2))) // a day missed
    }

    @Test
    fun countsKeepTodayAndYesterdayAndSurviveJson() {
        val d1 = LocalDate.of(2026, 10, 16)
        var p = AdhkarProgress().withCount(d1, "morning", "me1", 1)
        p = p.withCount(d1.plusDays(1), "sleep", "hm119", 2)
        p = p.withCount(d1.plusDays(2), "morning", "me2", 1).withTasbih(d1.plusDays(2), 40, "ap5")
        assertEquals(0, p.count(d1, "morning", "me1")) // two days ago: gone
        assertEquals(2, p.count(d1.plusDays(1), "sleep", "hm119"))
        p = p.withDone(d1.plusDays(2), "after_prayer:dhuhr").withDismissed("2026-10-18/evening").withAnnounced("2026-10-18/morning")
        val back = AdhkarProgress.fromJson(org.json.JSONObject(p.toJson().toString()))
        assertEquals(p, back)
        assertEquals(40, back.tasbihToday(d1.plusDays(2)))
        assertEquals(0, back.tasbihToday(d1.plusDays(3))) // a new day starts at zero
        val reset = back.reset(d1.plusDays(2), "morning")
        assertTrue(reset.counts(d1.plusDays(2), "morning").isEmpty())
        assertEquals("after_prayer:asr", AdhkarProgress.session(AdhkarCategory.AFTER_PRAYER, PrayerKey.ASR))
        assertEquals("morning", AdhkarProgress.session(AdhkarCategory.MORNING, PrayerKey.ASR))
        assertEquals(AdhkarProgress(), AdhkarProgress.fromJson(org.json.JSONObject("{\"counts\":{\"nope\":{}},\"done\":{\"x\":[\"bad\"]}}")).copy(done = emptyMap()))
    }
}
