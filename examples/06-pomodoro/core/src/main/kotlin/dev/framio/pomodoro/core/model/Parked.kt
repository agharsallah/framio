package dev.framio.pomodoro.core.model

import dev.framio.pomodoro.core.time.Moment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A stray thought scribbled on the parking-lot pad during focus. It reappears at the break.
 *
 * @param ink strokes in [InkCodec]'s compact text form
 */
@Serializable
public data class ParkedThought(
    @SerialName("ink") val ink: String,
    @SerialName("at") val at: Moment,
)

/** One finger stroke: points as x0,y0,x1,y1… normalized to 0..1 of the pad. */
public class Stroke(public val points: FloatArray) {
    public val size: Int get() = points.size / 2
    override fun equals(other: Any?): Boolean = other is Stroke && other.points.contentEquals(points)
    override fun hashCode(): Int = points.contentHashCode()
    override fun toString(): String = "Stroke(${points.size / 2} points)"
}

/**
 * Handwriting as compact, JSON-safe text. Points are quantized to 0..1023 (about 1 px on the pad); the first
 * point of a stroke is absolute and the rest are zigzag deltas. Each value is written as base-32 digits, low
 * digits first: continuation digits from [MORE], the final digit from [LAST]. Strokes are separated by '.'.
 * A one-word scribble is typically a few hundred characters.
 */
public object InkCodec {
    public const val SCALE: Int = 1023
    public const val MAX_POINTS: Int = 8_000
    internal const val LAST = "0123456789abcdefghijklmnopqrstuv"
    internal const val MORE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ!#\$%&*"

    public fun encode(strokes: List<Stroke>): String = buildString {
        strokes.forEachIndexed { i, s ->
            if (i > 0) append('.')
            var px = 0
            var py = 0
            for (p in 0 until s.size) {
                val x = quantize(s.points[2 * p])
                val y = quantize(s.points[2 * p + 1])
                varint(zigzag(x - px))
                varint(zigzag(y - py))
                px = x
                py = y
            }
        }
    }

    /** Null on anything that isn't ink (bad characters, an odd number of values, too many points). */
    public fun decode(text: String): List<Stroke>? {
        if (text.isEmpty()) return emptyList()
        var total = 0
        val out = ArrayList<Stroke>()
        for (part in text.split('.')) {
            val values = ArrayList<Int>()
            var acc = 0
            var shift = 0
            for (c in part) {
                val more = MORE.indexOf(c)
                if (more >= 0) {
                    if (shift > 25) return null
                    acc = acc or (more shl shift)
                    shift += 5
                    continue
                }
                val last = LAST.indexOf(c)
                if (last < 0 || shift > 25) return null
                values += acc or (last shl shift)
                acc = 0
                shift = 0
            }
            if (shift != 0 || values.size % 2 != 0) return null
            total += values.size / 2
            if (total > MAX_POINTS) return null
            val points = FloatArray(values.size)
            var px = 0
            var py = 0
            for (p in 0 until values.size / 2) {
                px += unzigzag(values[2 * p])
                py += unzigzag(values[2 * p + 1])
                points[2 * p] = px / SCALE.toFloat()
                points[2 * p + 1] = py / SCALE.toFloat()
            }
            out += Stroke(points)
        }
        return out
    }

    public fun quantize(v: Float): Int = (v.coerceIn(0f, 1f) * SCALE + 0.5f).toInt()

    private fun zigzag(v: Int) = (v shl 1) xor (v shr 31)
    private fun unzigzag(v: Int) = (v ushr 1) xor -(v and 1)

    private fun StringBuilder.varint(value: Int) {
        var v = value
        while (v >= 32) {
            append(MORE[v and 31])
            v = v ushr 5
        }
        append(LAST[v])
    }
}
