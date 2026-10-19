package dev.framio.lab.labs.media

/**
 * Test-pattern frames for YUV 4:2:0 video. Pure Kotlin, unit-tested.
 * Luma: a horizontal ramp that scrolls 8 px per frame (motion for the encoder).
 * Chroma: 8 vertical colour bands, constant over time.
 */
object YuvPattern {

    /** One luma row for [frame]; every row of the frame is the same. */
    fun lumaRow(width: Int, frame: Int): ByteArray = ByteArray(width) { x -> (((x + frame * 8) * 255 / width) % 256).toByte() }

    private val bandsU = intArrayOf(128, 44, 156, 72, 184, 100, 212, 128)
    private val bandsV = intArrayOf(128, 21, 44, 58, 198, 212, 235, 128)

    /** U and V values for chroma column [cx] of a frame [width] luma pixels wide. */
    fun chroma(cx: Int, width: Int): Pair<Byte, Byte> {
        val band = (cx * 2 * bandsU.size / width).coerceIn(0, bandsU.size - 1)
        return bandsU[band].toByte() to bandsV[band].toByte()
    }

    /**
     * Reuses one buffer: chroma is written once, only the luma plane changes per frame.
     * [planar] = I420 (Y, U, V planes); otherwise NV12 (Y, interleaved UV).
     */
    class FrameBuilder(private val width: Int, private val height: Int, private val planar: Boolean) {
        val buffer = ByteArray(width * height * 3 / 2)

        init {
            val ySize = width * height
            val cw = width / 2
            val ch = height / 2
            for (cy in 0 until ch) for (cx in 0 until cw) {
                val (u, v) = chroma(cx, width)
                if (planar) {
                    buffer[ySize + cy * cw + cx] = u
                    buffer[ySize + cw * ch + cy * cw + cx] = v
                } else {
                    buffer[ySize + cy * width + 2 * cx] = u
                    buffer[ySize + cy * width + 2 * cx + 1] = v
                }
            }
        }

        fun frame(index: Int): ByteArray {
            val row = lumaRow(width, index)
            for (y in 0 until height) System.arraycopy(row, 0, buffer, y * width, width)
            return buffer
        }
    }
}
