package dev.framio.meadow.worlds.colors

import kotlin.random.Random

/*
 * The pure rules of Tuka's Studio: which colours and shapes each level teaches, how a round is
 * built and what two paints make. No Android here, so all of it is unit-tested on the JVM.
 */

/** The ten colours children learn to name, in teaching order (matches Palette and the sprites). */
enum class Hue(val id: String, val argb: Long) {
    Red("red", 0xFFDE6A5E),
    Yellow("yellow", 0xFFF1CD5E),
    Blue("blue", 0xFF6E9FD6),
    Green("green", 0xFF7DBA70),
    Orange("orange", 0xFFEE9B55),
    Purple("purple", 0xFFA487C9),
    Pink("pink", 0xFFEFA6C0),
    Brown("brown", 0xFFA9785A),
    White("white", 0xFFF8F4EC),
    Black("black", 0xFF45404A);

    /** The spoken colour name ("Blue!"). */
    val line get() = "colors_$id"
}

// ---------------------------------------------------------------- Color hunt

/** Colours in play: red, yellow, blue, green first, then orange, purple, pink, then brown, white, black. */
internal fun huntPool(level: Int): List<Hue> = Hue.entries.take(
    when {
        level <= 1 -> 4
        level == 2 -> 7
        else -> 10
    },
)

/** How many things to choose from. */
internal fun huntChoices(level: Int): Int = when {
    level <= 0 -> 2
    level <= 2 -> 3
    else -> 4
}

/** Balloons only for the youngest; from level 2 any coloured thing ("find something blue"). */
internal fun huntThings(level: Int): List<String> = if (level < 2) listOf("balloon") else listOf("balloon", "car", "sock", "cup")

data class HuntItem(val hue: Hue, val thing: String) {
    val sprite get() = if (thing == "balloon") "ob_balloon_${hue.id}" else "co_${thing}_${hue.id}"
}

data class HuntRound(val options: List<HuntItem>, val target: HuntItem) {
    /** Balloons only: "Find the blue balloon!"; mixed things: "Find something blue!". */
    val promptLine get() = if (options.all { it.thing == "balloon" }) "colorhunt_find_${target.hue.id}" else "colorhunt_one_${target.hue.id}"
}

/**
 * One round: [huntChoices] things in different colours, one of them the colour to find. The colour
 * asked for changes every round ([previous] is never asked twice in a row), and newly added colours
 * come up a little more often so they get practised.
 */
internal fun huntRound(level: Int, random: Random, previous: Hue? = null): HuntRound {
    val pool = huntPool(level)
    val n = huntChoices(level).coerceAtMost(pool.size)
    val candidates = pool.filter { it != previous }
    val fresh = if (level >= 2) pool.drop(4).filter { it != previous } else emptyList()
    val target = if (fresh.isNotEmpty() && random.nextInt(3) == 0) fresh.random(random) else candidates.random(random)
    val others = (pool - target).shuffled(random).take(n - 1)
    val hues = (others + target).shuffled(random)
    val things = huntThings(level)
    val items = hues.map { HuntItem(it, things.random(random)) }
    return HuntRound(items, items.first { it.hue == target })
}

// ---------------------------------------------------------------- Shape sorter

enum class SortShape(val id: String) {
    Circle("circle"), Square("square"), Triangle("triangle"), Star("star"), Heart("heart"),
    Rect("rect"), Oval("oval"), Diamond("diamond"), Hexagon("hexagon");

    val hole get() = "co_hole_$id"
    fun sprite(color: String) = "co_shape_${id}_$color"
    /** "Star!" */
    val line get() = "colors_shape_$id"
}

/** The colours the clay shapes come in (co_shape_<shape>_<color>). */
internal val SHAPE_COLORS = listOf("red", "yellow", "blue", "green", "purple")

internal fun sorterPool(level: Int): List<SortShape> = SortShape.entries.take(
    when {
        level <= 1 -> 5
        level <= 3 -> 8
        else -> 9
    },
)

/** Shapes per round: 3 for the youngest up to 6. */
internal fun sorterCount(level: Int): Int = when (level) {
    0 -> 3
    1, 2 -> 4
    3 -> 5
    else -> 6
}

data class Piece(val shape: SortShape, val color: String) {
    val sprite get() = shape.sprite(color)
}

/** The shapes waiting above the box (left to right) and the holes on its lid (left to right). */
data class SorterRound(val pieces: List<Piece>, val holes: List<SortShape>)

/**
 * Distinct shapes in distinct colours where possible. The holes are in a different order from the
 * pieces so a child has to look, not just drop straight down.
 */
