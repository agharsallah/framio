package dev.framio.lab.labs.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import java.io.File

/**
 * Photo-frame workloads: decode a large JPEG full size, downsampled to the screen, and a tile of it.
 * The test JPEG is generated (8 MP, like a phone photo) so no files need to be copied over.
 */
object ImageBench {

    data class Result(val file: File, val sourcePx: String, val fileBytes: Long, val encodeMs: Long, val fullMs: Long?, val sampledMs: Long, val regionMs: Long)

    fun run(dir: File, width: Int = 3264, height: Int = 2448): Result {
        val file = File(dir, "bench.jpg")
        // RGB_565 halves the memory of the source bitmap (16 MB instead of 32 MB) on a 1 GB device
        val src = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        Canvas(src).drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), 0xFF2C5D8A.toInt(), 0xFFE9A23B.toInt(), Shader.TileMode.MIRROR)
        })
        val t0 = System.nanoTime()
        file.outputStream().use { src.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val encodeMs = ms(t0)
        src.recycle()

        // Full size ARGB is 32 MB: more than this app's 80 MB heap likes, so it is tried but may fail
        val t1 = System.nanoTime()
        val full = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
        val fullMs = full?.let { ms(t1) }
        full?.recycle()

        // What a slideshow should do: decode straight to roughly screen size
        val t2 = System.nanoTime()
        val sampled = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 2; inPreferredConfig = Bitmap.Config.RGB_565 })
        val sampledMs = ms(t2)
        sampled.recycle()

        // Pan/zoom: decode just a 1280×800 window
        val t3 = System.nanoTime()
        val region = BitmapRegionDecoder.newInstance(file.path, false).run {
            decodeRegion(Rect(0, 0, 1280, 800), null).also { recycle() }
        }
        val regionMs = ms(t3)
        region.recycle()

        return Result(file, "${width}×$height", file.length(), encodeMs, fullMs, sampledMs, regionMs)
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000
}
