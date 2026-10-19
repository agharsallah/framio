package dev.framio.pomodoro.core.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What the mode editor may save, and the ranges its pickers offer. */
public object ModeRules {
    public const val MAX_NAME: Int = 24
    public const val MAX_CHECKLIST: Int = 8
    public const val MAX_ITEM: Int = 48

    /** 0 = never. */
    public const val MAX_LONG_EVERY: Int = 12

    public val FOCUS: ClosedRange<Duration> = 1.minutes..4.hours
    public val SHORT: ClosedRange<Duration> = 1.minutes..1.hours
    public val LONG: ClosedRange<Duration> = 1.minutes..2.hours
    public val PAUSE_CAP: ClosedRange<Duration> = 1.minutes..1.hours
    public val NUDGE: ClosedRange<Duration> = 10.minutes..4.hours
    public val CHECK_IN: ClosedRange<Duration> = 5.minutes..1.hours
    public val HOLD: ClosedRange<Duration> = 0.5.seconds..3.seconds

    /** Why [mode] can't be saved, or null if it can. */
    public fun problem(mode: Mode): String? {
        if (mode.name.isBlank() || mode.name.length > MAX_NAME) return "name must be 1..$MAX_NAME characters"
        if (mode.accent !in 0 until Mode.ACCENTS) return "accent must be 0..${Mode.ACCENTS - 1}"
        ritualProblem(mode.ritual)?.let { return it }
        environmentProblem(mode.environment)?.let { return it }
        mode.rhythm.pauseCap?.let { if (it !in PAUSE_CAP) return "pause cap must be 1 min..1 h" }
        when (val r = mode.rhythm) {
            is Rhythm.Fixed -> {
                if (r.focus !in FOCUS) return "focus must be 1 min..4 h"
                if (r.short !in SHORT) return "short break must be 1 min..1 h"
                if (r.long !in LONG) return "long break must be 1 min..2 h"
                if (r.longEvery !in 0..MAX_LONG_EVERY) return "long break every 0 (never)..$MAX_LONG_EVERY blocks"
            }
            is Rhythm.Flow -> {
                if (r.tiers.isEmpty() || r.tiers.last().upTo != null) return "the last tier must cover any length"
                if (r.tiers.any { it.rest !in SHORT }) return "breaks must be 1 min..1 h"
                val bounds = r.tiers.mapNotNull { it.upTo }
                if (bounds.size != r.tiers.size - 1) return "only the last tier may be open-ended"
                if (bounds.zipWithNext().any { (a, b) -> b <= a } || bounds.any { it <= Duration.ZERO }) return "tiers must rise"
                r.nudgeAfter?.let { if (it !in NUDGE) return "nudge must be 10 min..4 h" }
            }
        }
        return null
    }

    private fun ritualProblem(r: Ritual): String? {
        if (r.checklist.size > MAX_CHECKLIST) return "at most $MAX_CHECKLIST checklist items"
        if (r.checklist.any { it.label.isBlank() || it.label.length > MAX_ITEM }) return "checklist items must be 1..$MAX_ITEM characters"
        if (r.checklist.any { it.id.isBlank() } || r.checklist.map { it.id }.toSet().size != r.checklist.size) return "checklist ids must be unique"
        return null
    }

    private fun environmentProblem(e: Environment): String? {
        e.checkIn?.let { if (it !in CHECK_IN) return "check-in must be 5 min..1 h" }
        (e.touch as? TouchPolicy.HoldToReveal)?.let { if (it.hold !in HOLD) return "hold must be 0.5..3 s" }
        return null
    }

    /** A new id for one of your own modes, unlike any in [modes] or the presets. */
    public fun newId(modes: List<Mode>): ModeId {
        val taken = (modes + Presets.all).map { it.id.value }.toSet()
        return generateSequence(1) { it + 1 }.map { "own-$it" }.first { it !in taken }.let(::ModeId)
    }

    /** A name for a copy of [name] that no mode in [modes] has yet: "Deep 2", "Deep 3", … */
    public fun copyName(name: String, modes: List<Mode>): String {
        val names = modes.map { it.name.lowercase() }.toSet()
        val base = name.take(MAX_NAME - 3).trimEnd()
        return generateSequence(2) { it + 1 }.map { "$base $it" }.first { it.lowercase() !in names }
    }

    /** A new checklist item id for a label you typed, unlike the ids already in [items]. */
    public fun newItemId(items: List<ChecklistItem>): String {
        val taken = items.map { it.id }.toSet()
        return generateSequence(1) { it + 1 }.map { "own-$it" }.first { it !in taken }
    }
}
