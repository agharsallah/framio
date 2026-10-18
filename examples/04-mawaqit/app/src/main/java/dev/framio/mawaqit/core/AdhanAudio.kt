package dev.framio.mawaqit.core

/**
 * The audio containers the frame's Android 6 MediaPlayer plays, recognised by their first bytes. An uploaded
 * adhan is stored under a name we generate with [ext], whatever the client called it, so the content decides.
 */
enum class AudioType(val ext: String, val mime: String) {
    MP3("mp3", "audio/mpeg"),
    AAC("aac", "audio/aac"),     // raw ADTS stream
    M4A("m4a", "audio/mp4"),     // MP4/3GP container (AAC, ALAC…)
    OGG("ogg", "audio/ogg"),     // Ogg Vorbis only: Ogg Opus needs Android 10
    WAV("wav", "audio/wav"),
    FLAC("flac", "audio/flac");

    companion object {
        /** Bytes [sniff] looks at: enough for the Ogg identification header. */
        const val HEAD = 64

        fun ofExt(ext: String): AudioType? = entries.firstOrNull { it.ext == ext.lowercase() }

        /** The type of a file starting with [head] (its first [HEAD] bytes or fewer), or null if it isn't one we play. */
        fun sniff(head: ByteArray): AudioType? {
            fun at(i: Int, s: String) = head.size >= i + s.length && s.indices.all { head[i + it] == s[it].code.toByte() }
            fun u(i: Int) = if (i < head.size) head[i].toInt() and 0xFF else -1
            return when {
                at(0, "ID3") -> MP3 // an ID3v2 tag: practically always MP3 behind it
                at(0, "fLaC") -> FLAC
                at(0, "RIFF") && at(8, "WAVE") -> WAV
                at(4, "ftyp") -> M4A
                // Ogg page header (27 bytes + segment table), then the first packet: "\u0001vorbis" or "OpusHead"
                at(0, "OggS") -> if ((0..minOf(head.size, HEAD) - 6).any { at(it, "vorbis") }) OGG else null
                u(0) == 0xFF && (u(1) and 0xF6) == 0xF0 -> AAC // ADTS sync: 12 bits set, layer 00
                u(0) == 0xFF && (u(1) and 0xE0) == 0xE0 && (u(1) shr 3 and 3) != 1 && (u(1) shr 1 and 3) != 0 &&
                    (u(2) shr 4) != 0xF && (u(2) shr 2 and 3) != 3 -> MP3 // MPEG audio frame: valid version, layer, bitrate, rate
                else -> null
            }
        }
    }
}

/** Rules for the adhans a user adds (see [CustomAdhan]); the Android side stores and decodes them. */
object AdhanFiles {
    const val MAX_BYTES = 20L * 1024 * 1024
    const val MAX_COUNT = 10
    /** Longer than any adhan; also [CustomAdhan]'s bound. */
    const val MAX_SECONDS = 1800
    private const val ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

    /** 8 random [a-z0-9]: the only thing from which a stored file's name is made. */
    fun newId(random: (Int) -> Int): String = (1..8).map { ID_CHARS[random(ID_CHARS.length)] }.joinToString("")

    fun fileName(id: String, type: AudioType) = "custom-$id.${type.ext}"

    /** A display name from what the user typed or the file was called: no extension, no control characters, ≤ 60. */
    fun cleanName(raw: String?): String {
        val s = (raw ?: "").substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("\\.(mp3|m4a|mp4|aac|ogg|oga|wav|flac|3gp)$", RegexOption.IGNORE_CASE), "")
            .filter { !it.isISOControl() }.replace(Regex("\\s+"), " ").trim()
        return s.take(60).trim().ifEmpty { "My adhan" }
    }

    /** "custom:<id>" → that adhan, if it is still in the list. */
    fun find(s: AdhanSettings, voice: String): CustomAdhan? =
        if (!voice.startsWith("custom:")) null else s.custom.firstOrNull { "custom:${it.id}" == voice }
}
