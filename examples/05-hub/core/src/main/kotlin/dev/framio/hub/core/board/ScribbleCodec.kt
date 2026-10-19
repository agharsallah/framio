package dev.framio.hub.core.board

import java.io.ByteArrayOutputStream
import java.io.IOException

/** One finger stroke: an ink, and points as x0,y0,x1,y1… normalized to 0..1 of the canvas. */
class Stroke(val ink: Int, val points: FloatArray) {
    override fun equals(other: Any?) = other is Stroke && other.ink == ink && other.points.contentEquals(points)
    override fun hashCode() = 31 * ink + points.contentHashCode()
    override fun toString() = "Stroke(ink=$ink, points=${points.size / 2})"
}

/**
 * Scribbles as compact binary: points quantized to 0..4095 (≈ 0.3 px on the 1280-px canvas), the first point
 * of a stroke absolute and the rest as zigzag-varint deltas. A typical scribble is 2–8 KB, so it stays out of
 * the state file. Lossy only below a third of a pixel, and a scribble looks the same after a restart.
 */
object ScribbleCodec {
    private val MAGIC = byteArrayOf('F'.code.toByte(), 'H'.code.toByte(), 'S'.code.toByte(), 1)
    const val SCALE = 4095
    const val MAX_STROKES = 500
    const val MAX_POINTS = 20_000

    fun encode(strokes: List<Stroke>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        writeVarint(out, strokes.size)
        for (s in strokes) {
            writeVarint(out, s.ink)
            val n = s.points.size / 2
            writeVarint(out, n)
            var px = 0
            var py = 0
            for (i in 0 until n) {
                val x = quantize(s.points[2 * i])
                val y = quantize(s.points[2 * i + 1])
                writeVarint(out, zigzag(x - px))
                writeVarint(out, zigzag(y - py))
                px = x
                py = y
            }
        }
        return out.toByteArray()
    }

    /** Throws [IOException] on anything that isn't a scribble (truncated, wrong magic, absurd sizes). */
    fun decode(bytes: ByteArray): List<Stroke> {
        val r = Reader(bytes)
        if (bytes.size < 4 || !bytes.copyOfRange(0, 4).contentEquals(MAGIC)) throw IOException("not a scribble")
        r.pos = 4
        val count = r.varint()
        if (count !in 0..MAX_STROKES) throw IOException("bad stroke count $count")
        var total = 0
        return List(count) {
            val ink = r.varint()
            val n = r.varint()
            total += n
            if (n < 0 || total > MAX_POINTS) throw IOException("too many points")
            val points = FloatArray(n * 2)
            var px = 0
            var py = 0
            for (i in 0 until n) {
                px += unzigzag(r.varint())
                py += unzigzag(r.varint())
                points[2 * i] = px / SCALE.toFloat()
                points[2 * i + 1] = py / SCALE.toFloat()
            }
            Stroke(ink, points)
        }
    }

    fun quantize(v: Float) = (v.coerceIn(0f, 1f) * SCALE + 0.5f).toInt()

    private fun zigzag(v: Int) = (v shl 1) xor (v shr 31)
    private fun unzigzag(v: Int) = (v ushr 1) xor -(v and 1)

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }

    private class Reader(val b: ByteArray) {
        var pos = 0
        fun varint(): Int {
            var result = 0
            var shift = 0
            while (true) {
                if (pos >= b.size) throw IOException("truncated")
                val byte = b[pos++].toInt()
                result = result or ((byte and 0x7F) shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                if (shift > 28) throw IOException("varint too long")
            }
        }
    }
}
