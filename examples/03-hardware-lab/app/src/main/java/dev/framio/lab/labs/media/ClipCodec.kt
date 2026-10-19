package dev.framio.lab.labs.media

import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * Encodes a generated test clip with the hardware H.264 encoder, and decodes it back as fast as possible.
 * Plain MediaCodec in synchronous (polling) mode, ByteBuffer/Image input, no Surface.
 */
object ClipCodec {

    data class EncodeResult(val file: File, val codec: String, val frames: Int, val millis: Long, val bytes: Long, val colorFormat: String) {
        val fps get() = frames * 1000.0 / millis
    }

    data class DecodeResult(val codec: String, val frames: Int, val millis: Long) {
        val fps get() = frames * 1000.0 / millis
    }

    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val TIMEOUT_US = 1_000L // short polls: long waits on one side starve the other and cap the measured fps

    fun encode(out: File, width: Int = 1280, height: Int = 720, fps: Int = 30, seconds: Int = 3, codecName: String? = null): EncodeResult {
        val name = codecName ?: MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(MediaFormat.createVideoFormat(MIME, width, height))
            ?: error("no H.264 encoder for ${width}×$height")
        val codec = MediaCodec.createByCodecName(name)
        val formats = codec.codecInfo.getCapabilitiesForType(MIME).colorFormats.toSet()
        // Raw layouts first: one bulk copy per frame. Flexible needs per-pixel Image writes, far slower on a Cortex-A7.
        val layout = when {
            CodecCapabilities.COLOR_FormatYUV420SemiPlanar in formats -> Layout.NV12
            CodecCapabilities.COLOR_FormatYUV420Planar in formats -> Layout.I420
            else -> Layout.FLEXIBLE
        }
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, layout.colorFormat)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val frames = YuvPattern.FrameBuilder(width, height, planar = layout == Layout.I420)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val total = fps * seconds
        var queued = 0
        var done = false
        val info = MediaCodec.BufferInfo()
        val t0 = System.nanoTime()
        try {
            while (!done) {
                if (queued <= total) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        if (queued == total) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            val size = fill(codec, i, width, height, queued, layout, frames)
                            codec.queueInputBuffer(i, 0, size, queued * 1_000_000L / fps, 0)
                        }
                        queued++
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                            buf.position(info.offset).limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                    }
                }
                if ((System.nanoTime() - t0) > 60_000_000_000L) error("encoder stalled")
            }
        } finally {
            codec.stop()
            codec.release()
            if (track >= 0) runCatching { muxer.stop() }
            muxer.release()
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        return EncodeResult(out, name, total, ms, out.length(), layout.name)
    }

    private enum class Layout(val colorFormat: Int) {
        NV12(CodecCapabilities.COLOR_FormatYUV420SemiPlanar),
        I420(CodecCapabilities.COLOR_FormatYUV420Planar),
        FLEXIBLE(CodecCapabilities.COLOR_FormatYUV420Flexible),
    }

    /** Writes test frame [frame] into input buffer [index]; returns the byte count. */
    private fun fill(codec: MediaCodec, index: Int, w: Int, h: Int, frame: Int, layout: Layout, frames: YuvPattern.FrameBuilder): Int {
        val image = if (layout == Layout.FLEXIBLE) codec.getInputImage(index) else null
        if (image == null) {
            val data = frames.frame(frame)
            codec.getInputBuffer(index)!!.apply { clear(); put(data) }
            return data.size
        }
        val (yPlane, uPlane, vPlane) = image.planes
        val row = YuvPattern.lumaRow(w, frame)
        val yBuf = yPlane.buffer
        for (y in 0 until h) {
            yBuf.position(y * yPlane.rowStride)
            yBuf.put(row)
        }
        for (cy in 0 until h / 2) for (cx in 0 until w / 2) {
            val (u, v) = YuvPattern.chroma(cx, w)
            uPlane.buffer.put(cy * uPlane.rowStride + cx * uPlane.pixelStride, u)
            vPlane.buffer.put(cy * vPlane.rowStride + cx * vPlane.pixelStride, v)
        }
        return w * h * 3 / 2
    }

    /** Decodes every frame of [file] with [codecName] into ByteBuffers (not rendered) and times it. */
    fun decode(file: File, codecName: String): DecodeResult {
        val ex = MediaExtractor().apply { setDataSource(file.path) }
        val trackIndex = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        ex.selectTrack(trackIndex)
        val codec = MediaCodec.createByCodecName(codecName)
        // Ask for "as fast as possible" rather than real-time pacing (API 23); some codecs reject it, so retry without.
        // getTrackFormat returns a fresh copy each call (the MediaFormat copy constructor is API 29).
        try {
            codec.configure(ex.getTrackFormat(trackIndex).apply { setInteger(MediaFormat.KEY_OPERATING_RATE, 240) }, null, null, 0)
        } catch (_: Exception) {
            codec.reset()
            codec.configure(ex.getTrackFormat(trackIndex), null, null, 0)
        }
        codec.start()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var frames = 0
        val t0 = System.nanoTime()
        try {
            while (true) {
                // fill every free input buffer, then wait briefly for output
                while (!inputDone) {
                    val i = codec.dequeueInputBuffer(0)
                    if (i < 0) break
                    val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5_000)
                if (o >= 0) {
                    if (info.size > 0) frames++
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                if ((System.nanoTime() - t0) > 60_000_000_000L) error("decoder stalled")
            }
        } finally {
            codec.stop()
            codec.release()
            ex.release()
        }
        return DecodeResult(codecName, frames, (System.nanoTime() - t0) / 1_000_000)
    }
}
