package dev.framio.meadow.worlds.quiet

import dev.framio.meadow.core.Friend
import dev.framio.meadow.core.Txt

/**
 * A friend on a story page. [look] is a FriendView mood ("idle", "happy", "wave", "sleep") or a
 * feeling id ("sad", "scared", …, see [Feeling]). [x] is the centre and [y] the feet, as fractions of
 * the picture; [h] is the height as a fraction of the picture's height.
 */
data class Actor(val friend: Friend, val look: String, val x: Float, val y: Float = 0.93f, val h: Float = 0.42f)

/**
 * A thing on a page: sprite, centre x, bottom y, height (fractions of the picture), tilt in degrees.
 * Things on the ground stand behind the friends, things up in the air in front.
 */
data class Prop(val sprite: String, val x: Float, val y: Float, val h: Float, val tilt: Float = 0f)

/** One picture: a backdrop, friends and props. Pages with [minLevel] above the child's level are skipped. */
data class Page(
    val bg: String,
    val actors: List<Actor>,
    val props: List<Prop> = emptyList(),
    val rain: Boolean = false,
    val minLevel: Int = 0,
)

/** A picture story. Narration: stories_<id>_<n> (n = 1-based index into [pages]), title stories_title_<id>. */
data class Story(val id: String, val title: Txt, val cover: Actor, val coverBg: String, val pages: List<Page>) {
    fun narration(pageIndex: Int) = "stories_${id}_${pageIndex + 1}"
    val titleLine get() = "stories_title_$id"

    /** The pages a child at [level] sees (the original indexes, so narration ids stay fixed). */
    fun pagesFor(level: Int): List<Int> = pages.indices.filter { pages[it].minLevel <= level }
}

private val P = Friend.Pip
private val T = Friend.Tuka
private val B = Friend.Bo
private val M = Friend.Moss
private val L = Friend.Luma

/** The four bedtime picture stories, each gently teaching one thing. Texts: voice/<lang>/quiet.tsv. */
object StoryBook {
    val pipSleep = Story(
        "pip_sleep", Txt("Pip Can't Sleep", "Pip kann nicht schlafen"), Actor(P, "sleepy", 0.5f), "bg_night",
        listOf(
            Page("bg_night", listOf(Actor(P, "idle", 0.56f, 0.83f, 0.34f)), listOf(Prop("qt_nestbed", 0.56f, 0.97f, 0.34f))),
            Page("bg_night", listOf(Actor(P, "happy", 0.46f, 0.9f, 0.4f)), listOf(Prop("qt_nestbed", 0.74f, 0.95f, 0.28f))),
            Page("bg_night", listOf(Actor(P, "idle", 0.36f, 0.93f, 0.36f), Actor(L, "wave", 0.64f, 0.93f, 0.46f))),
            Page("bg_night", listOf(Actor(P, "idle", 0.32f, 0.93f, 0.34f), Actor(M, "happy", 0.66f, 0.93f, 0.4f)), listOf(Prop("ob_milk", 0.48f, 0.9f, 0.17f))),
            Page("bg_night", listOf(Actor(P, "happy", 0.32f, 0.93f, 0.34f), Actor(B, "happy", 0.66f, 0.93f, 0.48f)), listOf(Prop("ob_toothbrush", 0.49f, 0.62f, 0.12f, -20f))),
            Page("bg_night", listOf(Actor(P, "calm", 0.33f, 0.93f, 0.34f), Actor(T, "idle", 0.64f, 0.93f, 0.44f)), listOf(Prop("ob_book", 0.48f, 0.9f, 0.15f)), minLevel = 2),
            Page("bg_night", listOf(Actor(P, "calm", 0.36f, 0.93f, 0.34f), Actor(L, "idle", 0.63f, 0.93f, 0.46f))),
            Page("bg_night", listOf(Actor(P, "sleepy", 0.52f, 0.83f, 0.34f)), listOf(Prop("qt_nestbed", 0.52f, 0.97f, 0.34f))),
            Page(
                "bg_qt_sky",
                listOf(Actor(P, "sleep", 0.5f, 0.83f, 0.32f), Actor(T, "sleep", 0.2f, 0.93f, 0.3f), Actor(M, "sleep", 0.34f, 0.95f, 0.22f), Actor(B, "sleep", 0.8f, 0.93f, 0.32f)),
                listOf(Prop("qt_nestbed", 0.5f, 0.97f, 0.32f)),
            ),
        ),
    )

