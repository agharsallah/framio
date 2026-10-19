package dev.framio.lab.labs.media

import android.content.Context
import android.media.MediaFormat
import android.media.MediaPlayer
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.framio.lab.core.Check
import dev.framio.lab.core.CheckList
import dev.framio.lab.core.Format
import dev.framio.lab.core.Lab
import dev.framio.lab.core.LocalHost
import dev.framio.lab.core.Status
import dev.framio.lab.core.ui.ActionButton
import dev.framio.lab.core.ui.Actions
import dev.framio.lab.core.ui.InkDim
import dev.framio.lab.core.ui.LabPage
import dev.framio.lab.core.ui.Lime
import dev.framio.lab.core.ui.Mono
import dev.framio.lab.core.ui.Note
import dev.framio.lab.core.ui.Section
import dev.framio.lab.core.ui.SelfTestSection
import dev.framio.lab.core.ui.StatusChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Rockchip VPU: codec list, hardware H.264 encode → decode round trip, playback, JPEG decoding. */
object MediaLab {

    val lab = Lab(
        id = "media",
        title = "Video & images",
        summary = "Codec list, hardware H.264 encode/decode benchmark, playback, JPEG decode speed",
        accent = Lime,
        selfTest = ::selfTest,
        content = { Content() },
    )

    fun clipFile(context: Context) = File(context.cacheDir, "testclip.mp4")

    @Composable
    private fun Content() {
        val context = LocalHost.current.context
        LabPage("Video & images", "Rockchip VPU decodes H.264/VP8/MPEG up to 1080p; HEVC and VP9 fall back to software. One hardware H.264 encoder (≤720p).") {
            CodecSection()
            RoundTripSection(context)
            ImageSection(context)
            SelfTestSection(lab.id, note = "Encodes a 2 s 720p clip on the VPU, decodes it with the hardware and the software decoder, plays it, decodes an 8 MP JPEG.")
        }
    }

