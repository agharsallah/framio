package dev.framio.meadow.worlds.quiet

import dev.framio.meadow.core.Friend
import kotlin.random.Random

/** Feelings the friends can show (sprites qt_<friend>_<id>, see tools/render3d/models/quiet.py). */
enum class Feeling(val id: String, val positive: Boolean) {
    Happy("happy", true),
    Sad("sad", false),
    Angry("angry", false),
    Scared("scared", false),
    Surprised("surprised", true),
    Sleepy("sleepy", false),
    Proud("proud", true),
    Calm("calm", true);

    companion object {
        fun of(id: String): Feeling? = entries.firstOrNull { it.id == id }
    }
}

/** The friends who have feeling faces (Luma hosts and keeps her own poses). */
val FEELING_FRIENDS = listOf(Friend.Pip, Friend.Tuka, Friend.Bo, Friend.Moss)

fun feelingSprite(friend: Friend, feeling: Feeling) = "qt_${friend.id}_${feeling.id}"

/** Which feelings a child meets at [level]: the four big ones first, the subtler ones later. */
fun feelingsFor(level: Int): List<Feeling> = when {
    level <= 1 -> listOf(Feeling.Happy, Feeling.Sad, Feeling.Angry, Feeling.Scared)
    level == 2 -> listOf(Feeling.Happy, Feeling.Sad, Feeling.Angry, Feeling.Scared, Feeling.Surprised, Feeling.Sleepy)
    else -> Feeling.entries.toList()
}

/** The faces to explore first (at most six on screen). */
fun exploreFeelings(level: Int): List<Feeling> = feelingsFor(level).let { all ->
    if (all.size <= 6) all else listOf(Feeling.Happy, Feeling.Sad, Feeling.Angry, Feeling.Scared, Feeling.Proud, Feeling.Calm)
}

/** How many different faces to look at before the game starts. */
fun exploreNeeded(level: Int) = if (level <= 1) 3 else 4

/** Pairs a little child could honestly mix up in a story: never offered side by side. */
private val confusable = setOf(
    setOf(Feeling.Sad, Feeling.Angry), setOf(Feeling.Sad, Feeling.Scared), setOf(Feeling.Angry, Feeling.Scared),
    setOf(Feeling.Scared, Feeling.Surprised), setOf(Feeling.Happy, Feeling.Proud), setOf(Feeling.Happy, Feeling.Surprised),
    setOf(Feeling.Calm, Feeling.Sleepy), setOf(Feeling.Sad, Feeling.Sleepy), setOf(Feeling.Proud, Feeling.Calm),
    setOf(Feeling.Happy, Feeling.Calm),
)

fun confusable(a: Feeling, b: Feeling) = setOf(a, b) in confusable

/** Faces to choose from: the answer plus clearly different ones (two at level 0, three above). */
fun feelingChoices(target: Feeling, level: Int, r: Random): List<Feeling> {
    val n = if (level <= 0) 2 else 3
    fun fits(f: Feeling) = f != target && !confusable(f, target)
    // faces the child already knows first, the next level's only to fill up
    val known = feelingsFor(level).filter(::fits).shuffled(r)
    val more = feelingsFor(level + 2).filter { fits(it) && it !in known }.shuffled(r)
    return ((known + more).take(n - 1) + target).shuffled(r)
}

/** Things that help with a hard feeling. Sprite + narration feelings_help_<id>. */
enum class Helper(val id: String, val sprite: String) {
    Hug("hug", "ob_heart"),
    Talk("talk", "qt_talk"),
    Breathe("breathe", "ob_flower"),
    Rest("rest", "qt_pillow"),
    Play("play", "ob_ball"),
}

/** Good helpers for each hard feeling: every one offered is a right answer. */
fun helpersFor(f: Feeling): List<Helper> = when (f) {
    Feeling.Sad -> listOf(Helper.Hug, Helper.Talk, Helper.Play)
    Feeling.Angry -> listOf(Helper.Breathe, Helper.Talk, Helper.Rest)
    Feeling.Scared -> listOf(Helper.Hug, Helper.Talk, Helper.Breathe)
    Feeling.Sleepy -> listOf(Helper.Rest, Helper.Hug, Helper.Breathe)
    else -> emptyList()
}

/**
 * A little picture story: [friend] in a situation that brings a [feeling]. Narration
 * feelings_s_<id> tells it and asks "How does … feel?". [dark] draws the card at dusk.
 */