internal fun sorterRound(level: Int, random: Random): SorterRound {
    val n = sorterCount(level)
    val shapes = sorterPool(level).shuffled(random).take(n)
    val colors = SHAPE_COLORS.shuffled(random)
    val pieces = shapes.mapIndexed { i, s -> Piece(s, colors[i % colors.size]) }
    var holes = shapes.shuffled(random)
    var guard = 0
    while (n > 1 && holes.zip(shapes).any { (h, s) -> h == s } && guard++ < 50) holes = shapes.shuffled(random)
    return SorterRound(pieces, holes)
}

// ---------------------------------------------------------------- Mixing colors

enum class Pot(val id: String, val argb: Long) {
    Red("red", Hue.Red.argb), Yellow("yellow", Hue.Yellow.argb), Blue("blue", Hue.Blue.argb),
    White("white", Hue.White.argb), Black("black", Hue.Black.argb);

    val sprite get() = "co_pot_$id"
    val line get() = "colors_$id"
}

/** Red, yellow, blue and white; black joins at the top level. */
internal fun mixPots(level: Int): List<Pot> = if (level >= 4) Pot.entries else Pot.entries - Pot.Black

/** What came out of the bowl: its colour and the whole sentence ("Red and yellow make orange!"). */
data class Mix(val a: Pot, val b: Pot, val name: String, val argb: Long) {
    val line get() = "mix_${a.id}_${b.id}"
    val key get() = "${a.id}+${b.id}"
}

private val MIXES: Map<Pair<Pot, Pot>, Pair<String, Long>> = mapOf(
    (Pot.Red to Pot.Yellow) to ("orange" to Hue.Orange.argb),
    (Pot.Red to Pot.Blue) to ("purple" to Hue.Purple.argb),
    (Pot.Yellow to Pot.Blue) to ("green" to Hue.Green.argb),
    (Pot.Red to Pot.White) to ("pink" to Hue.Pink.argb),
    (Pot.Yellow to Pot.White) to ("lightyellow" to 0xFFF7E6A6),
    (Pot.Blue to Pot.White) to ("lightblue" to 0xFFB1CEEC),
    (Pot.White to Pot.Black) to ("grey" to 0xFFA9A6AD),
    (Pot.Red to Pot.Black) to ("darkred" to 0xFF9C4E4C),
    (Pot.Yellow to Pot.Black) to ("olive" to 0xFF9E9653),
    (Pot.Blue to Pot.Black) to ("darkblue" to 0xFF506C98),
)

/** Any two different pots make something: there are no wrong mixes, only new colours. Order doesn't matter. */
internal fun mixOf(x: Pot, y: Pot): Mix {
    require(x != y) { "two different pots" }
    val (a, b) = if (x.ordinal < y.ordinal) x to y else y to x
    val (name, argb) = MIXES.getValue(a to b)
    return Mix(a, b, name, argb)
}

/** Discoveries needed to finish. */
internal const val MIX_GOAL = 4

/** Linear blend of two ARGB colours, t = 0 → [a], 1 → [b] (for the swirl). */
internal fun blendArgb(a: Long, b: Long, t: Float): Long {
    fun ch(c: Long, s: Int) = ((c shr s) and 0xFF).toInt()
    val k = t.coerceIn(0f, 1f)
    var out = 0xFF000000
    for (s in intArrayOf(16, 8, 0)) {
        val v = Math.round(ch(a, s) + (ch(b, s) - ch(a, s)) * k).toLong()
        out = out or (v shl s)
    }
    return out
}

// ---------------------------------------------------------------- Finger paint & colouring

/** The paint palette: ten calm colours (the rainbow brush is extra). */
internal val PAINT_COLORS: List<Pair<String, Long>> = listOf(
    "red" to Hue.Red.argb, "orange" to Hue.Orange.argb, "yellow" to Hue.Yellow.argb, "green" to Hue.Green.argb,
    "lightblue" to 0xFFA9CBEA, "blue" to Hue.Blue.argb, "purple" to Hue.Purple.argb, "pink" to Hue.Pink.argb,
    "brown" to Hue.Brown.argb, "black" to Hue.Black.argb,
)

/** Colouring pages: white instead of black (outlines are already dark, and white "rubs out"). */
internal val COLORING_COLORS: List<Pair<String, Long>> = PAINT_COLORS.dropLast(1) + ("white" to 0xFFFFFDF8)

/** The rainbow brush: a soft hue that moves along the stroke, [dist] in pixels. */
internal fun rainbowArgb(dist: Float): Long {
    val stops = longArrayOf(Hue.Red.argb, Hue.Orange.argb, Hue.Yellow.argb, Hue.Green.argb, Hue.Blue.argb, Hue.Purple.argb, Hue.Pink.argb)
    val pos = (dist / 90f) % stops.size
    val i = pos.toInt()
    return blendArgb(stops[i], stops[(i + 1) % stops.size], pos - i)
}

/** The file name of a saved drawing, e.g. 20261018-153012.png (local time). */
internal fun drawingName(millis: Long): String {
    val f = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
    return f.format(java.util.Date(millis)) + ".png"
}
