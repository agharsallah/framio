package dev.framio.mawaqit.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/** The adhkar display's loop and the main screen's dhikr slides, on the shipped book. */
class AdhkarLoopTest {
    private val book = AdhkarBook.parse(File("src/main/assets/adhkar.json").readText())
    private val s = AdhkarLoopSettings()
    private val all = AdhkarCategory.entries.flatMap { book.items(it) }.distinctBy { it.id }

    @Test
    fun settingsDefaultsAndBounds() {
        assertEquals(listOf(AdhkarCategory.DHIKR, AdhkarCategory.ISTIGHFAR, AdhkarCategory.SALAWAT, AdhkarCategory.QURAN), s.categories)
        assertEquals(s, AdhkarLoopSettings.fromJson(null))
        assertEquals(s, AdhkarLoopSettings.fromJson(JSONObject(s.toJson().toString())))
        val odd = AdhkarLoopSettings.fromJson(JSONObject("""{"home":"elsewhere","speed":900,"order":"random","returnMinutes":-3,"collections":["nope","quran","quran","sleep"]}"""))
        assertEquals("main", odd.home)
        assertEquals(200, odd.speed)
        assertEquals("shuffle", odd.order)
        assertEquals(0, odd.returnMinutes)
        assertEquals(listOf("quran", "sleep"), odd.collections) // unknown dropped, each once
        // nothing valid: the default mix, never a blank page
        assertEquals(AdhkarLoopSettings.DEFAULT_COLLECTIONS, AdhkarLoopSettings.fromJson(JSONObject("""{"collections":["x"]}""")).collections)
        val settings = Settings.fromJson(JSONObject("""{"loop":{"home":"adhkar"},"display":{"adhkarSlidePercent":0}}"""))
        assertEquals("adhkar", settings.loop.home)
        assertEquals(5, settings.display.adhkarSlidePercent)
        assertTrue(Settings().display.showAdhkarSlides)
    }

    @Test
    fun timeAwareLoopsTheCollectionWhoseTimeItIs() {
        assertEquals(listOf(AdhkarCategory.MORNING), AdhkarLoop.collections(s, AdhkarCategory.MORNING))
        assertEquals(listOf(AdhkarCategory.SLEEP), AdhkarLoop.collections(s, AdhkarCategory.SLEEP))
        assertEquals(s.categories, AdhkarLoop.collections(s, null))
        assertEquals(s.categories, AdhkarLoop.collections(s, AdhkarCategory.AFTER_PRAYER)) // said once, not looped
        assertEquals(s.categories, AdhkarLoop.collections(s.copy(timeAware = false), AdhkarCategory.EVENING))
    }

    @Test
    fun splitNeverCutsAWordNorDropsOne() {
        for (d in all) {
            val parts = AdhkarLoop.split(d.arabic) ?: continue
            assertEquals(d.id, d.arabic.trim().split(' ').filter { it.isNotEmpty() }.joinToString(" "), parts.joinToString(" "))
            assertTrue(d.id, parts.size <= AdhkarLoop.MAX_PARTS && parts.all { it.length <= AdhkarLoop.PAGE_CHARS && it.isNotBlank() })
        }
        // only the very longest (Al 'Imran 190–200 on waking) is left out of the loop
        val skipped = all.filter { AdhkarLoop.split(it.arabic) == null }
        assertTrue(skipped.joinToString { it.id }, skipped.all { it.arabic.length > AdhkarLoop.PAGE_CHARS * 2 })
        assertEquals(listOf("short"), AdhkarLoop.split("short"))
    }

    @Test
    fun splitPrefersPunctuationAndEvenPages() {
        val clause = "كلمة ".repeat(20).trim() // 99 letters
        val text = "$clause, $clause, $clause, $clause"
        val parts = AdhkarLoop.split(text, max = 220)!!
        assertEquals(2, parts.size)
        assertTrue(parts[0], parts[0].endsWith(","))
        assertEquals(parts[0].length.toDouble(), parts[1].length.toDouble(), 10.0)
        // a pause mark of the Quran (Tanzil writes it as a word) is never the first thing on a page
        val quran = "$clause ۖ $clause ۚ $clause"
        AdhkarLoop.split(quran, max = 220)!!.forEach { assertFalse(it, it.startsWith("ۖ") || it.startsWith("ۚ")) }
        assertNull(AdhkarLoop.split("$clause ".repeat(10), max = 220)) // would need 5 pages
    }

