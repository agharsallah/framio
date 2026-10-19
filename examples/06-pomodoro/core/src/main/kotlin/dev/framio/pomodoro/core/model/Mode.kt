package dev.framio.pomodoro.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * A mode bundles a [rhythm], a [ritual] and an [environment] (PLAN §4). Built-in presets are read-only;
 * "Duplicate & edit" makes your own with [duplicate].
 *
 * @param accent index into the app's 8-colour accent palette (0..7)
 */
@Serializable
public data class Mode(
    @SerialName("id") val id: ModeId,
    @SerialName("name") val name: String,
    @SerialName("accent") val accent: Int,
    @SerialName("rhythm") val rhythm: Rhythm,
    @SerialName("ritual") val ritual: Ritual,
    @SerialName("environment") val environment: Environment,
    @SerialName("builtIn") val builtIn: Boolean = false,
) {
    /** An editable copy: a new id, your own (not built in). */
    public fun duplicate(newId: ModeId, newName: String = "$name copy"): Mode = copy(id = newId, name = newName, builtIn = false)

    public companion object {
        /** Accents come from a palette of this size. */
        public const val ACCENTS: Int = 8
    }
}

/** How focus and breaks follow each other. */
@Serializable
public sealed interface Rhythm {
    /** Start the break as soon as focus ends; the wrap-up then shows on the break screen. */
    public val autoBreak: Boolean

    /** Longest pause before the app offers to end the session; null = no limit. */
    public val pauseCap: Duration?

    /**
     * Fixed blocks (Classic 25/5). A long break replaces every [longEvery]th short break; 0 = never.
     * [autoFocus] starts the next block when a break ends instead of waiting on "Ready?".
     */
    @Serializable
    @SerialName("fixed")
    public data class Fixed(
        @SerialName("focus") val focus: Duration = 25.minutes,
        @SerialName("short") val short: Duration = 5.minutes,
        @SerialName("long") val long: Duration = 20.minutes,
        @SerialName("longEvery") val longEvery: Int = 4,
        @SerialName("autoBreak") override val autoBreak: Boolean = true,
        @SerialName("autoFocus") val autoFocus: Boolean = false,
        @SerialName("pauseCap") override val pauseCap: Duration? = null,
    ) : Rhythm

    /**
     * Flowtime: focus counts up until you stop at a natural point; the break follows from how long you
     * focused ([tiers], Smits et al. 2025). [nudgeAfter]: one gentle glow and "a real break helps" on screen, no sound.
     */
    @Serializable
    @SerialName("flow")
    public data class Flow(
        @SerialName("tiers") val tiers: List<Tier> = SmitsTiers,
        @SerialName("nudgeAfter") val nudgeAfter: Duration? = 90.minutes,
        @SerialName("autoBreak") override val autoBreak: Boolean = true,
        @SerialName("pauseCap") override val pauseCap: Duration? = null,
    ) : Rhythm
}

/** Focus up to and including [upTo] earns a [rest]-long break; null [upTo] = anything longer. */
@Serializable
public data class Tier(
    @SerialName("upTo") val upTo: Duration?,
    @SerialName("rest") val rest: Duration,
)

/** Smits 2025: ≤ 25 min of work → 5 min, ≤ 50 → 8, more → 10. */
public val SmitsTiers: List<Tier> = listOf(
    Tier(25.minutes, 5.minutes),
    Tier(50.minutes, 8.minutes),
    Tier(null, 10.minutes),
)

/**
 * What happens before a block starts. A ritual with an if-then line or a checklist has its own screen
 * ([hasScreen]); a [skippable] one can be skipped once it has been through once (the app remembers the ticks).
 */
@Serializable
public data class Ritual(
    @SerialName("intention") val intention: IntentionRule = IntentionRule.Optional,
    @SerialName("ifThen") val ifThen: Boolean = false,
    @SerialName("checklist") val checklist: List<ChecklistItem> = emptyList(),
    @SerialName("resumeNote") val showResumeNote: Boolean = true,
    @SerialName("skippable") val skippable: Boolean = true,
) {
    /** Whether Begin goes through the ritual screen (otherwise Home's task choice is the whole ritual). */
    public val hasScreen: Boolean get() = ifThen || checklist.isNotEmpty()
}

@Serializable
public enum class IntentionRule {
    @SerialName("required") Required,
    @SerialName("optional") Optional,
    @SerialName("off") Off,
}

/** A ritual checkbox. Built-in items have well-known [id]s the app maps to translated labels. */
@Serializable
public data class ChecklistItem(
    @SerialName("id") val id: String,
    @SerialName("label") val label: String,
)

/** How much the screen asks of you during focus. */
@Serializable
public data class Environment(
    @SerialName("display") val display: Display = Display.Full,
    @SerialName("face") val face: Face = Face.Ring,
    @SerialName("seconds") val showSeconds: Boolean = true,
    @SerialName("touch") val touch: TouchPolicy = TouchPolicy.Open,
    @SerialName("knock") val knockToPeek: Boolean = false,
    @SerialName("cues") val cues: CueStyle = CueStyle.Soft,
    @SerialName("soundscape") val soundscape: Soundscape? = null,
    /** A 3-second glow every so often ("still on it?"), never asking for input; null = off. */
    @SerialName("checkIn") val checkIn: Duration? = null,
    /** At the end of a Calm or Blackout block the screen fades in like a sunrise. */
    @SerialName("sunrise") val sunrise: Boolean = false,
    @SerialName("tone") val tone: CopyTone = CopyTone.Neutral,
)

@Serializable
public enum class Display {
    /** The timer face at full size, normal brightness. */
    @SerialName("full") Full,

    /** Near-black, one thin arc and "38 min" at low luminance, one update a minute. */
    @SerialName("calm") Calm,

    /** Backlight off (root); the screen is pure black. */
    @SerialName("blackout") Blackout,
}

@Serializable
public enum class Face {
    @SerialName("disc") Disc,
    @SerialName("ring") Ring,
    @SerialName("horizon") Horizon,
    @SerialName("digits") Digits,
}

@Serializable
public sealed interface TouchPolicy {
    /** Taps work as usual. */
    @Serializable
    @SerialName("open")
    public data object Open : TouchPolicy

    /** Single taps do nothing; holding for [hold] opens the panel (Park a thought · Pause · End). */
    @Serializable
    @SerialName("hold")
    public data class HoldToReveal(@SerialName("hold") val hold: Duration = 1200.milliseconds) : TouchPolicy
}

@Serializable
public enum class CueStyle {
    @SerialName("off") Off,

    /** One soft two-note chime. */
    @SerialName("soft") Soft,

    /** The chime starts at −24 dB and rises over 8 s. */
    @SerialName("rising") Rising,
}

@Serializable
public enum class Soundscape {
    @SerialName("rain") Rain,
    @SerialName("wind") Wind,
    @SerialName("pink") Pink,
    @SerialName("brown") Brown,
}

/** Gentle start uses warmer copy (self-compassion, PLAN §3 principle 10). */
@Serializable
public enum class CopyTone {
    @SerialName("neutral") Neutral,
    @SerialName("warm") Warm,
}
