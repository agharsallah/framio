package dev.framio.meadow.worlds.puzzles

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

// Pure round logic for the Puzzle Path: no Android or Compose here, so it is unit-tested on the JVM.

/** A point in dp, used by the layout helpers. */
internal data class Pt(val x: Float, val y: Float)

// ---------------------------------------------------------------- memory

/** Things on the memory cards (sprites ob_<name>, spoken name_<name>): distinct shapes and colours. */
internal val MEMORY_THINGS = listOf(
    "apple", "duck", "star", "ball", "flower", "fish", "teddy", "car", "boat", "cupcake",
    "butterfly", "snail", "sun", "heart", "strawberry", "umbrella", "kite", "rocket",
)

/** Columns × rows of cards: 4 cards at level 0, then 6, 8 and 12 (level 3+). */
internal fun memoryGrid(level: Int): Pair<Int, Int> = when (level) {
    0 -> 2 to 2
    1 -> 3 to 2
    2 -> 4 to 2
    else -> 4 to 3
}

/** The cards of one board, shuffled: every thing appears exactly twice. */
internal fun memoryDeal(level: Int, random: Random): List<String> {
    val (cols, rows) = memoryGrid(level)
    val things = MEMORY_THINGS.shuffled(random).take(cols * rows / 2)
    return (things + things).shuffled(random)
}

/** Card size for a grid: as big as fits (≥ 150 dp), the gap scales with it. */
internal fun memoryCardSize(cols: Int, rows: Int, areaW: Float, areaH: Float): Float {
    val byW = (areaW - 40f) / (cols + (cols - 1) * 0.13f)
    val byH = (areaH - 40f) / (rows + (rows - 1) * 0.13f)
    return min(min(byW, byH), 230f)
}

// ---------------------------------------------------------------- jigsaw

/** The jigsaw pictures (sprites pz_pic_<id>, spoken jigsaw_pic_<id>). */
internal val JIGSAW_PICTURES = listOf("nest", "paint", "balloons", "garden", "moon")

/** Columns × rows of pieces: 2 at level 0, then 4, 6 and 9. */
internal fun jigsawGrid(level: Int): Pair<Int, Int> = when (level) {
    0 -> 2 to 1
    1 -> 2 to 2
    2 -> 3 to 2
    else -> 3 to 3
}

/** The bump on each side of a piece: 0 = a flat border, 1 = a knob sticking out, -1 = a hole. */
internal data class PieceEdges(val top: Int, val right: Int, val bottom: Int, val left: Int)

/**
 * Edges for every piece, row by row. Shared edges always fit: a knob on one side is a hole on the
 * other, and the outside of the picture is flat.
 */
internal fun jigsawEdges(cols: Int, rows: Int, random: Random): List<PieceEdges> {
    val right = Array(cols) { IntArray(rows) { if (random.nextBoolean()) 1 else -1 } }  // right side of (c, r)
    val bottom = Array(cols) { IntArray(rows) { if (random.nextBoolean()) 1 else -1 } } // bottom side of (c, r)
    return List(cols * rows) { i ->
        val c = i % cols
        val r = i / cols
        PieceEdges(
            top = if (r == 0) 0 else -bottom[c][r - 1],
            right = if (c == cols - 1) 0 else right[c][r],
            bottom = if (r == rows - 1) 0 else bottom[c][r],
            left = if (c == 0) 0 else -right[c - 1][r],
        )
    }
}

/** How far knobs reach beyond a cell, as a fraction of the cell's shorter side. */
internal const val TAB = 0.3f