data class Scenario(val id: String, val friend: Friend, val feeling: Feeling, val props: List<Prop>, val dark: Boolean = false)

val SCENARIOS = listOf(
    Scenario("icecream", Friend.Tuka, Feeling.Sad, listOf(Prop("qt_icecream", 0.72f, 0.94f, 0.22f))),
    Scenario("balloon", Friend.Bo, Feeling.Sad, listOf(Prop("ob_balloon_blue", 0.78f, 0.36f, 0.3f, 12f))),
    Scenario("gift", Friend.Pip, Feeling.Happy, listOf(Prop("ob_gift", 0.74f, 0.94f, 0.28f))),
    Scenario("ball", Friend.Moss, Feeling.Happy, listOf(Prop("ob_ball", 0.76f, 0.94f, 0.2f), Prop("ch_pip_happy", 0.86f, 0.6f, 0.24f))),
    Scenario("tower", Friend.Bo, Feeling.Angry, listOf(Prop("ob_block", 0.68f, 0.95f, 0.16f, 20f), Prop("ob_block", 0.82f, 0.96f, 0.16f, -35f), Prop("ob_block", 0.76f, 0.8f, 0.14f, 60f))),
    Scenario("wait", Friend.Pip, Feeling.Angry, listOf(Prop("ob_drum", 0.76f, 0.94f, 0.26f))),
    Scenario("noise", Friend.Moss, Feeling.Scared, listOf(Prop("ob_moon", 0.78f, 0.38f, 0.22f)), dark = true),
    Scenario("thunder", Friend.Tuka, Feeling.Scared, listOf(Prop("qt_raincloud", 0.72f, 0.4f, 0.26f)), dark = true),
    Scenario("butterfly", Friend.Bo, Feeling.Surprised, listOf(Prop("ob_butterfly", 0.66f, 0.3f, 0.18f, -10f))),
    Scenario("party", Friend.Pip, Feeling.Surprised, listOf(Prop("ob_balloon_pink", 0.74f, 0.5f, 0.3f), Prop("ob_balloon_yellow", 0.86f, 0.56f, 0.3f, 8f))),
    Scenario("yawn", Friend.Tuka, Feeling.Sleepy, listOf(Prop("qt_pillow", 0.76f, 0.95f, 0.2f), Prop("ob_moon", 0.8f, 0.36f, 0.18f)), dark = true),
    Scenario("busy", Friend.Bo, Feeling.Sleepy, listOf(Prop("ob_teddy", 0.76f, 0.95f, 0.26f)), dark = true),
    Scenario("built", Friend.Moss, Feeling.Proud, listOf(Prop("ob_block", 0.76f, 0.95f, 0.15f), Prop("ob_block", 0.76f, 0.73f, 0.15f, 4f), Prop("ob_block", 0.76f, 0.51f, 0.15f, -3f))),
    Scenario("clouds", Friend.Pip, Feeling.Calm, listOf(Prop("ob_cloud", 0.72f, 0.34f, 0.2f), Prop("ob_flower", 0.82f, 0.95f, 0.2f))),
)

/**
 * The game's rounds: [count] stories with feelings the child knows at [level], never the same
 * feeling or friend twice in a row, the big feelings (happy, sad, angry, scared) first.
 */
fun pickScenarios(level: Int, count: Int, r: Random): List<Scenario> {
    val known = feelingsFor(level).toSet()
    val pool = SCENARIOS.filter { it.feeling in known }.shuffled(r)
    val out = mutableListOf<Scenario>()
    val left = pool.toMutableList()
    while (out.size < count && left.isNotEmpty()) {
        val prev = out.lastOrNull()
        val next = left.firstOrNull { prev == null || (it.feeling != prev.feeling && it.friend != prev.friend) }
            ?: left.firstOrNull { prev == null || it.feeling != prev.feeling }
            ?: left.first()
        out += next
        left.remove(next)
    }
    return out
}

/** Every voice id the feelings game can play. */
internal fun feelingsVoiceIds(): List<String> =
    listOf("feelings_intro", "feelings_explore", "feelings_ready", "feelings_whathelps") +
        Feeling.entries.flatMap { listOf("feelings_about_${it.id}", "feelings_name_${it.id}", "feelings_yes_${it.id}") } +
        Feeling.entries.filter { it.positive }.map { "feelings_share_${it.id}" } +
        Helper.entries.map { "feelings_help_${it.id}" } +
        FEELING_FRIENDS.map { "feelings_better_${it.id}" } +
        SCENARIOS.map { "feelings_s_${it.id}" }
