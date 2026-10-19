package dev.framio.meadow.worlds.colors

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * A tiny vector-drawing vocabulary for line art (colouring pages, the things painted in Mixing
 * colors). Pure data: the app turns it into android.graphics.Path, the unit tests into java.awt
 * shapes to check the pages and preview them as PNGs.
 */

sealed interface Seg
data class MoveTo(val x: Float, val y: Float) : Seg
data class LineTo(val x: Float, val y: Float) : Seg
data class QuadTo(val x1: Float, val y1: Float, val x: Float, val y: Float) : Seg
data class CubicTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : Seg
data object Close : Seg

/** One outline, possibly several closed pieces (filled together, non-zero winding). */
class Shape(val segs: List<Seg>) {
    operator fun plus(o: Shape) = Shape(segs + o.segs)

    /**
     * Every closed piece turned the same way round (clockwise on screen), so overlapping pieces of
     * one shape fill as a union with non-zero winding instead of cancelling into holes.
     */
    fun clockwise(): Shape {
        val out = ArrayList<Seg>(segs.size)
        var i = 0
        while (i < segs.size) {
            var j = i + 1
            while (j < segs.size && segs[j] !is MoveTo) j++
            val piece = segs.subList(i, j)
            out += if (signedArea(piece) < 0f) reversed(piece) else piece
            i = j
        }
        return Shape(out)
    }

    /** Rough bounds (control points included): left, top, right, bottom. */
    fun bounds(): FloatArray {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        for (s in segs) when (s) {
            is MoveTo -> { xs += s.x; ys += s.y }
            is LineTo -> { xs += s.x; ys += s.y }
            is QuadTo -> { xs += listOf(s.x1, s.x); ys += listOf(s.y1, s.y) }
            is CubicTo -> { xs += listOf(s.x1, s.x2, s.x); ys += listOf(s.y1, s.y2, s.y) }
            Close -> {}
        }
        return floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }
}

private fun end(s: Seg): Pair<Float, Float>? = when (s) {
    is MoveTo -> s.x to s.y
    is LineTo -> s.x to s.y
    is QuadTo -> s.x to s.y
    is CubicTo -> s.x to s.y
    Close -> null
}

/** Shoelace area over the end points (and control points, close enough for the sign), y down. */
private fun signedArea(piece: List<Seg>): Float {
    val pts = ArrayList<Pair<Float, Float>>()
    for (s in piece) when (s) {
        is QuadTo -> { pts += s.x1 to s.y1; pts += s.x to s.y }
        is CubicTo -> { pts += s.x1 to s.y1; pts += s.x2 to s.y2; pts += s.x to s.y }
        else -> end(s)?.let { pts += it }
    }
    var a = 0f
    for (k in pts.indices) {
        val (x0, y0) = pts[k]
        val (x1, y1) = pts[(k + 1) % pts.size]
        a += x0 * y1 - x1 * y0
    }
    return a / 2f
}

private fun reversed(piece: List<Seg>): List<Seg> {
    val closed = piece.last() == Close
    val body = piece.filter { it != Close }
    val ends = body.map { end(it)!! }
    val out = arrayListOf<Seg>(MoveTo(ends.last().first, ends.last().second))
    for (k in body.size - 1 downTo 1) {
        val (px, py) = ends[k - 1]
        out += when (val s = body[k]) {
            is QuadTo -> QuadTo(s.x1, s.y1, px, py)
            is CubicTo -> CubicTo(s.x2, s.y2, s.x1, s.y1, px, py)
            else -> LineTo(px, py)
        }
    }
    if (closed) out += Close
    return out
}

private const val K = 0.5522848f // cubic Bézier circle constant

/** An ellipse, optionally turned by [deg] degrees (clockwise on screen). */
fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, deg: Float = 0f): Shape {
    val a = deg * PI.toFloat() / 180f
    val c = cos(a)
    val s = sin(a)
    fun p(x: Float, y: Float) = (cx + x * c - y * s) to (cy + x * s + y * c)
    val pts = listOf(
        p(rx, 0f), p(rx, ry * K), p(rx * K, ry), p(0f, ry), p(-rx * K, ry), p(-rx, ry * K), p(-rx, 0f),
        p(-rx, -ry * K), p(-rx * K, -ry), p(0f, -ry), p(rx * K, -ry), p(rx, -ry * K), p(rx, 0f),
    )
    val out = arrayListOf<Seg>(MoveTo(pts[0].first, pts[0].second))
    for (i in 0 until 4) {
        val (a1, b1) = pts[i * 3 + 1]
        val (a2, b2) = pts[i * 3 + 2]
        val (a3, b3) = pts[i * 3 + 3]
        out += CubicTo(a1, b1, a2, b2, a3, b3)
    }
    out += Close
    return Shape(out)
}

fun circle(cx: Float, cy: Float, r: Float) = ellipse(cx, cy, r, r)

fun rrect(l: Float, t: Float, r: Float, b: Float, rad: Float): Shape {
    val k = rad * (1 - K)
    return Shape(
        listOf(
            MoveTo(l + rad, t), LineTo(r - rad, t), CubicTo(r - k, t, r, t + k, r, t + rad),
            LineTo(r, b - rad), CubicTo(r, b - k, r - k, b, r - rad, b),
            LineTo(l + rad, b), CubicTo(l + k, b, l, b - k, l, b - rad),
            LineTo(l, t + rad), CubicTo(l, t + k, l + k, t, l + rad, t), Close,
        ),
    )
}

