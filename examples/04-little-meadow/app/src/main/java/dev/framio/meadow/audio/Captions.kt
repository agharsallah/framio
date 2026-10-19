package dev.framio.meadow.audio

import org.json.JSONObject

/*
 * Captions: what the friends say, as text on screen for grown-ups and early readers. Pure functions
 * (no Android), so the joining, timing and paging are unit tested; [Voice] publishes a [Caption] and
 * ui/CaptionOverlay draws it.
 */

/** One recorded line: its length and its words, from assets/voice/<lang>/manifest.json. */
data class Clip(val ms: Int, val text: String)

/** Reads a manifest (`{"id":{"h":…,"ms":…,"t":"Well done!"}}`). Older manifests without "t" give empty text. */
fun parseManifest(json: String): Map<String, Clip> {
    val j = JSONObject(json)
    return j.keys().asSequence().associateWith { id ->
        val c = j.optJSONObject(id)
        Clip(c?.optInt("ms") ?: 0, c?.optString("t", "").orEmpty())
    }
}

enum class CaptionKind {
    /** A friend's narration. */
    Speech,
    /** A family voice message: we can't know the words, only who sent it. */
    Message,
}

/** Where one clip's words sit in [Caption.text], and how long the clip plays. */
data class CaptionPart(val start: Int, val end: Int, val ms: Int)

/**
 * The words of one [Voice.say]: its clips joined into one line ("I'd like" + "three" + "berries"),
 * the clip playing now and when it started, so the words can light up as they are spoken.
 */
data class Caption(
    /** Bumped for every say(): a new line, not the next clip of the same one. */
    val seq: Int,
    val text: String,
    val parts: List<CaptionPart>,
    val kind: CaptionKind = CaptionKind.Speech,
    /** Index into [parts] of the clip playing now; parts.size once all of it has been said. */
    val clip: Int = 0,
    /** SystemClock.uptimeMillis() when [clip] started (or when it all ended). */
    val clipStartMs: Long = 0L,
) {
    val done: Boolean get() = clip >= parts.size
}

/** A piece of a line before joining: a clip's text, or the child's name (merged into the sentence before it). */
data class CaptionPiece(val text: String, val ms: Int, val isName: Boolean = false)

/** A word in [Caption.text] and when it starts, in ms after its clip starts. */
data class CaptionWord(val start: Int, val end: Int, val atMs: Int)

object CaptionText {

    private val END = charArrayOf('!', '.', '?', '…')

    /**
     * Joins the pieces of one say() with spaces. A name joins the sentence before it, so
     * "Good morning!" + "Mia" + "What shall we play?" reads "Good morning, Mia! What shall we play?".
     * Returns the text and one part per piece (same order).
     */
    fun join(pieces: List<CaptionPiece>): Pair<String, List<CaptionPart>> {
        val sb = StringBuilder()
        val parts = ArrayList<CaptionPart>(pieces.size)
        for ((i, p) in pieces.withIndex()) {
            val text = p.text.trim()
            if (p.isName && i > 0 && sb.isNotEmpty()) {
                // move the previous sentence's end mark after the name
                var mark = ""
                while (sb.isNotEmpty() && sb.last() in END) {
                    mark = sb.last() + mark
                    sb.setLength(sb.length - 1)
                }
                parts[i - 1] = parts[i - 1].copy(end = minOf(parts[i - 1].end, sb.length))
                val start = sb.length
                sb.append(", ").append(text).append(mark)
                parts += CaptionPart(start, sb.length, p.ms)
                continue
            }
            if (sb.isNotEmpty()) sb.append(' ')
            val start = sb.length
            sb.append(if (p.isName) "$text!" else text)
            parts += CaptionPart(start, sb.length, p.ms)
        }
        return sb.toString() to parts
    }

