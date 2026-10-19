package dev.framio.meadow.worlds.words

import dev.framio.meadow.core.Lang
import kotlin.random.Random

/**
 * One letter of the alphabet and the thing it stands for in a language ("A is for apple",
 * "A wie Apfel"). [thing] names the spoken clip `name_<thing>`; [sound] groups words whose first
 * sound is the same (cat and kite both start with /k/), so a first-sound question never offers two
 * right answers.
 */
data class LetterWord(val letter: Char, val thing: String, val sprite: String, val sound: String) {
    val id: String get() = letter.lowercaseChar().toString()
}

/** The alphabet in each language. First letters differ, so the things do too. Pure data, unit-tested. */
object Alphabet {
    val en: List<LetterWord> = listOf(
        LetterWord('A', "apple", "ob_apple", "a"),
        LetterWord('B', "ball", "ob_ball", "b"),
        LetterWord('C', "cat", "wo_cat_idle", "k"),
        LetterWord('D', "duck", "ob_duck", "d"),
        LetterWord('E', "egg", "ob_egg", "e"),
        LetterWord('F', "fish", "ob_fish", "f"),
        LetterWord('G', "grapes", "ob_grapes", "g"),
        LetterWord('H', "hat", "ob_hat", "h"),
        LetterWord('I', "icecream", "ob_icecream", "ai"),
        LetterWord('J', "jam", "wo_jam", "j"),
        LetterWord('K', "kite", "ob_kite", "k"),
        LetterWord('L', "lemon", "ob_lemon", "l"),
        LetterWord('M', "moon", "ob_moon", "m"),
        LetterWord('N', "nest", "wo_nest", "n"),
        LetterWord('O', "orange", "ob_orange", "o"),
        LetterWord('P', "pear", "ob_pear", "p"),
        LetterWord('Q', "quilt", "wo_quilt", "k"),
        LetterWord('R', "rainbow", "ob_rainbow", "r"),
        LetterWord('S', "sun", "ob_sun", "s"),
        LetterWord('T', "teddy", "ob_teddy", "t"),
        LetterWord('U', "umbrella", "ob_umbrella", "u"),
        LetterWord('V', "van", "wo_van", "v"),
        LetterWord('W', "watermelon", "ob_watermelon", "w"),
        LetterWord('X', "xylophone", "wo_xylophone", "z"), // xylophone starts with a /z/ sound
        LetterWord('Y', "yoyo", "wo_yoyo", "y"),
        LetterWord('Z', "zebra", "wo_zebra", "z"),
    )

    val de: List<LetterWord> = listOf(
        LetterWord('A', "apple", "ob_apple", "a"),
        LetterWord('B', "ball", "ob_ball", "b"),
        LetterWord('C', "clown", "wo_clown", "k"),
        LetterWord('D', "dino", "wo_dino", "d"),
        LetterWord('E', "duck", "ob_duck", "e"),
        LetterWord('F', "fish", "ob_fish", "f"),
        LetterWord('G', "gift", "ob_gift", "g"),
        LetterWord('H', "hat", "ob_hat", "h"),
        LetterWord('I', "hedgehog", "wo_hedgehog", "i"),
        LetterWord('J', "jacket", "wo_jacket", "j"),
        LetterWord('K', "cheese", "ob_cheese", "k"),
        LetterWord('L', "lion", "wo_lion", "l"),
        LetterWord('M', "moon", "ob_moon", "m"),
        LetterWord('N', "nest", "wo_nest", "n"),
        LetterWord('O', "orange", "ob_orange", "o"),
        LetterWord('P', "mushroom", "ob_mushroom", "p"),
        LetterWord('Q', "jellyfish", "wo_jellyfish", "k"),
        LetterWord('R', "rocket", "ob_rocket", "r"),
        LetterWord('S', "sun", "ob_sun", "s"),
        LetterWord('T', "tomato", "ob_tomato", "t"),
        LetterWord('U', "clock", "wo_clock", "u"),
        LetterWord('V', "bird", "wo_bird", "f"), // Vogel: a V that sounds like F
        LetterWord('W', "cloud", "ob_cloud", "w"),
        LetterWord('X', "xylophone", "wo_xylophone", "ks"),
        LetterWord('Y', "yak", "wo_yak", "j"),   // Yak: a Y that sounds like J
        LetterWord('Z', "lemon", "ob_lemon", "ts"),
    )

    fun of(lang: Lang): List<LetterWord> = if (lang == Lang.DE) de else en

    fun word(lang: Lang, letter: Char): LetterWord = of(lang).first { it.letter == letter.uppercaseChar() }

    /** Letters whose name and first sound don't match well enough to ask "which one starts with…". */
    private val noFirstSound = mapOf(Lang.EN to setOf('X'), Lang.DE to setOf('C', 'Q', 'V', 'X', 'Y'))

    /** Letters the first-sound game asks about (each has a `firstsound_q_<x>` line in that language). */
    fun firstSoundLetters(lang: Lang): List<LetterWord> = of(lang).filter { it.letter !in noFirstSound.getValue(lang) }

    /** Sounds children mix up; from level 3 one of these sits next to the answer. */
    private val confusable = mapOf(
        "b" to listOf("d", "p"), "d" to listOf("b", "t"), "p" to listOf("b", "t"), "m" to listOf("n"), "n" to listOf("m"),
        "f" to listOf("v", "s"), "v" to listOf("f", "w"), "s" to listOf("z", "f"), "t" to listOf("d", "p"),
        "k" to listOf("g"), "g" to listOf("k"), "e" to listOf("a", "i"), "a" to listOf("e", "o"), "o" to listOf("u", "a"),
        "u" to listOf("o"), "w" to listOf("v"), "l" to listOf("r"), "r" to listOf("l", "w"),
    )

    /**
     * One "which one starts with…" question: the answer and [choices] − 1 things whose first sound
     * differs from it (and from each other), shuffled. From [level] 3 a similar sound joins when one exists.
     */
    fun firstSoundRound(lang: Lang, target: LetterWord, choices: Int, level: Int, r: Random): List<LetterWord> {
        val pool = of(lang).filter { it.sound != target.sound }.shuffled(r)
        val picked = ArrayList<LetterWord>()
        if (level >= 3) {
            val near = confusable[target.sound].orEmpty()
            pool.firstOrNull { it.sound in near }?.let(picked::add)
        }
        for (w in pool) {
            if (picked.size >= choices - 1) break
            if (picked.none { it.sound == w.sound }) picked += w
        }
        return (picked + target).shuffled(r)
    }

    /** Targets for a whole game: [n] different letters, avoiding [recent] ones when possible. */
    fun firstSoundTargets(lang: Lang, n: Int, r: Random, recent: Set<Char> = emptySet()): List<LetterWord> {
        val all = firstSoundLetters(lang).shuffled(r)
        val fresh = all.filter { it.letter !in recent }
        return (fresh + all.filter { it.letter in recent }).take(n)
    }
}

/** Waits until the activity's spoken intro (or whatever is playing) has finished, so a prompt never cuts it off. */
internal suspend fun dev.framio.meadow.play.PlayScope.afterSpeech(minMs: Long = 500) {
    kotlinx.coroutines.delay(minMs)
    while (app.voice.speaking.value) kotlinx.coroutines.delay(100)
}
