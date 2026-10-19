package dev.framio.pomodoro.ui.insights

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/*
 * Insights' charts (DESIGN-history §8, dataviz mark specs): static Canvas draws built once per size and data
 * (drawWithCache), nothing animates. Columns ≤ 24 px with a 4 px round data end, square at the baseline; 2 px lines with
 * an 8 px end dot ringed in the surface colour; 2 px surface gaps between stacked segments; hairline solid grid. Text
 * lives outside, in ink (never the data colour).
 */

/** A rounded-top column path: round at the data end, square at the baseline. */
private fun column(x: Float, top: Float, w: Float, base: Float, r: Float): Path = Path().apply {
    addRoundRect(RoundRect(x, top, x + w, base, CornerRadius(r), CornerRadius(r), CornerRadius.Zero, CornerRadius.Zero))
}

/** Columns of [values] from one baseline, with a solid hairline at [target] (none when null). */
@Composable
fun Columns(values: List<Int>, target: Int?, color: Color, grid: Color, modifier: Modifier = Modifier, highlightLast: Boolean = true) {
    Spacer(
        modifier.drawWithCache {
            val n = values.size.coerceAtLeast(1)
            val max = maxOf(values.maxOrNull() ?: 0, target ?: 0, 1) * 1.1f
            val slot = size.width / n
            val w = minOf(24.dp.toPx(), slot * 0.7f)
            val base = size.height - 1f
            val r = minOf(4.dp.toPx(), w / 2)
            val paths = values.mapIndexed { i, v ->
                val h = (v / max) * base
                column(i * slot + (slot - w) / 2, base - h, w, base, if (h > r) r else 0f) to (i == values.lastIndex && highlightLast)
            }
            val targetY = target?.let { base - (it / max) * base }
            onDrawBehind {
                drawLine(grid, Offset(0f, base), Offset(size.width, base), 1f)
                targetY?.let { drawLine(grid.copy(alpha = 1f), Offset(0f, it), Offset(size.width, it), 2f) }
                paths.forEach { (p, last) -> drawPath(p, if (last || !highlightLast) color else color.copy(alpha = 0.55f)) }
            }
        },
    )
}

/** One horizontal stacked bar (part-to-whole), with a 2 px gap in [surface] between segments. */
@Composable
fun StackedBar(parts: List<Pair<Int, Color>>, surface: Color, modifier: Modifier = Modifier) {
    Spacer(
        modifier.drawWithCache {
            val total = parts.sumOf { it.first }.coerceAtLeast(1)
            val gap = 2.dp.toPx()
            val r = 4.dp.toPx()
            onDrawBehind {
                var x = 0f
                parts.filter { it.first > 0 }.forEach { (v, c) ->
                    val w = size.width * v / total
                    drawRoundRect(c, Offset(x, 0f), Size((w - gap).coerceAtLeast(1f), size.height), CornerRadius(r))
                    x += w
                }
                if (x == 0f) drawRoundRect(surface, size = size, cornerRadius = CornerRadius(r))
            }
        },
    )
}

/** A thin horizontal bar of [fraction] of the width, round at the data end. */
@Composable
fun HBar(fraction: Float, color: Color, track: Color, modifier: Modifier = Modifier) {
    Spacer(
        modifier.drawWithCache {
            val h = size.height
            onDrawBehind {
                drawLine(track, Offset(0f, h / 2), Offset(size.width, h / 2), 1f)
                val w = size.width * fraction.coerceIn(0f, 1f)
                if (w > 0f) drawRoundRect(color, Offset.Zero, Size(w, h), CornerRadius(h / 2))
            }
        },
    )
}

