package dev.framio.meadow.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import dev.framio.meadow.core.Lang
import dev.framio.meadow.core.TAG
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * The friends' voice: pre-recorded clips in assets/voice/<lang>/<id>.ogg (tools/voice/make_voice.py),
 * because the frame has no text-to-speech engine. One clip at a time; [say] replaces whatever is
 * playing, so a child tapping fast never hears a pile-up.
 *
 * Special ids: "@name" is the child's name as recorded by a grown-up on the dashboard (skipped
 * when there is none); "@file:<absolute path>" plays a family voice message.
 *
 * Every say() also becomes a [Caption]: the clips' words joined into one line, with the clip that is
 * playing and when it started, for the on-screen captions.
 */
class Voice(private val context: Context, private val familyDir: File) {

    private val thread = HandlerThread("voice").apply { start() }
    private val handler = Handler(thread.looper)
    private var player: MediaPlayer? = null
    private var queue = ArrayDeque<String>()
    private var waiter: CompletableDeferred<Unit>? = null
    private val manifests = HashMap<Lang, Map<String, Clip>>()

    @Volatile var lang: Lang = Lang.EN
    /** 0..1; applies to the clip already playing too (the mute button). */
    @Volatile var volume: Float = 1f
        set(value) {
            field = value
            handler.post { player?.setVolume(value, value) }
        }
    /** The child's name as typed by a grown-up, for the caption of "@name" ("" = leave it out). */
    @Volatile var childName: () -> String = { "" }
    /** Who sent the family message at this path, for its caption; null = no caption. */
    @Volatile var messageFrom: (path: String) -> String? = { null }

    // the caption being built for the current say(): which part each queued id fills (-1 = none)
    private var pending: Caption? = null
    private var partOf = IntArray(0)
    private var played = 0
    private var seq = 0
    private val captionState = MutableStateFlow<Caption?>(null)
    /** What is being said now (or was just said: [Caption.done]); null after [stop] or for lines without words. */
    val caption: StateFlow<Caption?> = captionState.asStateFlow()

    private val speakingState = MutableStateFlow(false)
    /** True while a clip plays: characters move their mouths, music ducks. */
    val speaking: StateFlow<Boolean> = speakingState.asStateFlow()

    /** Plays [ids] in order, replacing anything already playing. Unknown ids are skipped (and logged). */
    fun say(vararg ids: String) {
        val list = ids.toList()
        handler.post { start(list, null) }
    }

    /** Like [say], and returns when the last clip ends or is interrupted by the next [say]/[stop]. */
    suspend fun sayAndWait(vararg ids: String) {
        val done = CompletableDeferred<Unit>()
        val list = ids.toList()
        handler.post { start(list, done) }
        done.await()
    }

    fun stop() = handler.post { start(emptyList(), null) }

    fun has(id: String): Boolean = durationMs(id) > 0

    /** Length of a clip in ms, 0 if it doesn't exist in this language or English. */
    fun durationMs(id: String): Int = clip(id)?.ms ?: 0

    private fun clip(id: String): Clip? = manifest(lang)[id] ?: manifest(Lang.EN)[id]

    // ---- voice thread

    private fun start(ids: List<String>, done: CompletableDeferred<Unit>?) {
        waiter?.complete(Unit)
        waiter = done
        queue = ArrayDeque(ids)
        prepareCaption(ids)
        release()
        next()
    }

    private fun prepareCaption(ids: List<String>) {
        val pieces = ArrayList<CaptionPiece>()
        var kind = CaptionKind.Speech
        partOf = IntArray(ids.size) { -1 }
        for ((i, id) in ids.withIndex()) {
            val piece = when {
                id == "@name" -> childName().trim().takeIf { it.isNotEmpty() && nameFile() != null }?.let { CaptionPiece(it, 0, isName = true) }
                id.startsWith("@file:") -> messageFrom(id.removePrefix("@file:"))?.let {
                    kind = CaptionKind.Message
                    CaptionPiece(CaptionText.messageFrom(it, lang == Lang.DE), 0)
                }
                else -> clip(id)?.takeIf { it.text.isNotBlank() }?.let { CaptionPiece(it.text, it.ms) }
            } ?: continue
            partOf[i] = pieces.size
            pieces += piece
        }
        played = 0
        pending = if (pieces.isEmpty()) null else {
            val (text, parts) = CaptionText.join(pieces)
            Caption(++seq, text, parts, kind)
        }
        // nothing to show for this line (or stop()): the old caption goes now, not after its linger
        if (pending == null) captionState.value = null
    }

    private fun next() {
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val index = played++
            val mp = open(id) ?: continue
            player = mp
            speakingState.value = true
            mp.setOnCompletionListener { handler.post { release(); next() } }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "voice $id error $what/$extra")
                handler.post { release(); next() }
                true
            }
            mp.setVolume(volume, volume)
            mp.start()
            // MediaPlayer sometimes never reports the end (seen after errors on this firmware's decoder):
            // a clip still "playing" well after its length counts as finished, so the voice and its
            // caption never hang
            val length = (clip(id)?.ms ?: mp.duration).coerceAtLeast(500)
            handler.postDelayed({ if (player === mp) { Log.w(TAG, "voice $id never finished"); release(); next() } }, length + 4000L)
            val part = partOf.getOrElse(index) { -1 }
            if (part >= 0) pending?.let { captionState.value = it.copy(clip = part, clipStartMs = SystemClock.uptimeMillis()) }
            return
        }
        captionState.value?.let { c -> if (!c.done) captionState.value = c.copy(clip = c.parts.size, clipStartMs = SystemClock.uptimeMillis()) }
        pending = null
        speakingState.value = false
        waiter?.complete(Unit)
        waiter = null
    }

    private fun open(id: String): MediaPlayer? {
        val mp = MediaPlayer()
        mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        try {
            when {
                id == "@name" -> {
                    val f = nameFile() ?: run { mp.release(); return null }
                    mp.setDataSource(f.absolutePath)
                }
                id.startsWith("@file:") -> mp.setDataSource(id.removePrefix("@file:"))
                else -> {
                    val fd = asset(id) ?: run {
                        Log.w(TAG, "voice clip missing: $id")
                        mp.release()
                        return null
                    }
                    fd.use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                }
            }
            mp.prepare()
            return mp
        } catch (e: Exception) {
            Log.w(TAG, "voice $id failed", e)
            mp.release()
            return null
        }
    }

    private fun asset(id: String): AssetFileDescriptor? {
        for (l in listOf(lang, Lang.EN).distinct()) {
            if (manifest(l)[id] == null) continue
            try {
                return context.assets.openFd("voice/${l.code}/$id.ogg")
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun nameFile(): File? = familyDir.listFiles { f -> f.name.startsWith("name.") }?.firstOrNull()

    private fun release() {
        player?.let {
            it.setOnCompletionListener(null)
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        player = null
    }

    private fun manifest(l: Lang): Map<String, Clip> = synchronized(manifests) {
        manifests.getOrPut(l) {
            try {
                parseManifest(context.assets.open("voice/${l.code}/manifest.json").bufferedReader().readText())
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }
}
