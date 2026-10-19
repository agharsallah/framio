package dev.framio.pomodoro.core.model

import kotlin.time.Duration.Companion.minutes

/** The four built-in modes of PLAN §4. Read-only; duplicate one to edit it. */
public object Presets {
    public val CLASSIC_ID: ModeId = ModeId("classic")
    public val DEEP_ID: ModeId = ModeId("deep")
    public val FLOW_ID: ModeId = ModeId("flow")
    public val GENTLE_ID: ModeId = ModeId("gentle")

    /** Well-known checklist ids; the app shows translated labels for them. */
    public const val CHECK_PHONE: String = "phone"
    public const val CHECK_WATER: String = "water"
    public const val CHECK_ONE_WINDOW: String = "one_window"

    /** Fixed 25/5, long 20 every 4. Breaks start by themselves; the next block waits for a tap. */
    public val classic: Mode = Mode(
        id = CLASSIC_ID,
        name = "Classic",
        accent = 0,
        rhythm = Rhythm.Fixed(focus = 25.minutes, short = 5.minutes, long = 20.minutes, longEvery = 4, autoBreak = true, autoFocus = false),
        ritual = Ritual(intention = IntentionRule.Optional),
        environment = Environment(display = Display.Full, face = Face.Ring, showSeconds = true, cues = CueStyle.Soft),
        builtIn = true,
    )

    /** Fixed 50/10, long 30 every 2, nothing starts by itself, pauses capped at 10 min. Calm by default. */
    public val deep: Mode = Mode(
        id = DEEP_ID,
        name = "Deep",
        accent = 1,
        rhythm = Rhythm.Fixed(
            focus = 50.minutes, short = 10.minutes, long = 30.minutes, longEvery = 2,
            autoBreak = false, autoFocus = false, pauseCap = 10.minutes,
        ),
        ritual = Ritual(
            intention = IntentionRule.Required,
            ifThen = true,
            checklist = listOf(
                ChecklistItem(CHECK_PHONE, "Phone silenced and out of reach"),
                ChecklistItem(CHECK_WATER, "Water"),
                ChecklistItem(CHECK_ONE_WINDOW, "One window or tab"),
            ),
            showResumeNote = true,
        ),
        environment = Environment(
            display = Display.Calm,
            face = Face.Ring,
            showSeconds = false,
            touch = TouchPolicy.HoldToReveal(),
            knockToPeek = true,
            cues = CueStyle.Rising,
            sunrise = true,
        ),
        builtIn = true,
    )

    /** Counts up; break from the Smits tiers; one nudge after 90 min. Calm, elapsed time only. */
    public val flow: Mode = Mode(
        id = FLOW_ID,
        name = "Flow",
        accent = 2,
        rhythm = Rhythm.Flow(tiers = SmitsTiers, nudgeAfter = 90.minutes),
        ritual = Ritual(intention = IntentionRule.Optional),
        environment = Environment(display = Display.Calm, face = Face.Digits, showSeconds = false, cues = CueStyle.Soft),
        builtIn = true,
    )

    /** Fixed 10/3 with no long breaks, then "another 10?". No ritual, warm copy. */
    public val gentle: Mode = Mode(
        id = GENTLE_ID,
        name = "Gentle start",
        accent = 3,
        rhythm = Rhythm.Fixed(focus = 10.minutes, short = 3.minutes, long = 3.minutes, longEvery = 0, autoBreak = true, autoFocus = false),
        ritual = Ritual(intention = IntentionRule.Off, showResumeNote = false),
        environment = Environment(display = Display.Full, face = Face.Disc, showSeconds = true, cues = CueStyle.Soft, tone = CopyTone.Warm),
        builtIn = true,
    )

    /** In carousel order. */
    public val all: List<Mode> = listOf(classic, deep, flow, gentle)

    public fun byId(id: ModeId): Mode? = all.firstOrNull { it.id == id }
}
