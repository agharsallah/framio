package dev.framio.pomodoro.ui.breaks

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import kotlinx.coroutines.delay
import dev.framio.pomodoro.core.time.DayPart
import dev.framio.pomodoro.ui.theme.QuietColors
import kotlin.math.PI
import kotlin.math.sin

/**
 * The colours of the break landscape for one part of the day, already toned for the theme (see [landscapePalette]).
 * [sun] is the moon at night; [stars] only at night.
 */
@Immutable
data class LandscapePalette(
    val skyTop: Color,
    val skyBottom: Color,
    val sun: Color,
    val sunX: Float,
    val sunY: Float,
    val sunRadius: Float,
    val cloud: Color,
    val far: Color,
    val mid: Color,
    val near: Color,
    val grass: Color,
    val stars: Color? = null,
)

/**
 * Muted, dim colours per part of the day (PLAN principle 7: the landscape is a pause for the eyes, not a show).
 * Dark themes pull everything a quarter of the way towards their background; Paper pulls nearly half way towards its
 * off-white, which turns the same scene into a pale wash.
 */
fun landscapePalette(part: DayPart, colors: QuietColors): LandscapePalette {
    val base = when (part) {
        DayPart.Dawn -> LandscapePalette(
            skyTop = Color(0xFF2B3550), skyBottom = Color(0xFFC08A78), sun = Color(0xFFF0C29A), sunX = 0.22f, sunY = 0.50f, sunRadius = 34f,
            cloud = Color(0x59E6B4A4), far = Color(0xFF4C5672), mid = Color(0xFF3A465C), near = Color(0xFF27323F), grass = Color(0xFF1F2B31),
        )
        DayPart.Day -> LandscapePalette(
            skyTop = Color(0xFF3B6890), skyBottom = Color(0xFF9DBFD0), sun = Color(0xFFF2E6C2), sunX = 0.74f, sunY = 0.18f, sunRadius = 30f,
            cloud = Color(0x73EEF2F4), far = Color(0xFF6C8C9C), mid = Color(0xFF4E775C), near = Color(0xFF345A43), grass = Color(0xFF2A4835),
        )
        DayPart.Dusk -> LandscapePalette(
            skyTop = Color(0xFF29254A), skyBottom = Color(0xFFC9775A), sun = Color(0xFFEFA46A), sunX = 0.80f, sunY = 0.48f, sunRadius = 38f,
            cloud = Color(0x59C48683), far = Color(0xFF594A66), mid = Color(0xFF3E3550), near = Color(0xFF2A2438), grass = Color(0xFF211C2C),
        )
        DayPart.Night -> LandscapePalette(
            skyTop = Color(0xFF070B16), skyBottom = Color(0xFF1B2541), sun = Color(0xFFD5D9E3), sunX = 0.78f, sunY = 0.17f, sunRadius = 22f,
            cloud = Color(0x4D3A4560), far = Color(0xFF1C2638), mid = Color(0xFF151D2C), near = Color(0xFF0E1420), grass = Color(0xFF0B1018),
            stars = Color(0x99D5D9E3),
        )
    }
    val toward = colors.background
    val k = if (colors.light) 0.45f else 0.25f
    fun Color.tone() = lerp(this, toward.copy(alpha = alpha), k)
    return base.copy(
        skyTop = base.skyTop.tone(), skyBottom = base.skyBottom.tone(), sun = base.sun.tone(), cloud = base.cloud.tone(),
        far = base.far.tone(), mid = base.mid.tone(), near = base.near.tone(), grass = base.grass.tone(), stars = base.stars?.tone(),
    )
}

/**
 * The landscape's one clock, in seconds since it started; read only inside layer blocks (draw phase).
 *
 * It ticks at [STEP_MS] (20 Hz, every third vsync), not every vsync: the motion is so slow (clouds ≈ 1 px a step, the
 * hills a fraction of one) that more steps look the same, while on this fill-rate-bound GPU every step redraws the
 * whole screen (no partial updates). At 60 Hz the frame couldn't keep up (49–51 fps, 11–22 % janky, LOG M4); at
 * 20 Hz each step has three vsyncs of GPU time and lands on time (LOG M6). Between steps nothing is drawn.
 */
