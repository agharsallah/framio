package dev.framio.meadow.worlds.words

import dev.framio.meadow.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class LettersTest {

    private fun ids(lang: String): Set<String> = listOf("common", "words").flatMap { f ->
        File("../voice/$lang/$f.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('\t').trim() }
    }.toSet()

    private val sprites = File("src/main/assets/sprites").list()!!.map { it.removeSuffix(".webp") }.toSet()

    @Test fun bothAlphabetsAreComplete() {
        for (lang in Lang.entries) {
            val a = Alphabet.of(lang)
            assertEquals(('A'..'Z').toList(), a.map { it.letter })
        }
    }

    @Test fun everyWordHasItsSpriteAndLines() {
        for (lang in Lang.entries) {
            val have = ids(lang.code)
            for (w in Alphabet.of(lang)) {
                assertTrue("${w.sprite} missing", w.sprite in sprites)
                assertTrue("name_${w.thing} missing in ${lang.code}", "name_${w.thing}" in have)
                assertTrue("abc_${w.id} missing in ${lang.code}", "abc_${w.id}" in have)
                assertTrue("letter_${w.id} missing in ${lang.code}", "letter_${w.id}" in have)
            }
            for (w in Alphabet.firstSoundLetters(lang)) assertTrue("firstsound_q_${w.id}", "firstsound_q_${w.id}" in have)
        }
    }

    @Test fun voiceIdsMatchAcrossLanguages() {
        fun own(lang: String) = File("../voice/$lang/words.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBefore('\t') }
        assertEquals(own("en").toSet(), own("de").toSet())
        assertEquals(own("en").size, own("en").toSet().size)
    }

    @Test fun firstSoundChoicesNeverShareTheAnswersSound() {
        val r = Random(3)
        for (lang in Lang.entries) for (level in 0..4) for (t in Alphabet.firstSoundLetters(lang)) repeat(5) {
            val n = if (level == 0) 2 else 3
            val opts = Alphabet.firstSoundRound(lang, t, n, level, r)
            assertEquals(n, opts.size)
            assertEquals(1, opts.count { it == t })
            assertEquals("sounds must all differ: $opts", n, opts.map { it.sound }.toSet().size)
        }
    }

    @Test fun ambiguousLettersAreNotAskedAbout() {
        assertTrue(Alphabet.firstSoundLetters(Lang.DE).none { it.letter in "CQVXY" })
        assertTrue(Alphabet.firstSoundLetters(Lang.EN).none { it.letter == 'X' })
    }

    @Test fun targetsAreDifferentAndPreferFreshOnes() {
        val r = Random(5)
        val t = Alphabet.firstSoundTargets(Lang.EN, 5, r, recent = "ABCDEFGHIJKLMNOPQRSTU".toSet())
        assertEquals(5, t.map { it.letter }.toSet().size)
        assertTrue(t.take(4).all { it.letter in "VWYZ" })
    }
}