    @Test
    fun longDhikrGetsItsMeaningOnAPageOfItsOwn() {
        val kursi = book.items(AdhkarCategory.MORNING).first { it.id == "me2" } // Ayat al-Kursi with the isti'adha
        val pages = AdhkarLoop.pages(kursi, AdhkarCategory.MORNING, s, latin = true)
        val arabic = pages.filter { !it.meaning }
        assertTrue(arabic.size >= 2)
        assertEquals((1..arabic.size).toList(), arabic.map { it.part })
        assertTrue(arabic.all { it.translation == null })
        assertTrue(pages.last().meaning && pages.last().translation == kursi.translation)
        // in Arabic, no translation at all
        assertTrue(AdhkarLoop.pages(kursi, AdhkarCategory.MORNING, s, latin = false).none { it.meaning || it.translation != null })
        // a short one carries its translation (and transliteration when asked) under the Arabic
        val subhan = book.items(AdhkarCategory.DHIKR).first { it.id == "hm274" }
        val one = AdhkarLoop.pages(subhan, AdhkarCategory.DHIKR, s.copy(transliteration = true), latin = true).single()
        assertEquals(subhan.translation, one.translation)
        assertEquals(subhan.transliteration, one.transliteration)
        assertEquals("hm274/1", one.key)
    }

    @Test
    fun readingTimeGrowsWithTheTextWithinBounds() {
        val short = AdhkarLoop.millis(AdhkarLoop.seconds(10, 0), 100)
        val long = AdhkarLoop.millis(AdhkarLoop.seconds(290, 0), 100)
        assertEquals(8_000, short) // at least 8 s
        assertTrue(long in 30_000..40_000)
        assertEquals(50_000, AdhkarLoop.millis(AdhkarLoop.seconds(2000, 2000), 100)) // at most 50 s
        assertEquals(long / 2, AdhkarLoop.millis(AdhkarLoop.seconds(290, 0), 200))
        assertEquals(16_000, AdhkarLoop.millis(AdhkarLoop.seconds(10, 0), 50))
        assertTrue(AdhkarLoop.millis(AdhkarLoop.seconds(100, 200), 100) > AdhkarLoop.millis(AdhkarLoop.seconds(100, 0), 100))
    }

    @Test
    fun aRoundShowsEveryDhikrOnceAndShufflesDifferentlyEachRound() {
        val expected = AdhkarLoop.items(book, s.categories).map { it.first.id }.toSet()
        val r0 = AdhkarLoop.playlist(book, s, null, latin = true, cycle = 0)
        val r1 = AdhkarLoop.playlist(book, s, null, latin = true, cycle = 1)
        assertEquals(expected, r0.map { it.dhikr.id }.toSet())
        assertEquals(r0.size, r1.size)
        assertNotEquals(r0.map { it.key }, r1.map { it.key })
        assertEquals(r0, AdhkarLoop.playlist(book, s, null, latin = true, cycle = 0)) // same round, same order
        for (c in 1..30) {
            val before = AdhkarLoop.playlist(book, s, null, latin = true, cycle = c - 1)
            val now = AdhkarLoop.playlist(book, s, null, latin = true, cycle = c)
            assertNotEquals("round $c starts where $c - 1 ended", before.last().dhikr.id, now.first().dhikr.id)
            // the pages of one dhikr stay together and in order
            now.zipWithNext().filter { (a, b) -> a.dhikr.id == b.dhikr.id }.forEach { (a, b) -> assertTrue(b.meaning || b.part == a.part + 1) }
        }
        val sequential = AdhkarLoop.playlist(book, s.copy(order = "sequential"), null, latin = true, cycle = 5)
        assertEquals(AdhkarLoop.items(book, s.categories).map { it.first.id }, sequential.map { it.dhikr.id }.distinct())
        // in the morning window, the morning adhkar
        assertTrue(AdhkarLoop.playlist(book, s, AdhkarCategory.MORNING, latin = true, cycle = 0).all { it.category == AdhkarCategory.MORNING })
    }

    @Test
    fun slidesAreShortAndNeverJustBeforeAnAdhan() {
        val candidates = AdhkarLoop.slideCandidates(book, s)
        assertTrue(candidates.size >= 10)
        assertTrue(candidates.all { (d, _) -> d.arabic.length <= AdhkarLoop.SLIDE_ARABIC && (d.translation?.length ?: 0) <= AdhkarLoop.SLIDE_LATIN })
        val random = Random(1)
        val recent = ArrayDeque<String>()
        repeat(40) {
            val (d, _) = AdhkarLoop.pick(candidates, recent, random)!!
            assertFalse(d.id in recent)
            recent.addLast(d.id)
            if (recent.size > 6) recent.removeFirst()
        }
        assertNull(AdhkarLoop.pick(emptyList(), recent, random))
        val now = ZonedDateTime.of(2026, 10, 18, 14, 0, 0, 0, ZoneId.of("Europe/Berlin"))
        assertTrue(AdhkarLoop.slideAllowed(now, PhaseKind.NORMAL, now.plusMinutes(10)))
        assertFalse(AdhkarLoop.slideAllowed(now, PhaseKind.NORMAL, now.plusMinutes(9)))
        assertFalse(AdhkarLoop.slideAllowed(now, PhaseKind.REMINDER, now.plusHours(2)))
        assertFalse(AdhkarLoop.slideAllowed(now, PhaseKind.IQAMA_COUNTDOWN, now.plusHours(2)))
        assertTrue(AdhkarLoop.slideAllowed(now, PhaseKind.NORMAL, null))
    }
}
