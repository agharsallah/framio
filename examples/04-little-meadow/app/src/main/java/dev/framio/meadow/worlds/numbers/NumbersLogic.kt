package dev.framio.meadow.worlds.numbers

import kotlin.math.abs
import kotlin.random.Random

// Pure round logic for Pip's Nest (no Android imports: unit-tested on the JVM).

// ---------------------------------------------------------------- How many?

/** A little picture to count in: what is in it, where it is, and the question Pip asks. */
internal enum class HowManyScene(val thing: String, val ask: String) {
    Pond("duck", "howmany_ask_duck"),
    Branch("apple", "howmany_ask_apple"),
    Night("star", "howmany_ask_star"),
    Meadow("flower", "howmany_ask_flower"),
    Sky("butterfly", "howmany_ask_butterfly"),
    Forest("mushroom", "howmany_ask_mushroom"),
}

internal data class HowManyRound(val scene: HowManyScene, val n: Int, val choices: List<Int>, val dots: Boolean)

/** How many things at each level: 1–3 for the youngest, up to 10 at the top. */
internal fun howManyRange(level: Int): IntRange = when (level) {
    0 -> 1..3
    1 -> 1..5
    2 -> 2..6
    3 -> 3..8
    else -> 4..10
}

/** Dot patterns help children who can't read numerals yet; at the top level only the numeral is shown. */
internal fun howManyDots(level: Int): Boolean = level < 4

/**
 * Three answer cards: the right number and two others from the level's range, close to it as the
 * child gets better (at level 0 the range is exactly 1..3, so the cards are always 1, 2, 3).
 */
internal fun howManyChoices(n: Int, level: Int, r: Random): List<Int> {
    val range = howManyRange(level)
    val lo = minOf(range.first, 1).coerceAtLeast(1)
    val hi = maxOf(range.last, 3)
    val pool = (lo..hi).filter { it != n }
    val spread = if (level >= 3) 2 else 9
    val near = pool.filter { abs(it - n) <= spread }.shuffled(r)
    val picked = (near + pool.shuffled(r)).distinct().take(2)
    return (picked + n).shuffled(r)
}

internal fun howManyRound(level: Int, r: Random, previous: HowManyScene? = null): HowManyRound {
    val scene = HowManyScene.entries.filter { it != previous }.random(r)
    val n = howManyRange(level).random(r)
    return HowManyRound(scene, n, howManyChoices(n, level, r), howManyDots(level))
}

/**
 * Dots for [n] in a unit square, as (x, y) centres: dice faces for 1–6 (seen at a glance), a
 * ten-frame (two rows of five, top row first) for 7–10.
 */
internal fun dotPattern(n: Int): List<Pair<Float, Float>> {
    val a = 0.2f
    val m = 0.5f
    val b = 0.8f
    return when (n) {
        1 -> listOf(m to m)
        2 -> listOf(a to a, b to b)
        3 -> listOf(a to a, m to m, b to b)
        4 -> listOf(a to a, b to a, a to b, b to b)
        5 -> listOf(a to a, b to a, m to m, a to b, b to b)
        6 -> listOf(a to a, b to a, a to m, b to m, a to b, b to b)
        else -> tenFrameSlots().take(n.coerceIn(0, 10))
    }
}

/** The ten slots of a ten-frame, in filling order. */
internal fun tenFrameSlots(): List<Pair<Float, Float>> =
    (0 until 10).map { i -> (0.1f + 0.2f * (i % 5)) to (if (i < 5) 0.3f else 0.7f) }

/**
 * Where [n] things sit inside a scene, as fractions of the picture: one row up to five, two rows
 * above that (the second row a little lower and offset like real things). Night and sky scatter
 * more freely. Spots never overlap: neighbours are at least a slot apart.
 */
internal fun sceneSpots(scene: HowManyScene, n: Int, seed: Int): List<Pair<Float, Float>> {
    val r = Random(seed)
    val rows = if (n <= 5) listOf(n) else listOf((n + 1) / 2, n / 2)
    val ys = when (scene) {
        HowManyScene.Branch -> if (rows.size == 1) listOf(0.5f) else listOf(0.33f, 0.8f)
        HowManyScene.Night, HowManyScene.Sky -> if (rows.size == 1) listOf(0.5f) else listOf(0.3f, 0.72f)
        HowManyScene.Pond -> if (rows.size == 1) listOf(0.58f) else listOf(0.42f, 0.76f)
        else -> if (rows.size == 1) listOf(0.66f) else listOf(0.5f, 0.82f)
    }
    val free = scene == HowManyScene.Night || scene == HowManyScene.Sky
    val out = ArrayList<Pair<Float, Float>>(n)
    rows.forEachIndexed { row, count ->
        val left = if (row == 1 && rows[0] != count) 0.2f else 0.13f
        val right = if (row == 1 && rows[0] != count) 0.8f else 0.87f
        val step = if (count > 1) (right - left) / (count - 1) else 0f
        for (i in 0 until count) {
            val x = if (count == 1) 0.5f else left + step * i
            val jx = if (scene == HowManyScene.Branch) 0f else (r.nextFloat() - 0.5f) * minOf(step, 0.2f) * 0.3f
            val jy = (r.nextFloat() - 0.5f) * (if (free) 0.12f else 0.04f)
            out += (x + jx) to (ys[row] + jy)
        }
    }
    return out
}