    val tukaShares = Story(
        "tuka_share", Txt("Tuka Shares Her Berries", "Tuka teilt ihre Beeren"), Actor(T, "happy", 0.5f), "bg_meadow",
        listOf(
            Page("bg_meadow", listOf(Actor(T, "idle", 0.36f, 0.93f, 0.44f)), listOf(Prop("qt_bush", 0.66f, 0.93f, 0.42f))),
            Page("bg_meadow", listOf(Actor(T, "happy", 0.36f, 0.93f, 0.44f)), listOf(Prop("qt_bush", 0.7f, 0.93f, 0.4f), Prop("qt_basket", 0.53f, 0.95f, 0.2f))),
            Page("bg_meadow", listOf(Actor(T, "happy", 0.44f, 0.93f, 0.46f)), listOf(Prop("qt_basket", 0.62f, 0.95f, 0.22f))),
            Page("bg_meadow", listOf(Actor(T, "idle", 0.3f, 0.93f, 0.42f), Actor(B, "sad", 0.7f, 0.93f, 0.46f)), listOf(Prop("qt_basket", 0.44f, 0.95f, 0.2f))),
            Page("bg_meadow", listOf(Actor(T, "idle", 0.36f, 0.93f, 0.42f), Actor(B, "sad", 0.74f, 0.93f, 0.46f)), listOf(Prop("qt_basket", 0.53f, 0.95f, 0.2f)), minLevel = 2),
            Page("bg_meadow", listOf(Actor(T, "happy", 0.34f, 0.93f, 0.42f), Actor(B, "happy", 0.7f, 0.93f, 0.46f)), listOf(Prop("qt_basket", 0.52f, 0.95f, 0.2f))),
            Page(
                "bg_meadow",
                listOf(Actor(P, "happy", 0.16f, 0.93f, 0.3f), Actor(T, "happy", 0.36f, 0.93f, 0.4f), Actor(M, "happy", 0.66f, 0.93f, 0.32f), Actor(B, "happy", 0.86f, 0.93f, 0.4f)),
                listOf(Prop("qt_basket", 0.51f, 0.95f, 0.18f)),
            ),
            Page(
                "bg_meadow",
                listOf(Actor(P, "happy", 0.2f, 0.93f, 0.28f), Actor(T, "proud", 0.5f, 0.93f, 0.48f), Actor(B, "happy", 0.8f, 0.93f, 0.38f)),
                listOf(Prop("ob_heart", 0.5f, 0.38f, 0.14f)),
            ),
        ),
    )

