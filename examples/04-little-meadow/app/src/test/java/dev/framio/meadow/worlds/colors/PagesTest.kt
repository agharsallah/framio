package dev.framio.meadow.worlds.colors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Area
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** The colouring pages: parts are big enough for small fingers, ids are unique, and a PNG preview. */
class PagesTest {

    private fun Shape.awt(): Path2D.Float = Path2D.Float(Path2D.WIND_NON_ZERO).also { p ->
        for (s in segs) when (s) {
            is MoveTo -> p.moveTo(s.x, s.y)
            is LineTo -> p.lineTo(s.x, s.y)
            is QuadTo -> p.quadTo(s.x1, s.y1, s.x, s.y)
            is CubicTo -> p.curveTo(s.x1, s.y1, s.x2, s.y2, s.x, s.y)
            Close -> p.closePath()
        }
    }

    /** How much of a part is still visible (not covered by parts above it), in design pixels, sampled. */
    private fun visibleArea(page: Page, index: Int): Int {
        val mine = Area(page.parts[index].shape.awt())
        for (j in index + 1 until page.parts.size) mine.subtract(Area(page.parts[j].shape.awt()))
        var n = 0
        for (y in 0 until 700 step 5) for (x in 0 until 1000 step 5) if (mine.contains(x.toDouble(), y.toDouble())) n++
        return n * 25
    }

    @Test fun sixPagesWithUniqueParts() {
        assertEquals(6, COLORING_PAGES.size)
        for (p in COLORING_PAGES) {
            assertEquals(p.id, p.parts.size, p.parts.map { it.id }.toSet().size)
            assertTrue(p.id, p.required.size in 5..16)
        }
    }

    @Test fun requiredPartsAreEasyToTap() {
        for (p in COLORING_PAGES) p.parts.forEachIndexed { i, part ->
            // a required part needs a visible area of roughly a fingertip-sized blob (about 45×45 dp on screen)
            if (part.required) assertTrue("${p.id}/${part.id} too small: ${visibleArea(p, i)}", visibleArea(p, i) >= 2500)
        }
    }

    @Test fun preview() {
        val out = File("build/coloring-preview").apply { mkdirs() }
        val colors = COLORING_COLORS.map { Color(it.second.toInt(), true) }
        for ((k, page) in (COLORING_PAGES + MIX_THINGS.map { it.page.copy(id = "mix_" + it.id) }).withIndex()) {
            val img = BufferedImage(1000, 700, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val ink = Color(0x3E, 0x33, 0x46)
            page.parts.forEachIndexed { i, part ->
                val s = part.shape.clockwise().awt()
                g.color = ink
                g.stroke = BasicStroke(16f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(s)
                g.color = if (part.id == "sky") Color(0xFFFDF8) else colors[(i * 3 + k) % colors.size]
                g.fill(s)
                for (d in part.details) when (d) {
                    is Line -> { g.color = ink; g.stroke = BasicStroke(8f * d.width / 0.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(d.shape.awt()) }
                    is Ink -> { g.color = ink; g.fill(d.shape.awt()) }
                    is Blush -> { g.color = Color(0xF2, 0xA7, 0xA0, 150); g.fill(d.shape.awt()) }
                }
            }
            g.dispose()
            ImageIO.write(img, "png", File(out, "${page.id}.png"))
        }
    }
}
