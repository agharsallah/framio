package dev.framio.meadow.worlds.words

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** A point in a glyph's unit box: x right, y down, both 0..1. */
data class Pt(val x: Float, val y: Float)

/**
 * Something to trace: a pre-writing shape ("down", "circle"…) or an uppercase letter, as strokes in
 * writing order. Each stroke is a polyline in the unit box. Pure geometry, unit-tested.
 */
data class Glyph(val id: String, val strokes: List<List<Pt>>) {
    val isLetter: Boolean get() = id.length == 1
}

object Glyphs {
    private fun p(x: Number, y: Number) = Pt(x.toFloat(), y.toFloat())
    private fun line(vararg xy: Number): List<Pt> = xy.toList().chunked(2) { p(it[0], it[1]) }

    /** Points on an ellipse arc from [a0] to [a1] degrees (0 = right, 90 = down: screen angles). */
    private fun arc(cx: Float, cy: Float, rx: Float, ry: Float, a0: Float, a1: Float, steps: Int = 24): List<Pt> =
        (0..steps).map {
            val a = (a0 + (a1 - a0) * it / steps) * PI.toFloat() / 180f
            Pt(cx + rx * cos(a), cy + ry * sin(a))
        }

    private fun g(id: String, vararg strokes: List<Pt>) = Glyph(id, strokes.toList())

    /** Pre-writing shapes for the youngest: lines, a circle, zig-zag and waves. */
    val shapes: List<Glyph> = listOf(
        g("down", line(0.5, 0.1, 0.5, 0.9)),
        g("across", line(0.1, 0.5, 0.9, 0.5)),
        g("circle", arc(0.5f, 0.5f, 0.38f, 0.38f, -90f, -450f, 40)),
        g("zigzag", line(0.08, 0.66, 0.25, 0.34, 0.42, 0.66, 0.58, 0.34, 0.75, 0.66, 0.92, 0.34)),
        g("wave", (0..40).map { val t = it / 40f; p(0.08f + 0.84f * t, 0.5f - 0.15f * sin(t * 3f * PI.toFloat())) }),
    )

    private val o = arc(0.5f, 0.5f, 0.32f, 0.4f, -90f, -450f, 40)

    val letters: Map<Char, Glyph> = listOf(
        g("L", line(0.3, 0.1, 0.3, 0.9, 0.74, 0.9)),
        g("T", line(0.18, 0.1, 0.82, 0.1), line(0.5, 0.1, 0.5, 0.9)),
        g("I", line(0.5, 0.1, 0.5, 0.9), line(0.32, 0.1, 0.68, 0.1), line(0.32, 0.9, 0.68, 0.9)),
        g("O", o),
        g("C", arc(0.52f, 0.5f, 0.32f, 0.4f, -40f, -320f, 36)),
        g("U", line(0.25, 0.1, 0.25, 0.62) + arc(0.5f, 0.62f, 0.25f, 0.28f, 180f, 0f, 20).drop(1) + line(0.75, 0.62, 0.75, 0.1).drop(1)),
        g("V", line(0.2, 0.1, 0.5, 0.9, 0.8, 0.1)),
        g("H", line(0.25, 0.1, 0.25, 0.9), line(0.75, 0.1, 0.75, 0.9), line(0.25, 0.5, 0.75, 0.5)),
        g("A", line(0.5, 0.1, 0.2, 0.9), line(0.5, 0.1, 0.8, 0.9), line(0.33, 0.6, 0.67, 0.6)),
        g("M", line(0.15, 0.1, 0.15, 0.9), line(0.15, 0.1, 0.5, 0.62, 0.85, 0.1, 0.85, 0.9)),
        g("E", line(0.3, 0.1, 0.3, 0.9), line(0.3, 0.1, 0.74, 0.1), line(0.3, 0.5, 0.66, 0.5), line(0.3, 0.9, 0.74, 0.9)),
        g("F", line(0.3, 0.1, 0.3, 0.9), line(0.3, 0.1, 0.74, 0.1), line(0.3, 0.5, 0.66, 0.5)),
        g("S", arc(0.5f, 0.3f, 0.23f, 0.2f, -20f, -270f, 24) + arc(0.5f, 0.7f, 0.25f, 0.2f, -90f, 160f, 24).drop(1)),
        g("N", line(0.22, 0.1, 0.22, 0.9), line(0.22, 0.1, 0.78, 0.9, 0.78, 0.1)),
        g("W", line(0.1, 0.1, 0.3, 0.9, 0.5, 0.36, 0.7, 0.9, 0.9, 0.1)),
        g("Z", line(0.22, 0.1, 0.78, 0.1, 0.22, 0.9, 0.78, 0.9)),
        g("X", line(0.22, 0.1, 0.78, 0.9), line(0.78, 0.1, 0.22, 0.9)),
        g("K", line(0.27, 0.1, 0.27, 0.9), line(0.75, 0.1, 0.27, 0.55, 0.75, 0.9)),
        g("P", line(0.28, 0.1, 0.28, 0.9), line(0.28, 0.1, 0.48, 0.1) + arc(0.48f, 0.3f, 0.24f, 0.2f, -90f, 90f, 18).drop(1) + line(0.48, 0.5, 0.28, 0.5).drop(1)),
        g("R", line(0.28, 0.1, 0.28, 0.9), line(0.28, 0.1, 0.48, 0.1) + arc(0.48f, 0.3f, 0.24f, 0.2f, -90f, 90f, 18).drop(1) + line(0.48, 0.5, 0.28, 0.5).drop(1), line(0.46, 0.5, 0.76, 0.9)),
        g("D", line(0.25, 0.1, 0.25, 0.9), line(0.25, 0.1, 0.42, 0.1) + arc(0.42f, 0.5f, 0.34f, 0.4f, -90f, 90f, 28).drop(1) + line(0.42, 0.9, 0.25, 0.9).drop(1)),
        g("B", line(0.28, 0.1, 0.28, 0.9), line(0.28, 0.1, 0.48, 0.1) + arc(0.48f, 0.29f, 0.22f, 0.19f, -90f, 90f, 16).drop(1) + arc(0.5f, 0.69f, 0.25f, 0.21f, -90f, 90f, 16).drop(1) + line(0.5, 0.9, 0.28, 0.9).drop(1)),
        g("J", line(0.66, 0.1, 0.66, 0.66) + arc(0.46f, 0.66f, 0.2f, 0.24f, 0f, 180f, 18).drop(1)),
        g("G", arc(0.52f, 0.5f, 0.32f, 0.4f, -40f, -360f, 40) + line(0.84, 0.5, 0.58, 0.5).drop(1)),
        g("Q", o, line(0.58, 0.68, 0.84, 0.94)),
        g("Y", line(0.2, 0.1, 0.5, 0.5), line(0.8, 0.1, 0.5, 0.5, 0.5, 0.9)),
    ).associateBy { it.id[0] }

