package dev.framio.meadow.ui

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.framio.meadow.core.TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

/**
 * The pre-rendered 3D sprites in assets/sprites/<name>.webp (tools/render3d). Decoded off the main
 * thread, at most two at a time, into a bounded LRU cache: on Android 6 bitmaps count against
 * the Java heap.
 */
object Sprites {
    lateinit var assets: AssetManager

    private val cache = object : LruCache<String, ImageBitmap>(56 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val decoder = Dispatchers.IO.limitedParallelism(2)

    fun cached(name: String): ImageBitmap? = cache.get(name)

    suspend fun load(name: String): ImageBitmap? = cache.get(name) ?: withContext(decoder) {
        cache.get(name) ?: decode(name)?.also { cache.put(name, it) }
    }

    /** Warms the cache, e.g. every pose of a friend before the first blink. */
    suspend fun preload(names: List<String>) = names.forEach { load(it) }

    fun exists(name: String): Boolean = try {
        assets.openFd("sprites/$name.webp").close()
        true
    } catch (_: Exception) {
        false
    }

    private fun decode(name: String): ImageBitmap? = try {
        assets.open("sprites/$name.webp").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
    } catch (e: Exception) {
        Log.w(TAG, "sprite $name missing", e)
        null
    }
}

@Composable
fun rememberSprite(name: String): ImageBitmap? {
    val bmp by produceState(Sprites.cached(name), name) { value = Sprites.load(name) }
    return bmp
}

/** A sprite, fitted into the given size. Draws nothing (but keeps its size) until decoded. */
@Composable
fun Sprite(name: String, modifier: Modifier = Modifier, alpha: Float = 1f, contentScale: ContentScale = ContentScale.Fit) {
    val bmp = rememberSprite(name)
    if (bmp == null) Spacer(modifier) else Image(
        bitmap = bmp, contentDescription = null, modifier = modifier, alpha = alpha,
        contentScale = contentScale, filterQuality = FilterQuality.Medium,
    )
}

/** Draws [bmp] fitted and centred into the DrawScope (for canvases that place many sprites). */
fun DrawScope.drawSpriteFit(bmp: ImageBitmap, alpha: Float = 1f, colorFilter: ColorFilter? = null) {
    val s = minOf(size.width / bmp.width, size.height / bmp.height)
    val w = (bmp.width * s).toInt()
    val h = (bmp.height * s).toInt()
    drawImage(
        bmp, srcOffset = IntOffset.Zero, srcSize = IntSize(bmp.width, bmp.height),
        dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()), dstSize = IntSize(w, h),
        alpha = alpha, colorFilter = colorFilter, filterQuality = FilterQuality.Medium,
    )
}