    val mossSlow = Story(
        "moss_slow", Txt("Moss Takes His Time", "Moos lässt sich Zeit"), Actor(M, "calm", 0.5f), "bg_garden",
        listOf(
            Page("bg_garden", listOf(Actor(M, "idle", 0.5f, 0.93f, 0.42f))),
            Page("bg_garden", listOf(Actor(P, "wave", 0.32f, 0.93f, 0.34f), Actor(M, "idle", 0.64f, 0.93f, 0.38f))),
            Page(
                "bg_path",
                listOf(Actor(M, "idle", 0.16f, 0.95f, 0.3f), Actor(P, "happy", 0.56f, 0.55f, 0.26f), Actor(T, "happy", 0.72f, 0.93f, 0.38f), Actor(B, "happy", 0.88f, 0.93f, 0.4f)),
            ),
            Page("bg_path", listOf(Actor(M, "happy", 0.4f, 0.93f, 0.4f)), listOf(Prop("ob_snail", 0.68f, 0.95f, 0.18f))),
            Page("bg_path", listOf(Actor(M, "surprised", 0.38f, 0.93f, 0.4f)), listOf(Prop("ob_butterfly", 0.6f, 0.45f, 0.16f, 10f), Prop("ob_flower", 0.72f, 0.95f, 0.22f))),
            Page(
                "bg_qt_dusk",
                listOf(Actor(P, "idle", 0.42f, 0.93f, 0.3f), Actor(T, "idle", 0.6f, 0.93f, 0.4f), Actor(B, "idle", 0.8f, 0.93f, 0.42f)),
                minLevel = 2,
            ),
            Page(
                "bg_qt_dusk",
                listOf(Actor(M, "happy", 0.22f, 0.95f, 0.36f), Actor(P, "happy", 0.5f, 0.93f, 0.3f), Actor(T, "happy", 0.67f, 0.93f, 0.4f), Actor(B, "happy", 0.85f, 0.93f, 0.42f)),
            ),
            Page(
                "bg_qt_dusk",
                listOf(Actor(M, "proud", 0.36f, 0.95f, 0.4f), Actor(T, "surprised", 0.62f, 0.93f, 0.42f), Actor(P, "surprised", 0.82f, 0.93f, 0.3f)),
                listOf(Prop("ob_snail", 0.14f, 0.95f, 0.13f)),
            ),
            Page(
                "bg_qt_dusk",
                listOf(Actor(P, "calm", 0.2f, 0.93f, 0.28f), Actor(M, "calm", 0.4f, 0.95f, 0.34f), Actor(T, "calm", 0.6f, 0.93f, 0.38f), Actor(B, "calm", 0.8f, 0.93f, 0.4f)),
            ),
        ),
    )

    val boRain = Story(
        "bo_rain", Txt("Bo's Big Rain", "Bos großer Regen"), Actor(B, "surprised", 0.5f), "bg_qt_rain",
        listOf(
            Page("bg_meadow", listOf(Actor(B, "happy", 0.42f, 0.93f, 0.44f)), listOf(Prop("ob_ball", 0.66f, 0.95f, 0.14f), Prop("qt_raincloud", 0.74f, 0.36f, 0.24f))),
            Page("bg_qt_rain", listOf(Actor(B, "surprised", 0.44f, 0.93f, 0.46f)), listOf(Prop("qt_raincloud", 0.5f, 0.34f, 0.3f)), rain = true),
            Page("bg_qt_rain", listOf(Actor(B, "scared", 0.46f, 0.93f, 0.48f)), rain = true),
            Page("bg_qt_rain", listOf(Actor(B, "scared", 0.54f, 0.93f, 0.46f), Actor(L, "idle", 0.76f, 0.93f, 0.42f)), rain = true),
            Page("bg_qt_rain", listOf(Actor(B, "calm", 0.56f, 0.93f, 0.46f), Actor(L, "idle", 0.78f, 0.93f, 0.42f)), listOf(Prop("ob_flower", 0.38f, 0.95f, 0.2f)), rain = true),
            Page("bg_qt_rain", listOf(Actor(B, "calm", 0.56f, 0.93f, 0.46f), Actor(L, "idle", 0.78f, 0.93f, 0.42f)), rain = true, minLevel = 2),
            Page("bg_qt_rain", listOf(Actor(B, "calm", 0.58f, 0.93f, 0.46f), Actor(L, "happy", 0.76f, 0.93f, 0.42f)), rain = true),
            Page("bg_qt_rain", listOf(Actor(B, "surprised", 0.56f, 0.93f, 0.46f), Actor(L, "idle", 0.78f, 0.93f, 0.42f)), listOf(Prop("ob_sun", 0.26f, 0.42f, 0.26f))),
            Page("bg_meadow", listOf(Actor(B, "happy", 0.4f, 0.93f, 0.46f), Actor(L, "happy", 0.62f, 0.93f, 0.42f)), listOf(Prop("ob_rainbow", 0.5f, 0.42f, 0.36f))),
        ),
    )

    val all = listOf(pipSleep, tukaShares, mossSlow, boRain)
}