/** Straight-sided polygon through [pts] (x0, y0, x1, y1, …). */
fun poly(vararg pts: Float, close: Boolean = true): Shape {
    val out = arrayListOf<Seg>(MoveTo(pts[0], pts[1]))
    for (i in 2 until pts.size step 2) out += LineTo(pts[i], pts[i + 1])
    if (close) out += Close
    return Shape(out)
}

/**
 * A soft closed blob through [pts] (x0, y0, …): each corner becomes a curve, like clay
 * (a Catmull-Rom spline as cubic Béziers).
 */
fun blob(vararg pts: Float, tension: Float = 1f): Shape {
    val n = pts.size / 2
    fun x(i: Int) = pts[((i % n + n) % n) * 2]
    fun y(i: Int) = pts[((i % n + n) % n) * 2 + 1]
    val t = tension / 6f
    val out = arrayListOf<Seg>(MoveTo(x(0), y(0)))
    for (i in 0 until n) {
        out += CubicTo(
            x(i) + (x(i + 1) - x(i - 1)) * t, y(i) + (y(i + 1) - y(i - 1)) * t,
            x(i + 1) - (x(i + 2) - x(i)) * t, y(i + 1) - (y(i + 2) - y(i)) * t,
            x(i + 1), y(i + 1),
        )
    }
    out += Close
    return Shape(out)
}

/** An open smooth line through [pts] (for strings, smiles, stems drawn as strokes). */
fun curve(vararg pts: Float): Shape {
    val n = pts.size / 2
    fun x(i: Int) = pts[i.coerceIn(0, n - 1) * 2]
    fun y(i: Int) = pts[i.coerceIn(0, n - 1) * 2 + 1]
    val out = arrayListOf<Seg>(MoveTo(x(0), y(0)))
    for (i in 0 until n - 1) {
        out += CubicTo(
            x(i) + (x(i + 1) - x(i - 1)) / 6f, y(i) + (y(i + 1) - y(i - 1)) / 6f,
            x(i + 1) - (x(i + 2) - x(i)) / 6f, y(i + 1) - (y(i + 2) - y(i)) / 6f,
            x(i + 1), y(i + 1),
        )
    }
    return Shape(out)
}

/** A puffy star (points rounded by the thick outline). */
fun starShape(cx: Float, cy: Float, outer: Float, inner: Float, points: Int = 5, deg: Float = 0f): Shape {
    val f = FloatArray(points * 4)
    for (i in 0 until points * 2) {
        val r = if (i % 2 == 0) outer else inner
        val a = -PI / 2 + i * PI / points + deg * PI / 180
        f[i * 2] = cx + r * cos(a).toFloat()
        f[i * 2 + 1] = cy + r * sin(a).toFloat()
    }
    return poly(*f)
}

fun heart(cx: Float, cy: Float, w: Float): Shape {
    val h = w * 0.9f
    return Shape(
        listOf(
            MoveTo(cx, cy + h * 0.5f),
            CubicTo(cx - w * 0.15f, cy + h * 0.38f, cx - w * 0.55f, cy + h * 0.1f, cx - w * 0.5f, cy - h * 0.2f),
            CubicTo(cx - w * 0.45f, cy - h * 0.55f, cx - w * 0.05f, cy - h * 0.55f, cx, cy - h * 0.25f),
            CubicTo(cx + w * 0.05f, cy - h * 0.55f, cx + w * 0.45f, cy - h * 0.55f, cx + w * 0.5f, cy - h * 0.2f),
            CubicTo(cx + w * 0.55f, cy + h * 0.1f, cx + w * 0.15f, cy + h * 0.38f, cx, cy + h * 0.5f),
            Close,
        ),
    )
}

/** A band of a ring between radii [r0] < [r1], from angle [a0] to [a1] degrees (0 = right, 180 = left; upper half uses 180..360). */
fun ringBand(cx: Float, cy: Float, r0: Float, r1: Float, a0: Float, a1: Float, steps: Int = 24): Shape {
    val out = ArrayList<Seg>()
    fun pt(r: Float, a: Float) = (cx + r * cos(a * PI / 180).toFloat()) to (cy + r * sin(a * PI / 180).toFloat())
    for (i in 0..steps) {
        val (x, y) = pt(r1, a0 + (a1 - a0) * i / steps)
        out += if (i == 0) MoveTo(x, y) else LineTo(x, y)
    }
    for (i in steps downTo 0) {
        val (x, y) = pt(r0, a0 + (a1 - a0) * i / steps)
        out += LineTo(x, y)
    }
    out += Close
    return Shape(out)
}

/** Something drawn on top of a region that isn't coloured: a line (smile, string) or an ink dot (eye). */
sealed interface Detail {
    val shape: Shape
}
/** A stroke; [width] relative to the page's outline width. */
data class Line(override val shape: Shape, val width: Float = 0.8f) : Detail
/** Filled with ink (pupils); [glint] adds the little white light in the eye. */
data class Ink(override val shape: Shape, val glint: Boolean = false) : Detail
/** A soft blush (not colourable, a fixed tint). */
data class Blush(override val shape: Shape) : Detail

/**
 * A colourable part of a page. Drawn in list order (later on top), and tapped from the top down.
 * [required] parts must be coloured to finish the page; tiny bits and the background are optional.
 */
data class Part(val id: String, val shape: Shape, val required: Boolean = true, val details: List<Detail> = emptyList(), val start: Long? = null)

/** A colouring page in a 1000×700 design space. */
data class Page(val id: String, val parts: List<Part>) {
    val required get() = parts.filter { it.required }
}

const val PAGE_W = 1000f
const val PAGE_H = 700f