private class LandscapeClock {
    val seconds: MutableFloatState = mutableFloatStateOf(0f)

    suspend fun run() {
        val start = withInfiniteAnimationFrameNanos { it }
        // One lambda for the whole break: nothing is allocated per frame by this loop
        val onFrame: (Long) -> Unit = { t -> seconds.floatValue = ((t - start) / 1_000_000_000.0).toFloat() }
        while (true) {
            // Wake a little before the third vsync from now, then wait for that vsync: steps stay evenly spaced
            delay(STEP_MS - STEP_SLACK_MS)
            withInfiniteAnimationFrameNanos(onFrame)
        }
    }
}

/** One landscape step: three vsyncs at 60 Hz. */
private const val STEP_MS = 50L

/** How early the clock wakes before a step's vsync (under one vsync, so it never lands a vsync late or early). */
private const val STEP_SLACK_MS = 10L

/** Seconds for the clouds to cross the screen once (PLAN §5.2: "clouds over 60 s"). */
private const val CLOUD_PERIOD = 60f

/** The slow pan that gives the hills their parallax: there and back in this many seconds. */
private const val PAN_PERIOD = 96f

/** A grass tuft's sway. */
private const val SWAY_PERIOD = 6.5f

/** The sky stops where the far ridge surely covers it (its lowest point). */
private const val SKY_BOTTOM = 0.66f

private const val TWO_PI = (2 * PI).toFloat()

/**
 * The break's slow, dim, procedural landscape (PLAN §3.2 principles 6–7, §5.2): a sky gradient with the sun (or the
 * moon and a few stars), clouds drifting across in about a minute, three hill ridges panning slowly at different
 * depths (parallax) and two grass tufts swaying.
 *
 * Built for the Mali-400, where the limit is blended pixels per frame (memory bandwidth), not draw calls (LOG M4):
 * every shape is a path built once per size (drawWithCache), so HWUI rasterizes it into a texture once. The hills pan
 * through `graphicsLayer.translationX` (layer blocks only); each layer is only as big as its band; below a ridge's
 * lowest point the hill is an opaque rect; the sky stops where the far ridge covers it; the clouds are four small
 * textures moved in one small layer's draw phase, and the tufts sway in the near ridge's. Five state reads per step,
 * about thirteen draw ops, nothing allocated; 20 steps a second ([LandscapeClock]). With [motion] off (Reduce motion)
 * it is a still picture: no clock, no frames.
 */
@Composable
fun Landscape(palette: LandscapePalette, motion: Boolean, modifier: Modifier = Modifier) {
    val clock = remember { LandscapeClock() }
    if (motion) LaunchedEffect(clock) { clock.run() }
    val t = clock.seconds
    // Its own layer: the break's 1 Hz countdown re-records the screen, never the landscape
    BoxWithConstraints(modifier.fillMaxSize().graphicsLayer().clearAndSetSemantics {}) {
        val w = maxWidth
        val h = maxHeight
        Sky(palette, Modifier.fillMaxSize())
        // Clouds: each its own small layer drifting left, wrapping round off screen: only the clouds' own pixels are
        // drawn (one band-wide texture was mostly transparent pixels, all of them blended, LOG M4)
        Clouds(palette.cloud, w, h) { t.floatValue }
        // Each ridge is filled only down to where the next one surely covers it (its lowest point), and the sky only
        // down to the far ridge's lowest point: on this GPU every blended pixel counts (LOG M4)
        Hill(palette.far, w, h, baseline = 0.60f, amplitude = 0.05f, bottom = 0.77f, seed = 1, pan = 6.dp) { t.floatValue }
        Hill(palette.mid, w, h, baseline = 0.70f, amplitude = 0.06f, bottom = 0.88f, seed = 2, pan = 14.dp) { t.floatValue }
        // The near ridge carries the two grass tufts: they pan with it and sway in its draw phase
        Hill(palette.near, w, h, baseline = 0.82f, amplitude = 0.05f, bottom = 1f, seed = 3, pan = 28.dp, grass = palette.grass) { t.floatValue }
    }
}