    @Composable
    private fun CodecSection() {
        val all = remember { Codecs.all() }
        Section("Codecs", note = "MediaCodecList(ALL_CODECS). HW = runs on the VPU, SW = Google's software codec on the CPU.") {
            listOf(false to "Decoders", true to "Encoders").forEach { (enc, title) ->
                Text(title, style = MaterialTheme.typography.titleMedium)
                all.filter { it.encoder == enc }.forEach { c ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusChip(if (c.hardware) Status.PASS else Status.INFO, Modifier.width(76.dp))
                        Text(Codecs.shortType(c.type), Modifier.width(90.dp), style = MaterialTheme.typography.bodyMedium)
                        Text(c.name, Modifier.width(300.dp), style = Mono)
                        Text(c.detail, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(color = InkDim))
                    }
                }
            }
            Note("Chip: PASS = hardware, INFO = software.")
        }
    }

    @Composable
    private fun RoundTripSection(context: Context) {
        val scope = rememberCoroutineScope()
        var log by remember { mutableStateOf(listOf<String>()) }
        var busy by remember { mutableStateOf(false) }
        var playing by remember { mutableStateOf(false) }
        val clip = clipFile(context)

        Section("Encode → decode → play", note = "MediaCodec (synchronous), MediaMuxer → MP4, MediaExtractor, VideoView.") {
            Actions {
                ActionButton(if (busy) "Working…" else "Encode 3 s 720p clip", {
                    busy = true
                    scope.launch {
                        log = log + withContext(Dispatchers.Default) {
                            runCatching { ClipCodec.encode(clip) }.fold(
                                { r -> "Encoded ${r.frames} frames with ${r.codec} (${r.colorFormat}) in ${r.millis} ms = ${Format.f1(r.fps)} fps → ${Format.bytes(r.bytes)}" },
                                { e -> "Encode failed: ${e.message}" },
                            )
                        }
                        busy = false
                    }
                }, enabled = !busy)
                ActionButton("Decode: HW vs SW", {
                    busy = true
                    scope.launch {
                        log = log + withContext(Dispatchers.Default) {
                            Codecs.decodersFor(MediaFormat.MIMETYPE_VIDEO_AVC).map { name ->
                                runCatching { ClipCodec.decode(clip, name) }.fold(
                                    { r -> "${r.codec}: ${r.frames} frames in ${r.millis} ms = ${Format.f1(r.fps)} fps" },
                                    { e -> "$name failed: ${e.message}" },
                                )
                            }
                        }
                        busy = false
                    }
                }, enabled = !busy && clip.exists(), primary = false)
                ActionButton(if (playing) "Stop video" else "Play clip", { playing = !playing }, enabled = clip.exists(), primary = false)
            }
            log.forEach { Text(it, style = Mono) }
            if (playing && clip.exists()) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoPath(clip.path)
                            setOnPreparedListener { mp -> mp.isLooping = true; start() }
                        }
                    },
                    modifier = Modifier.width(640.dp).height(360.dp),
                    onRelease = { it.stopPlayback() },
                )
            }
        }
    }

    @Composable
    private fun ImageSection(context: Context) {
        val scope = rememberCoroutineScope()
        var result by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        Section("JPEG decoding", note = "BitmapFactory on an 8 MP (3264×2448) JPEG: full size, inSampleSize=2 to near screen size, and a 1280×800 BitmapRegionDecoder tile.") {
            Actions {
                ActionButton(if (busy) "Decoding…" else "Run", {
                    busy = true
                    scope.launch {
                        result = withContext(Dispatchers.Default) {
                            runCatching { ImageBench.run(context.cacheDir) }.fold({ describe(it) }, { "failed: ${it.message}" })
                        }
                        busy = false
                    }
                }, enabled = !busy)
            }
            result?.let { Text(it, style = Mono) }
            Note("For a slideshow: always decode with inSampleSize (or a library that does) — full 8 MP bitmaps are 32 MB each.")
        }
    }

    private fun describe(r: ImageBench.Result) =
        "${r.sourcePx} JPEG ${Format.bytes(r.fileBytes)} · encode ${r.encodeMs} ms · full decode ${r.fullMs?.let { "$it ms" } ?: "out of memory"} · ½ size ${r.sampledMs} ms · tile ${r.regionMs} ms"

    private suspend fun selfTest(context: Context): List<Check> = CheckList(lab.id).run {
        val all = Codecs.all()
        guard("Hardware decoders") {
            val hw = all.filter { !it.encoder && it.hardware && it.type.startsWith("video/") }
            pass("Hardware decoders", hw.joinToString { Codecs.shortType(it.type) })
            val swOnly = listOf("video/hevc", "video/x-vnd.on2.vp9").filter { m -> all.none { !it.encoder && it.hardware && it.type == m } }
            if (swOnly.isNotEmpty()) warn("Software-only video", swOnly.joinToString { Codecs.shortType(it) } + ": use H.264 for clips")
        }
        val clip = clipFile(context)
        guard("H.264 encode") {
            val r = withContext(Dispatchers.Default) { ClipCodec.encode(clip, seconds = 2) }
            if (r.bytes > 10_000) pass("H.264 encode", "${r.codec} (${r.colorFormat}): ${r.frames} frames 1280×720 in ${r.millis} ms = ${Format.f1(r.fps)} fps, ${Format.bytes(r.bytes)}")
            else fail("H.264 encode", "output only ${r.bytes} bytes")
        }
        if (clip.exists()) {
            Codecs.decodersFor(MediaFormat.MIMETYPE_VIDEO_AVC).forEach { name ->
                guard("Decode $name") {
                    val r = withContext(Dispatchers.Default) { ClipCodec.decode(clip, name) }
                    if (r.frames >= 55) pass("Decode ${if (Codecs.isHardware(name)) "HW" else "SW"}", "$name: ${r.frames} frames in ${r.millis} ms = ${Format.f1(r.fps)} fps")
                    else fail("Decode $name", "only ${r.frames} frames")
                }
            }
            guard("MediaPlayer") {
                val mp = MediaPlayer()
                mp.setDataSource(clip.path)
                mp.prepare()
                pass("MediaPlayer", "prepared ${mp.videoWidth}×${mp.videoHeight}, ${mp.duration} ms")
                mp.release()
            }
        }
        guard("JPEG") {
            val r = withContext(Dispatchers.Default) { ImageBench.run(context.cacheDir) }
            info("JPEG", describe(r))
        }
        manual("Playback", "Video & images → Encode, then Play clip: a scrolling grey ramp over colour bands")
        items
    }
}
