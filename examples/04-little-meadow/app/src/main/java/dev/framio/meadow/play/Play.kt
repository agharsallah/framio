package dev.framio.meadow.play

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import dev.framio.meadow.AppState
import dev.framio.meadow.audio.Sound
import dev.framio.meadow.audio.Track
import dev.framio.meadow.core.Friend
import dev.framio.meadow.core.Lang
import dev.framio.meadow.core.Txt
import dev.framio.meadow.ui.Mood

/**
 * One activity. [content] fills the area below the top bar; [PlayScreen] supplies the back
 * button, progress dots, the "say it again" button, the friend in the corner and the ending.
 */
class Play(
    /** Stable id: used by adb (`--es play <id>`), the dashboard, progress and voice clip names. */
    val id: String,
    val title: Txt,
    val friend: Friend,
    /** Sprite for its card in the world, e.g. "ob_apple". */
    val icon: String,
    /** Youngest age it is shown to (2..5). */
    val minAge: Int = 2,
    /** For grown-ups (dashboard): what it practises. */
    val about: Txt = Txt(""),
    /** Spoken when it opens, before the first prompt. */
    val intro: String? = null,
    /** Background sprite; null = the world's. */
    val backdrop: String? = null,
    /** Background music; null = silence (for anything that makes its own sounds). */
    val music: Track? = Track.Meadow,
    val showFriend: Boolean = true,
    /** The corner friend doesn't breathe (screens with a GL view: every Compose redraw is a full-screen composite). */
    val stillFriend: Boolean = false,
    val showDots: Boolean = true,
    /** Allowed during bedtime hours (the quiet corner). */
    val quiet: Boolean = false,
    /** Counts as play time (false for brushing teeth, the night light, breathing). */
    val countsAsScreenTime: Boolean = true,
    /** Show what is said in the top bar (the grown-up's setting permitting); false where any light or text would intrude (the night light). */
    val captions: Boolean = true,
    val content: @Composable (PlayScope) -> Unit,
)

/** A place in the meadow: a friend's corner with a few activities. */
class World(
    val id: String,
    val title: Txt,
    val friend: Friend,
    val color: Color,
    /** Full-screen background sprite, e.g. "bg_nest". */
    val backdrop: String,
    /** Small sprites shown on its card on the home screen. */
    val props: List<String>,
    /** Spoken when the child opens it ("Let's count with Pip!"). */
    val intro: String,
    val quiet: Boolean = false,
    val plays: List<Play>,
)

/**
 * What an activity can do. Every method is safe to call from the main thread at any time.
 *
 * Calm-design rules every activity follows:
 *  - Everything is spoken: never rely on reading. Call [prompt] with the instruction, it is repeated
 *    when the child taps the ear button or stops touching the screen for a while.
 *  - No failure: a wrong answer gets [notQuite] (a soft sound, a gentle line, a small tilt), and
 *    after two tries a visual [hint] points at the answer. No timers, no lives, no scores.
 *  - Report each answered round with [roundDone] (it adapts [level]), then [finish] after 4–6
 *    rounds: the host celebrates, awards a sticker and offers "again" or "back".
 */
@Stable
interface PlayScope {
    val play: Play
    val app: AppState
    val age: Int
    val lang: Lang
    /** 0..4, adapts to how the child is doing (Difficulty). Read it when building a new round. */
    val level: Int
    val reducedMotion: Boolean
    /** True when the child seems stuck: draw a hint (Modifier.hintPulse) on the answer. */
    val hint: Boolean

    /** The friend in the corner. Set Happy after a right answer; it goes back to Idle by itself. */
    var friendMood: Mood

    fun say(vararg ids: String)
    suspend fun sayAndWait(vararg ids: String)

    /** Sets the instruction for this round and says it. */
    fun prompt(vararg ids: String)
    /** Says the current instruction again. */
    fun repeatPrompt()
    /** No instruction for now (brushing, a story reading itself): no repeats, no idle hints. Doesn't stop the voice. */
    fun clearPrompt()

    /** A short random praise ("Well done!") with the Good sound and a happy hop. */
    fun praise()
    /** Same as [praise] but waits until it is spoken. */
    suspend fun praiseAndWait()
    /** The gentle "not quite": soft sound + encouraging line; the second time switches the hint on. */
    fun notQuite()

    fun showHint(on: Boolean = true)
    fun sfx(s: Sound, volume: Float = 1f, rate: Float = 1f)

    /** One round answered. [firstTry] false = needed help or several tries. Returns the new level. */
    fun roundDone(firstTry: Boolean): Int
    /** Progress dots at the top. */
    fun rounds(done: Int, total: Int)
    /** For the dashboard's skills view, e.g. "count:7", "letter:b", "color:blue". */
    fun practised(skill: String)
    /** Ends the activity: celebration, sticker, "again" or "back". */
    fun finish()
}