/** The sky: one gradient rect and the sun or moon (and the stars at night). Static: drawn once per size. */
@Composable
private fun Sky(palette: LandscapePalette, modifier: Modifier = Modifier) {
    Spacer(
        modifier.drawWithCache {
            val skyHeight = size.height * SKY_BOTTOM
            val brush = Brush.verticalGradient(listOf(palette.skyTop, palette.skyBottom), endY = skyHeight)
            val sun = Offset(size.width * palette.sunX, size.height * palette.sunY)
            val r = palette.sunRadius.dp.toPx()
            val starColor = palette.stars
            val stars = starColor?.let { starPoints(size.width, size.height * 0.5f) }
            val starPaint = Paint().apply {
                color = starColor ?: Color.Transparent
                strokeWidth = 2.dp.toPx()
                strokeCap = StrokeCap.Round
            }
            onDrawBehind {
                drawRect(brush, size = Size(size.width, skyHeight))
                // One call for all the stars (a list of points would be one call each)
                if (stars != null) drawIntoCanvas { it.drawRawPoints(PointMode.Points, stars, starPaint) }
                drawCircle(palette.sun, r, sun)
            }
        },
    )
}

/**
 * One ridge: a sum of three slow sines, filled down to the bottom. It is wider than the screen by its [pan] on both
 * sides, and pans there and back: the nearer the ridge, the further it moves (parallax).
 */
@Composable
private fun Hill(
    color: Color,
    w: Dp,
    h: Dp,
    baseline: Float,
    amplitude: Float,
    bottom: Float,
    seed: Int,
    pan: Dp,
    grass: Color? = null,
    t: () -> Float,
) {
    Spacer(
        Modifier
            // The layer is the ridge's band only (its highest point to its fill bottom), not the whole screen
            .overflowing(w + pan * 2, h * (bottom - baseline + amplitude), x = -pan, y = h * (baseline - amplitude))
            .graphicsLayer { translationX = pan.toPx() * sin(TWO_PI * t() / PAN_PERIOD + seed) }
            .drawWithCache {
                val hPx = h.toPx()
                val top = hPx * (baseline - amplitude)
                val solid = hPx * (baseline + amplitude) - top // below the ridge's lowest point the hill is a plain rect
                // The ridge as a path texture (blended) only where it has an edge; the solid part an opaque rect
                val ridge = ridgePath(size.width, hPx, baseline, amplitude, baseline + amplitude + 1f / hPx, seed).apply { translate(Offset(0f, -top)) }
                val tuftSize = 120.dp.toPx()
                val blades = tuftPath(tuftSize, tuftSize)
                // Tuft roots, in this layer's coordinates (it starts `pan` left of the screen and `top` down)
                val roots = TUFTS.map { (fx, fy, _) -> Offset(pan.toPx() + fx * w.toPx(), fy * hPx - top) }
                onDrawBehind {
                    drawPath(ridge, color)
                    drawRect(color, topLeft = Offset(0f, solid), size = Size(size.width, size.height - solid))
                    if (grass != null) {
                        val s = t() // read while drawing: the sway re-records this small layer, nothing else
                        roots.forEachIndexed { i, root ->
                            rotate(2.5f * sin(TWO_PI * s / SWAY_PERIOD + TUFTS[i].third), pivot = root) {
                                translate(root.x - tuftSize / 2, root.y - tuftSize) { drawPath(blades, grass) }
                            }
                        }
                    }
                }
            },
    )
}

/** Grass tufts on the near ridge: root position (fractions of the screen) and sway phase. */
private val TUFTS = listOf(Triple(0.68f, 0.86f, 0f), Triple(0.30f, 0.90f, 2.1f))

/** The clouds, drifting left across the screen once per [CLOUD_PERIOD] and coming back in from the right: one layer,
 * each cloud's texture drawn at its own place (a band-wide texture was mostly transparent pixels, all blended). */