/** Path pieces: absolute coordinates inside the piece's box (cell + margin on every side). */
internal sealed interface Seg {
    data class Move(val x: Float, val y: Float) : Seg
    data class Line(val x: Float, val y: Float) : Seg
    data class Cubic(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : Seg
}

// one edge as (t along, k outwards) in units of the cell's shorter side, around the edge's middle
private val KNOB = listOf(
    floatArrayOf(-0.16f, 0f),
    floatArrayOf(-0.10f, 0f, -0.08f, 0.06f, -0.12f, 0.12f),
    floatArrayOf(-0.18f, 0.22f, -0.10f, 0.30f, 0f, 0.30f),
    floatArrayOf(0.10f, 0.30f, 0.18f, 0.22f, 0.12f, 0.12f),
    floatArrayOf(0.08f, 0.06f, 0.10f, 0f, 0.16f, 0f),
)

/** Margin around a cell inside its piece box: room for the knobs. */
internal fun pieceMargin(cellW: Float, cellH: Float) = min(cellW, cellH) * (TAB + 0.02f)

/**
 * The outline of a piece of [cellW]×[cellH], clockwise from the top-left corner, in a box of
 * (cellW + 2m)×(cellH + 2m) where m = [pieceMargin]. Knobs are the same size on every edge.
 */
internal fun pieceOutline(e: PieceEdges, cellW: Float, cellH: Float): List<Seg> {
    val u = min(cellW, cellH)
    val m = pieceMargin(cellW, cellH)
    val out = ArrayList<Seg>()
    val corners = listOf(Pt(m, m), Pt(m + cellW, m), Pt(m + cellW, m + cellH), Pt(m, m + cellH))
    // direction along each edge and its outward normal (clockwise, y down)
    val dirs = listOf(Pt(1f, 0f), Pt(0f, 1f), Pt(-1f, 0f), Pt(0f, -1f))
    val normals = listOf(Pt(0f, -1f), Pt(1f, 0f), Pt(0f, 1f), Pt(-1f, 0f))
    val bumps = listOf(e.top, e.right, e.bottom, e.left)
    out += Seg.Move(corners[0].x, corners[0].y)
    for (side in 0 until 4) {
        val a = corners[side]
        val b = corners[(side + 1) % 4]
        val s = bumps[side]
        if (s != 0) {
            val d = dirs[side]
            val n = normals[side]
            val mid = Pt((a.x + b.x) / 2, (a.y + b.y) / 2)
            fun at(t: Float, k: Float) = Pt(mid.x + d.x * t * u + n.x * k * u * s, mid.y + d.y * t * u + n.y * k * u * s)
            val first = at(KNOB[0][0], KNOB[0][1])
            out += Seg.Line(first.x, first.y)
            for (c in KNOB.drop(1)) {
                val p1 = at(c[0], c[1])
                val p2 = at(c[2], c[3])
                val p3 = at(c[4], c[5])
                out += Seg.Cubic(p1.x, p1.y, p2.x, p2.y, p3.x, p3.y)
            }
        }
        out += Seg.Line(b.x, b.y)
    }
    return out
}

/** Board and loose-piece places for the jigsaw, in dp inside the content area. */
internal data class JigsawLayout(
    val boardX: Float,
    val boardY: Float,
    val boardW: Float,
    val boardH: Float,
    val cols: Int,
    val rows: Int,
    /** Where loose pieces wait (their centres), one per piece. */
    val spots: List<Pt>,
    /** Scale of a waiting piece (1 = its size on the board). */
    val looseScale: Float,
) {
    val cellW get() = boardW / cols
    val cellH get() = boardH / rows
    val margin get() = pieceMargin(cellW, cellH)

    /** Centre of piece [i]'s slot on the board. */
    fun slotCenter(i: Int) = Pt(boardX + cellW * (i % cols + 0.5f), boardY + cellH * (i / cols + 0.5f))

    /** The slot under [p] (on the board), or -1. */
    fun slotAt(p: Pt): Int {
        if (p.x < boardX || p.y < boardY || p.x >= boardX + boardW || p.y >= boardY + boardH) return -1
        val c = ((p.x - boardX) / cellW).toInt().coerceIn(0, cols - 1)
        val r = ((p.y - boardY) / cellH).toInt().coerceIn(0, rows - 1)
        return r * cols + c
    }

    /** True when a piece dropped with its centre at [p] is close enough to snap into slot [i]. */
    fun snaps(i: Int, p: Pt): Boolean {
        val c = slotCenter(i)
        val dx = p.x - c.x
        val dy = p.y - c.y
        val r = min(cellW, cellH) * 0.42f
        return dx * dx + dy * dy <= r * r
    }
}

/**
 * The board in the middle (600×420 dp, the pictures' 800×560 shape) and waiting places around it:
 * left (above the friend), right, and along the bottom. Pure, unit-tested.
 */
internal fun jigsawLayout(cols: Int, rows: Int): JigsawLayout {
    val bw = 600f
    val bh = 420f
    val bx = 340f
    val by = 26f
    val n = cols * rows
    val spots = when (n) {
        2 -> listOf(Pt(172f, 228f), Pt(1108f, 300f))
        4 -> listOf(Pt(172f, 124f), Pt(172f, 338f), Pt(1108f, 150f), Pt(1108f, 420f))
        6 -> listOf(Pt(172f, 124f), Pt(172f, 344f), Pt(1108f, 120f), Pt(1108f, 345f), Pt(1108f, 572f), Pt(600f, 574f))
        else -> listOf(
            Pt(172f, 118f), Pt(172f, 330f), Pt(1108f, 106f), Pt(1108f, 280f), Pt(1108f, 454f),
            Pt(418f, 584f), Pt(630f, 584f), Pt(842f, 584f), Pt(1090f, 600f),
        )
    }
    val cw = bw / cols
    val ch = bh / rows
    val m = pieceMargin(cw, ch)
    val fit = when (n) {
        2 -> Pt(300f, 400f)
        4 -> Pt(290f, 230f)
        6 -> Pt(230f, 215f)
        else -> Pt(206f, 168f)
    }
    val scale = min(1f, min(fit.x / (cw + 2 * m), fit.y / (ch + 2 * m)))
    return JigsawLayout(bx, by, bw, bh, cols, rows, spots, scale)
}

// ---------------------------------------------------------------- shadows

/** Groups of things whose shadows look alike: harder rounds take several from one group. */
internal val SHADOW_GROUPS = listOf(
    listOf("apple", "orange", "tomato"),
    listOf("pear", "lemon", "egg"),
    listOf("duck", "fish", "snail"),
    listOf("car", "boat", "rocket"),
    listOf("star", "flower", "sun"),
    listOf("mushroom", "umbrella", "tree"),
    listOf("cherry", "grapes", "strawberry"),
    listOf("teddy", "bell", "hat"),
    listOf("cup", "milk", "drum"),
    listOf("butterfly", "heart", "kite"),
)

/** How many shadows in a round: 3, then 4 from level 2. */
internal fun shadowCount(level: Int) = if (level < 2) 3 else 4

/**
 * The things of one round. Level 0–1: each from a different group (very different outlines);
 * level 2: one look-alike pair; level 3: three look-alikes; level 4: two look-alike pairs.
 */
internal fun shadowRound(level: Int, random: Random): List<String> {
    val n = shadowCount(level)
    val groups = SHADOW_GROUPS.shuffled(random)
    val takes = when {
        level <= 1 -> List(n) { 1 }
        level == 2 -> listOf(2, 1, 1)
        level == 3 -> listOf(3, 1)
        else -> listOf(2, 2)
    }
    val out = ArrayList<String>()
    takes.forEachIndexed { gi, k -> out += groups[gi].shuffled(random).take(k) }
    return out.take(n).shuffled(random)
}

/** The order the things wait in below the shadows: never the same order as the shadows. */
internal fun shadowTrayOrder(n: Int, random: Random): List<Int> {
    if (n < 2) return List(n) { it }
    while (true) {
        val o = (0 until n).shuffled(random)
        if (o.indices.none { o[it] == it }) return o
    }
}

// ---------------------------------------------------------------- patterns

/** Pattern rules, from easiest. */
internal enum class PatternKind(val unit: String) { AB("AB"), AAB("AAB"), ABC("ABC"), ABB("ABB"), AABB("AABB") }

internal fun patternKind(level: Int): PatternKind = PatternKind.entries[level.coerceIn(0, PatternKind.entries.size - 1)]

/** Things the train carries: easy to tell apart and to name. */
internal val PATTERN_THINGS = listOf("apple", "star", "duck", "ball", "flower", "heart", "fish", "leaf", "carrot", "strawberry", "moon", "car")

internal data class PatternRound(
    /** What the wagons carry, the last one being the question. */
    val cars: List<String>,
    val answer: String,
    /** Three choices: the answer and two others, shuffled. */
    val choices: List<String>,
    val kind: PatternKind,
)

/**
 * One train: the pattern shown at least one and a half times (5 to 7 wagons; the hidden last one
 * can be any place in the unit). Choices are the answer, the pattern's other things, and for
 * two-thing patterns a stranger.
 */
internal fun patternRound(level: Int, random: Random): PatternRound {
    val kind = patternKind(level)
    val letters = kind.unit.toSet().sorted()
    val things = PATTERN_THINGS.shuffled(random)
    val map = letters.withIndex().associate { (i, c) -> c to things[i] }
    val unit = kind.unit.length
    val minLen = min(unit * 2 + 1, 6)
    val maxLen = min(7, unit * 3)
    val len = if (maxLen > minLen) minLen + random.nextInt(maxLen - minLen + 1) else minLen
    val seq = List(len) { map.getValue(kind.unit[it % unit]) }
    val answer = seq.last()
    val inPattern = map.values.filter { it != answer }.shuffled(random)
    val stranger = things[letters.size]
    val distractors = (inPattern + stranger).take(2)
    return PatternRound(seq, answer, (distractors + answer).shuffled(random), kind)
}

// ---------------------------------------------------------------- 3D shapes

internal enum class Solid(val id: String, val example: String) {
    Sphere("sphere", "ob_ball"),
    Cube("cube", "ob_block"),
    Cylinder("cylinder", "ob_cup"),
    Cone("cone", "ob_icecream"),
    Pyramid("pyramid", "pz_ex_tent"),
    Torus("torus", "pz_ex_donut"),
}

/** The order shapes are met in: round ones first, the donut last. */
internal fun solidOrder(random: Random): List<Solid> {
    val first = listOf(Solid.Sphere, Solid.Cube, Solid.Cylinder).shuffled(random)
    val then = listOf(Solid.Cone, Solid.Pyramid, Solid.Torus).shuffled(random)
    return first + then
}

/** Shapes that are easy to mix up: harder quizzes put them next to each other. */
private val LOOKALIKE = mapOf(
    Solid.Sphere to listOf(Solid.Torus, Solid.Cylinder),
    Solid.Cube to listOf(Solid.Cylinder, Solid.Pyramid),
    Solid.Cylinder to listOf(Solid.Cube, Solid.Cone),
    Solid.Cone to listOf(Solid.Pyramid, Solid.Cylinder),
    Solid.Pyramid to listOf(Solid.Cone, Solid.Cube),
    Solid.Torus to listOf(Solid.Sphere, Solid.Cylinder),
)

/** Three cards for "Which one is the [target]?": the target, plus look-alikes from level 3. */
internal fun solidChoices(target: Solid, level: Int, random: Random): List<Solid> {
    val others = if (level >= 3) LOOKALIKE.getValue(target) else Solid.entries.filter { it != target && it !in LOOKALIKE.getValue(target) }.shuffled(random).take(2)
    return (others + target).shuffled(random)
}

/** Shapes per play: 3 for the youngest, then 4. */
internal fun solidRounds(level: Int) = max(3, min(4, 3 + level))
