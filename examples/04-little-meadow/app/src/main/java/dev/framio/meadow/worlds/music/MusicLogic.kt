package dev.framio.meadow.worlds.music

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

// Pure logic for Bo's Music Corner (no Android imports: unit-tested on the JVM).

// ---------------------------------------------------------------- Xylophone

/** The eight bars, C5..C6 (MIDI), longest first. */
internal val XYLO_MIDI = intArrayOf(72, 74, 76, 77, 79, 81, 83, 84)

/** Bar index by note name (C5 = "C", C6 = "C'"). */
private val BAR = mapOf("C" to 0, "D" to 1, "E" to 2, "F" to 3, "G" to 4, "A" to 5, "B" to 6, "C'" to 7)

/** One note of a song: which bar, and how long it lasts in beats (a quarter note = 1). */
internal data class Note(val bar: Int, val beats: Float)

internal class Song(val id: String, val icon: String, val notes: List<Note>) {
    val voice get() = "xylo_song_$id"
}

/** "C C G G" → notes, with an optional duration after a colon ("G:2", "C:0.5"); default one beat. */
internal fun parseNotes(src: String): List<Note> = src.trim().split(Regex("\\s+")).map { tok ->
    val parts = tok.split(":")
    Note(BAR[parts[0]] ?: error("unknown note $tok"), parts.getOrNull(1)?.toFloat() ?: 1f)
}

/**
 * Five public-domain songs, all within one octave of C major. The child plays them note by note;
 * the durations are only used when the song plays itself back at the end.
 */
internal val SONGS: List<Song> = listOf(
    Song(
        "twinkle", "ob_star",
        parseNotes("C C G G A A G:2 F F E E D D C:2"),
    ),
    Song(
        "mary", "mu_lamb",
        parseNotes("E D C D E E E:2 D D D:2 E G G:2 E D C D E E E E D D E D C:4"),
    ),
    Song(
        "row", "ob_boat",
        // 6/8: row, row, row your boat, gently down the stream, merrily ×4, life is but a dream
        parseNotes(
            "C:1.5 C:1.5 C:1 D:0.5 E:1.5 E:1 D:0.5 E:1 F:0.5 G:3 " +
                "C':0.5 C':0.5 C':0.5 G:0.5 G:0.5 G:0.5 E:0.5 E:0.5 E:0.5 C:0.5 C:0.5 C:0.5 " +
                "G:1 F:0.5 E:1 D:0.5 C:3",
        ),
    ),
    Song(
        "jacques", "mu_clock",
        // Frère Jacques / Bruder Jakob; "ding dang dong" (C G, C) is played an octave up as C' G C' to stay on the bars
        parseNotes(
            "C D E C C D E C E F G:2 E F G:2 " +
                "G:0.5 A:0.5 G:0.5 F:0.5 E C G:0.5 A:0.5 G:0.5 F:0.5 E C " +
                "C' G C':2 C' G C':2",
        ),
    ),
    Song(
        "buns", "mu_bun",
        parseNotes("E D C:2 E D C:2 C:0.5 C:0.5 C:0.5 C:0.5 D:0.5 D:0.5 D:0.5 D:0.5 E D C:2"),
    ),
)

/** How many songs finish the activity (free play never ends by itself). */
internal const val XYLO_SONGS_TO_FINISH = 3

/** When each note starts in the playback, in ms from the first. */
internal fun noteTimes(song: Song, beatMs: Int): List<Long> {
    var t = 0f
    return song.notes.map { n -> (t * beatMs).toLong().also { t += n.beats } }
}

/** Lengths of the bars on screen relative to the longest, as in the 3D model (4.4 → 3.0). */
internal fun barLength(i: Int): Float = (4.4f - 1.4f * i / 7f) / 4.4f

// ---------------------------------------------------------------- Bubble notes

/** C major pentatonic over two octaves: any two notes sound good together. */
internal val PENTATONIC = intArrayOf(72, 74, 76, 79, 81, 84, 86, 88)

/** Bigger bubbles sing lower. [size] 0..1 (smallest..biggest). */
internal fun bubbleNote(size: Float): Int {
    val i = ((1f - size.coerceIn(0f, 1f)) * (PENTATONIC.size - 1) + 0.5f).toInt()
    return PENTATONIC[i.coerceIn(0, PENTATONIC.size - 1)]
}

