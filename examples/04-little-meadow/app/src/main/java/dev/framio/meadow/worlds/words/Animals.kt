package dev.framio.meadow.worlds.words

import dev.framio.meadow.core.Lang
import kotlin.random.Random

/**
 * A farm friend. Sprites `wo_<id>_idle` / `wo_<id>_happy` (the duck is the shared ob_duck); lines
 * `animals_says_<id>` ("The cow says moo!") and `animals_who_<id>` ("Who says moo?"). [soundEn] and
 * [soundDe] say what it says in each language: in German the frog and the duck both say "quak", so
 * they never stand side by side in a question.
 */
data class Animal(val id: String, val soundEn: String, val soundDe: String) {
    fun sound(lang: Lang) = if (lang == Lang.DE) soundDe else soundEn
    val idle: String get() = if (id == "duck") "ob_duck" else "wo_${id}_idle"
    val happy: String get() = if (id == "duck") "ob_duck" else "wo_${id}_happy"
}

object Farm {
    val animals = listOf(
        Animal("cow", "moo", "muh"),
        Animal("pig", "oink", "grunz"),
        Animal("sheep", "baa", "maeh"),
        Animal("horse", "neigh", "wieher"),
        Animal("hen", "cluck", "gack"),
        Animal("dog", "woof", "wau"),
        Animal("cat", "meow", "miau"),
        Animal("frog", "ribbit", "quak"),
        Animal("mouse", "squeak", "piep"),
        Animal("duck", "quack", "quak"),
        Animal("bee", "buzz", "summ"),
    )

    /** How many friends wait in the meadow to be greeted at each level. */
    fun exploreCount(level: Int): Int = if (level <= 1) 4 else 5

    /** Choices in a "who says…?" question. */
    fun choiceCount(level: Int): Int = if (level == 0) 2 else 3

    /** The friends in the meadow: [n] different animals, all saying different things. */
    fun meadow(lang: Lang, n: Int, r: Random): List<Animal> {
        val out = ArrayList<Animal>()
        for (a in animals.shuffled(r)) if (out.size < n && out.none { it.sound(lang) == a.sound(lang) }) out += a
        return out
    }

    /** Question targets: the greeted friends first (they were just heard), then others. */
    fun quizTargets(met: List<Animal>, n: Int, r: Random): List<Animal> =
        (met.shuffled(r) + animals.filter { it !in met }.shuffled(r)).take(n)

    /** One question: the target and [choices] − 1 others that say something else, shuffled. */
    fun quizRound(lang: Lang, target: Animal, choices: Int, r: Random): List<Animal> {
        val others = ArrayList<Animal>()
        for (a in animals.shuffled(r)) {
            if (others.size >= choices - 1) break
            if (a.sound(lang) != target.sound(lang) && others.none { it.sound(lang) == a.sound(lang) }) others += a
        }
        return (others + target).shuffled(r)
    }

    /**
     * Where the friends stand, as fractions of the play area (centre x, feet y) and a size factor:
     * further back is a little smaller. Keeps clear of the friend's corner (bottom-left).
     */
    fun spots(n: Int): List<Triple<Float, Float, Float>> = when (n) {
        2 -> listOf(Triple(0.42f, 0.86f, 1.15f), Triple(0.72f, 0.86f, 1.15f))
        3 -> listOf(Triple(0.32f, 0.84f, 1.1f), Triple(0.57f, 0.88f, 1.15f), Triple(0.82f, 0.84f, 1.1f))
        4 -> listOf(Triple(0.30f, 0.6f, 0.9f), Triple(0.56f, 0.56f, 0.88f), Triple(0.80f, 0.62f, 0.92f), Triple(0.50f, 0.95f, 1.1f))
        else -> listOf(
            Triple(0.28f, 0.6f, 0.88f), Triple(0.50f, 0.55f, 0.85f), Triple(0.74f, 0.6f, 0.88f),
            Triple(0.40f, 0.96f, 1.05f), Triple(0.66f, 0.97f, 1.05f), Triple(0.88f, 0.92f, 1.0f),
        ).take(n)
    }
}