@Composable
private fun Clouds(color: Color, w: Dp, h: Dp, t: () -> Float) {
    Spacer(
        Modifier.size(w, h * 0.3f).drawWithCache {
            val wPx = size.width
            val puffs = Clouds.map { c -> cloudPath(c.width * wPx, c.width * wPx * 0.22f * 1.8f) }
            onDrawBehind {
                val travelled = t() / CLOUD_PERIOD * wPx
                Clouds.forEachIndexed { i, c ->
                    val cw = c.width * wPx
                    val span = wPx + cw
                    val x = (c.x * wPx - travelled).mod(span).let { if (it > wPx) it - span else it }
                    translate(x, c.y * h.toPx()) { drawPath(puffs[i], color) }
                }
            }
        },
    )
}

/** A cloud: where it starts (fractions of the screen) and how wide it is. */
private class CloudSpec(val x: Float, val y: Float, val width: Float)

private val Clouds = listOf(CloudSpec(0.10f, 0.13f, 0.16f), CloudSpec(0.42f, 0.07f, 0.22f), CloudSpec(0.70f, 0.18f, 0.14f), CloudSpec(0.86f, 0.06f, 0.10f))

/** A flat-bottomed cloud of three puffs (overlapping ovals filled as one shape), filling [w] × [h]. */
private fun cloudPath(w: Float, h: Float): Path = Path().apply {
    val base = h / 1.8f // the flat part's height; the puffs rise above it
    val y = h - base
    addOval(androidx.compose.ui.geometry.Rect(0f, y, w, h))
    addOval(androidx.compose.ui.geometry.Rect(w * 0.15f, y - base * 0.55f, w * 0.55f, y + base * 0.6f))
    addOval(androidx.compose.ui.geometry.Rect(w * 0.42f, y - base * 0.8f, w * 0.85f, y + base * 0.5f))
}

private fun ridgePath(w: Float, h: Float, baseline: Float, amplitude: Float, bottom: Float, seed: Int): Path = Path().apply {
    val steps = 64
    val a = amplitude * h
    moveTo(0f, bottom * h)
    for (i in 0..steps) {
        val x = w * i / steps
        val u = x / w
        val y = baseline * h - a * (
            0.55f * sin(TWO_PI * (u * 1.1f) + seed * 1.7f) +
                0.30f * sin(TWO_PI * (u * 2.3f) + seed * 0.9f) +
                0.15f * sin(TWO_PI * (u * 5.1f) + seed * 2.3f)
            )
        lineTo(x, y)
    }
    lineTo(w, bottom * h)
    close()
}

/** Seven thin blades from one root, leaning a little either way. */
private fun tuftPath(w: Float, h: Float): Path = Path().apply {
    val root = w / 2f
    val leans = floatArrayOf(-0.34f, -0.22f, -0.1f, 0f, 0.12f, 0.25f, 0.36f)
    val heights = floatArrayOf(0.55f, 0.8f, 0.68f, 0.95f, 0.72f, 0.85f, 0.5f)
    val base = w * 0.035f
    for (i in leans.indices) {
        val bx = root + (i - 3) * base * 1.2f
        val tipX = bx + leans[i] * w
        val tipY = h * (1f - heights[i])
        moveTo(bx - base, h)
        quadraticTo(bx + (tipX - bx) * 0.3f, h - (h - tipY) * 0.6f, tipX, tipY)
        quadraticTo(bx + (tipX - bx) * 0.35f, h - (h - tipY) * 0.5f, bx + base, h)
        close()
    }
}

/** A fixed scatter of stars in the upper sky (deterministic, so every night looks the same and goldens are stable). */
private fun starPoints(w: Float, h: Float): FloatArray {
    var s = 0x2F6E2B1
    fun next(): Float {
        s = s * 1103515245 + 12345
        return ((s ushr 8) and 0xFFFF) / 65535f
    }
    return FloatArray(84) { i -> if (i % 2 == 0) next() * w else next() * h }
}

/**
 * Lays the content out at [width] × [height] and places it at ([x], 0), while taking only the space it is given: a
 * layer wider than the screen (a pan, a loop) without being clamped or centred by its parent.
 */
private fun Modifier.overflowing(width: Dp, height: Dp, x: Dp = 0.dp, y: Dp = 0.dp): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(Constraints.fixed(width.roundToPx(), height.roundToPx()))
    layout(constraints.constrainWidth(p.width), constraints.constrainHeight(p.height)) { p.place(x.roundToPx(), y.roundToPx()) }
}