internal val BUBBLE_THINGS = listOf("star", "heart", "duck", "fish", "flower", "butterfly", "ball", "apple", "leaf", "snail")

internal const val BUBBLE_POPS_TO_FINISH = 30

/** One bubble's life: everything derives from its index, so nothing needs to be stored per frame. */
internal class Bubble(
    val index: Int,
    val bornMs: Float,
    /** Horizontal centre as a fraction of the width. */
    val x: Float,
    /** Radius in dp, and 0..1 of the size range. */
    val radius: Float,
    val size: Float,
    /** Rise speed in dp per second. */
    val speed: Float,
    val swayDp: Float,
    val swayPeriodMs: Float,
    val phase: Float,
    val hue: Int,
    /** A little object riding inside, or null. */
    val thing: String?,
) {
    /** Centre at time [tMs] in a [w]×[h] dp area (it rises from below the bottom edge). */
    fun at(tMs: Float, w: Float, h: Float): Pair<Float, Float> {
        val life = tMs - bornMs
        val y = h + radius - life * speed / 1000f
        val x = x * w + sin(2 * PI * life / swayPeriodMs + phase).toFloat() * swayDp
        return x to y
    }

    fun gone(tMs: Float, h: Float): Boolean = tMs - bornMs > (h + 2 * radius) / speed * 1000f
}

/** Time between new bubbles: slower for the youngest so the screen never gets busy (at most ~8 at once). */
internal fun bubbleGapMs(level: Int): Float = if (level <= 1) 1700f else 1500f

internal fun bubble(index: Int, level: Int): Bubble {
    val r = Random(index * 7919 + 17)
    val size = r.nextFloat()
    val radius = 52f + size * 36f
    val thing = if (index % 4 == 2) BUBBLE_THINGS[(index / 4) % BUBBLE_THINGS.size] else null
    return Bubble(
        index = index,
        bornMs = index * bubbleGapMs(level) + r.nextFloat() * 300f,
        // the friend sits bottom-left: start a little to the right of it
        x = 0.24f + 0.7f * ((index * 0.618034f + r.nextFloat() * 0.25f) % 1f),
        radius = radius,
        size = size,
        speed = 72f + r.nextFloat() * 26f - size * 8f,
        swayDp = 14f + r.nextFloat() * 18f,
        swayPeriodMs = 3200f + r.nextFloat() * 1800f,
        phase = r.nextFloat() * 6.28f,
        hue = index % 4,
        thing = thing,
    )
}

/** The bubbles that may be on screen at [tMs]: born, not yet floated off the top. */
internal fun liveRange(tMs: Float, level: Int, h: Float): IntRange {
    val gap = bubbleGapMs(level)
    val newest = ((tMs) / gap).toInt()
    val maxLife = (h + 2 * 88f) / 50f * 1000f
    val oldest = ((tMs - maxLife) / gap).toInt().coerceAtLeast(0) - 1
    return oldest.coerceAtLeast(0)..newest
}

// ---------------------------------------------------------------- Echo drums

/** Bo's patterns get longer as the child gets better: 2 hits for the youngest, 5 at the top. */
internal fun echoLength(level: Int): Int = when (level) {
    0 -> 2
    1 -> 3
    2 -> 3
    3 -> 4
    else -> 5
}

/** Two drums for the youngest, the bell joins from level 2. */
internal fun echoDrums(level: Int): Int = if (level < 2) 2 else 3

/**
 * A pattern of [length] hits on [drums] instruments: never three of the same in a row and always
 * at least two different ones (so it really is a pattern to remember).
 */
internal fun echoPattern(length: Int, drums: Int, r: Random): List<Int> {
    while (true) {
        val p = ArrayList<Int>(length)
        for (i in 0 until length) {
            var d = r.nextInt(drums)
            if (i >= 2 && p[i - 1] == p[i - 2] && d == p[i - 1]) d = (d + 1 + r.nextInt(drums - 1)) % drums
            p += d
        }
        if (p.distinct().size >= 2) return p
    }
}

internal enum class EchoStep { Next, Done, Wrong }

/** The child tapped [drum] as hit number [pos] (0-based) of [pattern]. Only the order matters. */
internal fun echoCheck(pattern: List<Int>, pos: Int, drum: Int): EchoStep = when {
    pos >= pattern.size || pattern[pos] != drum -> EchoStep.Wrong
    pos == pattern.size - 1 -> EchoStep.Done
    else -> EchoStep.Next
}
