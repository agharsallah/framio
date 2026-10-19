package dev.framio.lab.labs.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList

/** What MediaCodecList offers, with hardware vs software told apart by name. */
object Codecs {

    data class Entry(
        val name: String,
        val encoder: Boolean,
        val type: String,
        val hardware: Boolean,
        val detail: String,
    )

    /** Google's OMX.google.* (and later c2.android.*) are the software codecs; the rest run on the VPU. */
    fun isHardware(name: String) = !name.startsWith("OMX.google.") && !name.startsWith("c2.android.") && !name.contains(".sw.")

    fun all(): List<Entry> = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.flatMap { info ->
        info.supportedTypes.map { type ->
            Entry(info.name, info.isEncoder, type, isHardware(info.name), describe(info, type))
        }
    }

    private fun describe(info: MediaCodecInfo, type: String): String = try {
        val caps = info.getCapabilitiesForType(type)
        caps.videoCapabilities?.let { v ->
            val w = v.supportedWidths.upper
            val h = v.supportedHeights.upper
            val fhd = runCatching { v.areSizeAndRateSupported(1920, 1080, 30.0) }.getOrDefault(false)
            val hd = runCatching { v.areSizeAndRateSupported(1280, 720, 30.0) }.getOrDefault(false)
            "max ${w}×$h · 720p30 $hd · 1080p30 $fhd · ${v.bitrateRange.upper / 1_000_000} Mbps"
        } ?: caps.audioCapabilities?.let { a ->
            "${a.supportedSampleRateRanges.joinToString { r -> if (r.lower == r.upper) "${r.lower}" else "${r.lower}–${r.upper}" }} Hz · ${a.maxInputChannelCount} ch"
        } ?: ""
    } catch (e: Exception) {
        "caps: ${e.javaClass.simpleName}"
    }

    fun decodersFor(mime: String): List<String> = all().filter { !it.encoder && it.type == mime }.map { it.name }

    fun encodersFor(mime: String): List<String> = all().filter { it.encoder && it.type == mime }.map { it.name }

    /** Short names for MIME types. */
    fun shortType(mime: String) = when (mime) {
        "video/avc" -> "H.264"
        "video/hevc" -> "H.265"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/mp4v-es" -> "MPEG-4"
        "video/3gpp" -> "H.263"
        "video/mpeg2" -> "MPEG-2"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        else -> mime
    }
}