// ---------------------------------------------------------------- Feed Pip

/** How many berries Pip asks for. */
internal fun feedRange(level: Int): IntRange = when (level) {
    0 -> 1..3
    1 -> 2..4
    2 -> 3..6
    3 -> 4..8
    else -> 5..10
}

internal const val BUSH_BERRIES = 10

internal enum class FeedStatus { Under, Exact, TooMany }

internal fun feedStatus(inNest: Int, want: Int): FeedStatus = when {
    inNest < want -> FeedStatus.Under
    inNest == want -> FeedStatus.Exact
    else -> FeedStatus.TooMany
}

/** Ten berry spots on the bush (fractions of the bush sprite), rows of 3, 4 and 3, slightly hand-placed. */
internal fun bushSpots(): List<Pair<Float, Float>> = listOf(
    0.30f to 0.25f, 0.52f to 0.18f, 0.73f to 0.27f,
    0.16f to 0.49f, 0.39f to 0.45f, 0.62f to 0.47f, 0.85f to 0.52f,
    0.27f to 0.72f, 0.51f to 0.71f, 0.75f to 0.74f,
)

/** Spots for berries in the nest (fractions of the nest box): a front row of five, a back row of five. */
internal fun nestSpot(i: Int): Pair<Float, Float> {
    val row = i / 5
    val col = i % 5
    val x = 0.24f + col * 0.13f + (if (row == 1) 0.065f else 0f)
    val y = if (row == 0) 0.26f else 0.16f
    return x to y
}

// ---------------------------------------------------------------- More or fewer

internal val MORE_THINGS = listOf("apple", "strawberry", "orange", "egg", "cookie", "pear")

internal data class MoreRound(val thing: String, val left: Int, val right: Int, val askMore: Boolean) {
    /** 0 = the left basket is the answer, 1 = the right one. */
    val answer: Int get() = if ((left > right) == askMore) 0 else 1
}

/** The smallest difference between the baskets at each level: big and obvious first, one apart at the top. */
internal fun moreMinGap(level: Int): Int = when (level) {
    0 -> 3
    1 -> 2
    2 -> 2
    else -> 1
}

/** "More" for the younger levels, "fewer" (the harder word) from level 3. */
internal fun moreAsksMore(level: Int, r: Random): Boolean = if (level < 3) true else r.nextInt(3) == 0

internal fun moreRound(level: Int, r: Random, previousThing: String? = null): MoreRound {
    val maxN = if (level <= 1) 6 else if (level == 2) 8 else 10
    val gap = moreMinGap(level)
    val maxGap = if (level >= 3) 2 else if (level == 2) 3 else 5
    val small = r.nextInt(1, maxN - gap + 1)
    val big = (small + gap + r.nextInt(0, maxGap - gap + 1)).coerceAtMost(maxN)
    val leftBig = r.nextBoolean()
    val thing = MORE_THINGS.filter { it != previousThing }.random(r)
    return MoreRound(thing, if (leftBig) big else small, if (leftBig) small else big, moreAsksMore(level, r))
}

/**
 * [n] things in a basket (fractions of the basket picture): two neat rows, the front one sitting in
 * the basket and the back one just above it, so every one stays visible and countable. Up to six
 * go in rows of three (big things), seven to ten in rows of five (smaller things, see [pileItemSize]).
 */
internal fun pileSpots(n: Int, big: Boolean = n <= 6): List<Pair<Float, Float>> {
    val perRow = if (big) 3 else 5
    val step = if (big) 0.24f else 0.175f
    val ys = if (big) listOf(0.2f, -0.02f) else listOf(0.2f, 0.03f)
    val out = ArrayList<Pair<Float, Float>>(n)
    val rows = listOf(minOf(perRow, n), (n - perRow).coerceAtLeast(0))
    rows.forEachIndexed { row, c ->
        val x0 = 0.5f - step * (c - 1) / 2
        for (i in 0 until c) out += (x0 + step * i) to ys[row]
    }
    return out
}

/** Size of the things in a basket, in dp: both baskets use the bigger count's size, so size never hints at "more". */
internal fun pileItemSize(big: Boolean): Int = if (big) 84 else 64
