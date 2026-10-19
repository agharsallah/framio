package dev.framio.meadow.worlds.quiet

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random

/** Eased sine, 0..1 → 0..1: starts and ends at rest, nothing ever jumps. */
fun sineInOut(t: Float): Float = ((1 - cos(PI * t.coerceIn(0f, 1f))) / 2).toFloat()

/** One breath: in, a soft pause at the top, out, a pause at the bottom (all in ms). */
data class BreathTiming(val inMs: Int, val holdMs: Int, val outMs: Int, val restMs: Int) {
    val totalMs get() = inMs + holdMs + outMs + restMs
}

/** About 4 s in and 5 s out; a little quicker for the youngest, a short hold for the oldest. */
fun breathTiming(level: Int) = when {
    level <= 0 -> BreathTiming(3500, 400, 4500, 700)
    level <= 2 -> BreathTiming(4000, 500, 5000, 800)
    else -> BreathTiming(4000, 1000, 5000, 800)
}

/** How full the breath is (0 = empty, 1 = full) [ms] into a breath. */
fun breathLevel(ms: Long, t: BreathTiming): Float = when {
    ms < t.inMs -> sineInOut(ms / t.inMs.toFloat())
    ms < t.inMs + t.holdMs -> 1f
    ms < t.inMs + t.holdMs + t.outMs -> 1f - sineInOut((ms - t.inMs - t.holdMs) / t.outMs.toFloat())
    else -> 0f
}

const val BREATHS = 5

/** Narration for breath [i] (0-based) of a picture: guided for the first two, then just "in… out…". */
fun breathLines(pic: String, i: Int): Pair<String, String> =
    if (i < 2) "breathe_${pic}_in" to "breathe_${pic}_out" else "breathe_in" to "breathe_out"

/**
 * The night light's dark overlay: from [from] towards [NIGHT_DARK] over [durationMs], eased so the
 * first minutes stay friendly and the end is slow.
 */
fun nightAlpha(elapsedMs: Long, durationMs: Long, from: Float = 0f): Float {
    if (durationMs <= 0) return NIGHT_DARK
    val t = (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)
    return from + (NIGHT_DARK - from) * sineInOut(t)
}

const val NIGHT_DARK = 0.97f

/** Music follows the dark: full at the start, a whisper (20 %) at the end. */
fun nightMusic(alpha: Float): Float = 1f - 0.8f * (alpha / NIGHT_DARK).coerceIn(0f, 1f)

/** A star in the night sky (fractions of the screen), with its own slow twinkle. */
data class SkyStar(val x: Float, val y: Float, val r: Float, val periodS: Float, val phase: Float, val drift: Float)

/** A calm, uneven scatter of stars: none over the moon, none too close to another. Pure, unit-tested. */
fun skyStars(n: Int, seed: Int, moon: Pair<Float, Float> = 0.83f to 0.2f): List<SkyStar> {
    val r = Random(seed)
    val out = ArrayList<SkyStar>(n)
    var guard = 0
    while (out.size < n && guard++ < n * 60) {
        val x = 0.03f + r.nextFloat() * 0.94f
        val y = 0.04f + r.nextFloat() * r.nextFloat() * 0.6f // more stars high up
        if (dist(x, y, moon.first, moon.second) < 0.12f) continue
        if (out.any { dist(it.x, it.y, x, y) < 0.07f }) continue
        out += SkyStar(x, y, 2.2f + r.nextFloat() * 2.8f, 6f + r.nextFloat() * 6f, r.nextFloat() * 6.283f, (r.nextFloat() - 0.5f) * 0.02f)
    }
    return out
}

private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = (ax - bx) * 1.6f // the screen is 1.6 times wider than tall
    val dy = ay - by
    return sqrt(dx * dx + dy * dy) / 1.6f
}

/** The star nearest to a tap (fractions), or -1 when none is close. */
fun nearestStar(stars: List<SkyStar>, x: Float, y: Float, within: Float = 0.12f): Int {
    var best = -1
    var bd = within
    stars.forEachIndexed { i, s ->
        val d = dist(s.x, s.y, x, y)
        if (d < bd) {
            bd = d
            best = i
        }
    }
    return best
}
