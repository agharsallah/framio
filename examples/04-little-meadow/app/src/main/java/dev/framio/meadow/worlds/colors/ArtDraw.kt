package dev.framio.meadow.worlds.colors

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Region
import android.graphics.Shader
import kotlin.math.hypot
import kotlin.math.max

/** The page's outlines: warm plum ink, never pure black. */
internal const val INK_ARGB = 0xFF3E3346.toInt()
internal const val PAPER_ARGB = 0xFFFFFDF8.toInt()
private const val BLUSH_ARGB = 0x8CF2A7A0.toInt()

internal fun Shape.toPath(): Path {
    val p = Path()
    for (s in clockwise().segs) when (s) {
        is MoveTo -> p.moveTo(s.x, s.y)
        is LineTo -> p.lineTo(s.x, s.y)
        is QuadTo -> p.quadTo(s.x1, s.y1, s.x, s.y)
        is CubicTo -> p.cubicTo(s.x1, s.y1, s.x2, s.y2, s.x, s.y)
        Close -> p.close()
    }
    return p
}

/** A fill spreading softly from where the finger touched: [progress] 0..1 over the part. */
internal class Reveal(val part: Int, val from: Int, val x: Float, val y: Float, val progress: Float)

/**
 * A [Page] ready to draw with android.graphics: one Path per part (built once), hit-testing with
 * Regions, and the fill-then-outline drawing that gives every part a thick friendly outline.
 * All coordinates are in the page's 1000×700 design space; the caller scales the canvas.
 */
internal class PageArt(val page: Page, private val outline: Float = 18f) {
    private val paths = page.parts.map { it.shape.toPath() }
    private val detailPaths = page.parts.map { part -> part.details.map { it to it.shape.toPath() } }
    private val regions by lazy {
        val clip = Region(-100, -100, (PAGE_W + 100).toInt(), (PAGE_H + 100).toInt())
        paths.map { Region().apply { setPath(it, clip) } }
    }
    /** For each part, the radius that covers it from anywhere inside (for the reveal). */
    private val reach = page.parts.map { val b = it.shape.bounds(); hypot(b[2] - b[0], b[3] - b[1]) }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = INK_ARGB
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** The topmost part under the design-space point, or -1. */
    fun hit(x: Float, y: Float): Int {
        for (i in paths.indices.reversed()) if (regions[i].contains(x.toInt(), y.toInt())) return i
        return -1
    }

    /** Draws the whole page; [colorOf] gives each part's current colour. */
    fun draw(c: Canvas, colorOf: (Int) -> Int, reveal: Reveal? = null) {
        c.save()
        c.clipRect(0f, 0f, PAGE_W, PAGE_H) // hills and waves run past the edges; a rectangle clip is cheap (no stencil)
        for (i in paths.indices) {
            // outline first at double width, then the fill on top: only the outer half of the line
            // stays, and overlapping pieces of one part merge without seams
            stroke.strokeWidth = outline * 2f
            c.drawPath(paths[i], stroke)
            val to = colorOf(i)
            if (reveal != null && reveal.part == i && reveal.progress < 1f) {
                val r = max(1f, reach[i] * reveal.progress)
                fill.shader = RadialGradient(reveal.x, reveal.y, r, intArrayOf(to, to, reveal.from), floatArrayOf(0f, 0.94f, 1f), Shader.TileMode.CLAMP)
                c.drawPath(paths[i], fill)
                fill.shader = null
            } else {
                fill.color = to
                c.drawPath(paths[i], fill)
            }
            for ((d, p) in detailPaths[i]) drawDetail(c, d, p)
        }
        c.restore()
    }

    private fun drawDetail(c: Canvas, d: Detail, p: Path) {
        when (d) {
            is Line -> {
                stroke.strokeWidth = outline * d.width
                c.drawPath(p, stroke)
            }
            is Ink -> {
                fill.color = INK_ARGB
                c.drawPath(p, fill)
                if (d.glint) {
                    val b = d.shape.bounds()
                    val w = b[2] - b[0]
                    fill.color = PAPER_ARGB
                    c.drawCircle(b[0] + w * 0.36f, b[1] + w * 0.36f, w * 0.17f, fill)
                }
            }
            is Blush -> {
                fill.color = BLUSH_ARGB
                c.drawPath(p, fill)
            }
        }
    }

    /** The finished page as a picture (for the grown-ups' gallery). */
    fun render(colorOf: (Int) -> Int, width: Int = PAGE_W.toInt()): Bitmap {
        val h = (width * PAGE_H / PAGE_W).toInt()
        val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(PAPER_ARGB)
        c.scale(width / PAGE_W, width / PAGE_W)
        draw(c, colorOf)
        return bmp
    }
}