    /** Easiest first: straight lines and one curve, then more strokes, then the trickiest. */
    val tier1 = "LTIOCUVH".toList()
    val tier2 = "AMEFSNWZXK".toList()
    val tier3 = "PRDBJGQY".toList()

    fun letter(c: Char): Glyph = letters.getValue(c)

    /**
     * What to trace in a game of [n] at [level]: the five pre-writing shapes up to level 1, simple
     * letters at 2, more strokes at 3, any letter at 4.
     */
    fun round(level: Int, n: Int, r: Random): List<Glyph> = when {
        level <= 1 -> shapes.take(n)
        level == 2 -> tier1.shuffled(r).take(n).map(::letter)
        level == 3 -> (tier2.shuffled(r).take(3) + tier1.shuffled(r).take(n - 3)).shuffled(r).map(::letter)
        else -> (tier3.shuffled(r).take(2) + tier2.shuffled(r).take(2) + tier1.shuffled(r).take(n - 4)).shuffled(r).map(::letter)
    }
}

/**
 * A stroke resampled every [step] units: tracing compares the finger with these points and keeps
 * the arc length reached so far.
 */
class StrokePath(points: List<Pt>, step: Float) {
    val xs: FloatArray
    val ys: FloatArray
    /** Arc length at each sample. */
    val s: FloatArray
    val length: Float

    init {
        val ox = ArrayList<Float>()
        val oy = ArrayList<Float>()
        val os = ArrayList<Float>()
        var acc = 0f
        ox += points[0].x; oy += points[0].y; os += 0f
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val d = hypot(b.x - a.x, b.y - a.y)
            if (d <= 0f) continue
            val n = max(1, (d / step).toInt())
            for (k in 1..n) {
                val t = k.toFloat() / n
                ox += a.x + (b.x - a.x) * t
                oy += a.y + (b.y - a.y) * t
                os += acc + d * t
            }
            acc += d
        }
        xs = ox.toFloatArray(); ys = oy.toFloatArray(); s = os.toFloatArray()
        length = acc
    }

    /** The point at arc length [at] (clamped). */
    fun at(at: Float): Pt {
        if (at <= 0f) return Pt(xs[0], ys[0])
        if (at >= length) return Pt(xs.last(), ys.last())
        var i = 1
        while (i < s.size && s[i] < at) i++
        val t = (at - s[i - 1]) / (s[i] - s[i - 1])
        return Pt(xs[i - 1] + (xs[i] - xs[i - 1]) * t, ys[i - 1] + (ys[i] - ys[i - 1]) * t)
    }

    /** Direction of travel at arc length [at], as a unit vector. */
    fun direction(at: Float): Pt {
        val a = at(at - 2f)
        val b = at(at + 2f)
        val d = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(1e-4f)
        return Pt((b.x - a.x) / d, (b.y - a.y) / d)
    }
}

/**
 * Following a glyph with a finger, in pixels. Progress only moves forward along the current stroke,
 * only from near where it already is (so touching the end first does nothing, and a circle's end
 * isn't mistaken for its start), and anywhere within [tolerance] of the line counts: wobbling is fine.
 */
class TraceTracker(glyph: Glyph, private val box: Float, val tolerance: Float = 45f, private val ahead: Float = tolerance * 2.4f) {
    val strokes: List<StrokePath> = glyph.strokes.map { st -> StrokePath(st.map { Pt(it.x * box, it.y * box) }, 4f) }

    var stroke = 0
        private set
    /** Arc length reached on the current stroke. */
    var progress = 0f
        private set
    val done: Boolean get() = stroke >= strokes.size

    /** 0..1 of the current stroke. */
    val fraction: Float get() = if (done) 1f else progress / strokes[stroke].length

    /** The finger is at (x, y) in the glyph box. Returns true when a stroke was just finished. */
    fun feed(x: Float, y: Float): Boolean {
        if (done) return false
        val path = strokes[stroke]
        var best = -1
        var bestD = Float.MAX_VALUE
        val from = progress - tolerance
        val to = progress + ahead
        for (i in path.s.indices) {
            val si = path.s[i]
            if (si < from || si > to) continue
            val d = hypot(path.xs[i] - x, path.ys[i] - y)
            if (d < bestD) { bestD = d; best = i }
        }
        if (best < 0 || bestD > tolerance) return false
        progress = max(progress, path.s[best])
        if (progress >= path.length - tolerance * 0.8f) { // generous: lifting a little early still counts
            stroke++
            progress = 0f
            return true
        }
        return false
    }
}