    /**
     * Words of one part with an estimated start time each. The recordings are trimmed and spoken at an
     * even pace, so time follows the letters, plus a small gap per word and a pause after commas and
     * full stops. Good enough to light up the word being said; never needs to be exact.
     */
    fun words(text: String, part: CaptionPart): List<CaptionWord> {
        val spans = ArrayList<IntArray>()
        var i = part.start
        while (i < part.end) {
            while (i < part.end && text[i].isWhitespace()) i++
            if (i >= part.end) break
            val s = i
            while (i < part.end && !text[i].isWhitespace()) i++
            spans += intArrayOf(s, i)
        }
        if (spans.isEmpty()) return emptyList()
        val weights = spans.mapIndexed { n, (s, e) ->
            val letters = (s until e).count { text[it].isLetterOrDigit() }
            val last = trailing(text, e)
            val pause = when {
                n == spans.lastIndex -> 0
                last in END -> 6
                last == ',' || last == ';' || last == ':' || last == '—' || last == '–' -> 3
                else -> 0
            }
            letters + WORD_GAP + pause
        }
        val total = weights.sum().coerceAtLeast(1)
        val speak = maxOf(part.ms - TAIL_MS, part.ms * 6 / 10).coerceAtLeast(0)
        var acc = 0
        return spans.mapIndexed { n, (s, e) ->
            val at = (speak.toLong() * acc / total).toInt()
            acc += weights[n]
            CaptionWord(s, e, at)
        }
    }

    /**
     * Splits [text] into pages of at most [maxChars] (two short lines on screen), breaking after whole
     * sentences when it can, else after a comma, else between words. Returns [start, end) ranges.
     */
    fun pages(text: String, maxChars: Int): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = skipSpace(text, 0)
        while (start < text.length) {
            if (text.length - start <= maxChars) {
                out += start until text.length
                break
            }
            val limit = start + maxChars
            val cut = lastBreak(text, start, limit) { c -> c in END }
                ?: lastBreak(text, start, limit) { c -> c == ',' || c == ';' || c == ':' }
                ?: lastBreak(text, start, limit) { true }
                ?: nextSpace(text, limit) // one very long word: let it run over
            out += start until cut
            start = skipSpace(text, cut)
        }
        if (out.isEmpty()) out += 0 until 0
        return out
    }

    /** The end (exclusive) of the last word ending in a [mark] (or any word) that fits before [limit]; null if none. */
    private inline fun lastBreak(text: String, start: Int, limit: Int, mark: (Char) -> Boolean): Int? {
        var i = minOf(limit, text.length)
        while (i > start + MIN_PAGE) {
            val atWordEnd = i == text.length || text[i].isWhitespace()
            if (atWordEnd && !text[i - 1].isWhitespace() && mark(trailing(text, i))) return i
            i--
        }
        return null
    }

    /** The last character of a word, looking through closing quotes ("…said Bo." → '.'). */
    private fun trailing(text: String, end: Int): Char {
        var i = end - 1
        while (i > 0 && text[i] in QUOTES) i--
        return text[i]
    }

    private fun nextSpace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && !text[i].isWhitespace()) i++
        return i
    }

    private fun skipSpace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    /** Index of the page holding character [pos] (the last page once [pos] is past the end). */
    fun pageAt(pages: List<IntRange>, pos: Int): Int {
        for ((n, p) in pages.withIndex()) if (pos <= p.last) return n
        return pages.lastIndex.coerceAtLeast(0)
    }

    /** How long to leave a finished line up: long enough for a grown-up to finish reading it. */
    fun lingerMs(text: String): Long = (900L + text.length * 18L).coerceIn(1500L, 3200L)

    /** A family voice message, in the language of the frame. */
    fun messageFrom(from: String, german: Boolean): String =
        if (german) "Eine Sprachnachricht von $from" else "A voice message from $from"

    private const val WORD_GAP = 2
    private const val MIN_PAGE = 12
    /** make_voice pads every clip with 80 ms of silence and leaves a little breath at the end. */
    private const val TAIL_MS = 160
    private const val QUOTES = "\"'”“„«»’"
}
