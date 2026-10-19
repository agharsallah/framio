package dev.framio.meadow.worlds.words

import dev.framio.meadow.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class FarmAndGardenTest {

    private val sprites = File("src/main/assets/sprites").list()!!.map { it.removeSuffix(".webp") }.toSet()

    @Test fun everyAnimalHasSpritesAndLines() {
        for (lang in listOf("en", "de")) {
            val ids = File("../voice/$lang/words.tsv").readLines().map { it.substringBefore('\t') }.toSet()
            for (a in Farm.animals) {
                assertTrue(a.idle in sprites && a.happy in sprites)
                assertTrue("animals_says_${a.id}" in ids && "animals_who_${a.id}" in ids)
            }
        }
    }

    @Test fun aQuestionNeverHasTwoAnimalsSayingTheSameThing() {
        val r = Random(2)
        for (lang in Lang.entries) for (t in Farm.animals) for (n in 2..3) repeat(10) {
            val opts = Farm.quizRound(lang, t, n, r)
            assertEquals(n, opts.size)
            assertTrue(t in opts)
            assertEquals(n, opts.map { it.sound(lang) }.toSet().size)
        }
        // in German the frog and the duck both say "quak"
        val de = Farm.animals.groupBy { it.sound(Lang.DE) }
        assertEquals(setOf("frog", "duck"), de.getValue("quak").map { it.id }.toSet())
    }

    @Test fun theMeadowHasDifferentVoices() {
        val r = Random(4)
        repeat(50) {
            val m = Farm.meadow(Lang.DE, 5, r)
            assertEquals(5, m.size)
            assertEquals(5, m.map { it.sound(Lang.DE) }.toSet().size)
        }
    }

    @Test fun quizAsksGreetedFriendsFirst() {
        val met = Farm.animals.take(3)
        val t = Farm.quizTargets(met, 5, Random(1))
        assertEquals(met.toSet(), t.take(3).toSet())
        assertEquals(5, t.toSet().size)
    }

    @Test fun spotsStayInsideAndClearOfTheFriend() {
        for (n in 2..6) {
            val spots = Farm.spots(n)
            assertEquals(n, spots.size)
            for ((x, y, k) in spots) {
                val wPx = 1280f
                val hPx = 688f
                val size = 210f * k
                val left = x * wPx - size / 2
                val right = x * wPx + size / 2
                val top = y * hPx - size
                assertTrue(left >= 0f && right <= wPx && top >= 0f && y <= 1f)
                // Bo sits in the bottom-left 230×230 of the screen
                assertTrue("spot $x,$y overlaps the friend", left >= 230f || y * hPx <= hPx - 230f)
            }
        }
    }

    @Test fun visibleTilesFollowTheScroll() {
        // tiles 150 wide, 172 apart, starting 120 in; a 1280 viewport
        assertEquals(0..5, visibleTiles(0, 1280, 120, 172, 150, 26))
        assertEquals(1..6, visibleTiles(172, 1280, 120, 172, 150, 26))
        assertEquals(25, visibleTiles(10_000, 1280, 120, 172, 150, 26).last)
    }

    @Test fun seekPrefersLettersNotYetGrown() {
        val r = Random(9)
        repeat(30) { assertEquals(3, seekTarget(0..3, setOf(0, 1, 2), r)) }
        repeat(30) { assertTrue(seekTarget(2..4, setOf(2, 3, 4), r) in 2..4) }
    }
}