/** A 2 px line of [values] with an 8 px end dot ringed in [surface]. */
@Composable
fun Sparkline(values: List<Int>, color: Color, surface: Color, modifier: Modifier = Modifier) {
    Spacer(
        modifier.drawWithCache {
            val max = (values.maxOrNull() ?: 0).coerceAtLeast(1).toFloat()
            val pad = 6.dp.toPx()
            val step = if (values.size > 1) (size.width - 2 * pad) / (values.size - 1) else 0f
            val pts = values.mapIndexed { i, v -> Offset(pad + i * step, size.height - pad - (v / max) * (size.height - 2 * pad)) }
            val path = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
            val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            onDrawBehind {
                drawPath(path, color, style = stroke)
                pts.lastOrNull()?.let {
                    drawCircle(surface, 6.dp.toPx(), it)
                    drawCircle(color, 4.dp.toPx(), it)
                }
            }
        },
    )
}

/**
 * 24 thin columns (distractions per clock hour) above a strip of 24 cells shaded by focus time in that hour: two
 * marks, one axis each, never a second y-scale.
 */
@Composable
fun HourColumns(counts: List<Int>, focus: List<Float>, color: Color, cell: (Float) -> Color, grid: Color, modifier: Modifier = Modifier) {
    Spacer(
        modifier.drawWithCache {
            val strip = 10.dp.toPx()
            val gap = 6.dp.toPx()
            val base = size.height - strip - gap
            val slot = size.width / 24f
            val w = minOf(14.dp.toPx(), slot * 0.7f)
            val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1) * 1.1f
            val r = 3.dp.toPx()
            val paths = counts.mapIndexed { i, v ->
                val h = (v / max) * base
                column(i * slot + (slot - w) / 2, base - h, w, base, if (h > r) r else 0f)
            }
            val fmax = (focus.maxOrNull() ?: 0f).coerceAtLeast(0.001f)
            onDrawBehind {
                drawLine(grid, Offset(0f, base), Offset(size.width, base), 1f)
                paths.forEach { drawPath(it, color) }
                focus.forEachIndexed { i, f ->
                    drawRect(cell(f / fmax), Offset(i * slot + 1f, base + gap), Size(slot - 2f, strip))
                }
            }
        },
    )
}

/** 24 heat cells of a mean rating (1..5) by hour; an hour without enough sessions is an outline only. */
@Composable
fun HeatCells(means: List<Float?>, cell: (Float) -> Color, outline: Color, modifier: Modifier = Modifier) {
    Spacer(
        modifier.drawWithCache {
            val slot = size.width / 24f
            val r = 4.dp.toPx()
            onDrawBehind {
                means.forEachIndexed { i, m ->
                    val o = Offset(i * slot + 1f, 0f)
                    val s = Size(slot - 2f, size.height)
                    if (m == null) {
                        drawRoundRect(outline, o, s, CornerRadius(r), style = Stroke(1f))
                    } else {
                        drawRoundRect(cell((m - 1f) / 4f), o, s, CornerRadius(r))
                    }
                }
            }
        },
    )
}

/** Estimate ○ → actual ● on one scale ([max] sessions): a dumbbell in two shades of one hue. */
@Composable
fun Dumbbell(estimate: Int, actual: Int, max: Int, color: Color, surface: Color, modifier: Modifier = Modifier) {
    Spacer(
        modifier.drawWithCache {
            val pad = 8.dp.toPx()
            val y = size.height / 2
            fun x(v: Int) = pad + (size.width - 2 * pad) * v / max.coerceAtLeast(1)
            val stroke = Stroke(2.dp.toPx())
            onDrawBehind {
                drawLine(color.copy(alpha = 0.45f), Offset(x(estimate), y), Offset(x(actual), y), 2.dp.toPx())
                drawCircle(surface, 7.dp.toPx(), Offset(x(estimate), y))
                drawCircle(color.copy(alpha = 0.6f), 5.dp.toPx(), Offset(x(estimate), y), style = stroke)
                drawCircle(surface, 7.dp.toPx(), Offset(x(actual), y))
                drawCircle(color, 5.dp.toPx(), Offset(x(actual), y))
            }
        },
    )
}
