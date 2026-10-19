package dev.framio.pomodoro.core.time

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A point in time that survives a process kill and, when the wall clock was trusted, a reboot.
 * [elapsedMs] is `SystemClock.elapsedRealtime()` of boot [bootId]: it never jumps, but restarts at boot.
 * [wallMs] is the trusted wall time of the same instant, or null while the clock wasn't trusted (the frame
 * has no RTC and boots at 01-01 01:00). Raw milliseconds because this is the persisted, platform-edge form.
 */
@Serializable
public data class Moment(
    @SerialName("b") val bootId: String,
    @SerialName("e") val elapsedMs: Long,
    @SerialName("w") val wallMs: Long? = null,
) {
    public operator fun plus(d: Duration): Moment {
        val ms = d.inWholeMilliseconds
        return Moment(bootId, elapsedMs + ms, wallMs?.plus(ms))
    }

    public operator fun minus(d: Duration): Moment = plus(-d)
}

/** A deadline is a [Moment] in the future. */
public typealias Deadline = Moment

/**
 * The time as the engine sees it: this boot's [elapsedMs], the trusted [wallMs] (null until TimeGate trusts
 * the clock), and the local [zone] for calendar questions ("today").
 */
public data class Now(
    val bootId: String,
    val elapsedMs: Long,
    val wallMs: Long?,
    val zone: ZoneId,
) {
    public val moment: Moment get() = Moment(bootId, elapsedMs, wallMs)

    /** Today's local date, or null while the wall clock isn't trusted. */
    public val date: LocalDate? get() = wallMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    /**
     * Time from now until [m] (negative once it has passed), or null when it can't be known:
     * same boot → elapsed time, which never jumps; another boot → wall clock, if both sides trust it.
     */
    public fun until(m: Moment): Duration? = when {
        m.bootId == bootId -> (m.elapsedMs - elapsedMs).milliseconds
        m.wallMs != null && wallMs != null -> (m.wallMs - wallMs).milliseconds
        else -> null
    }

    /** Time since [m], or null when it can't be known. */
    public fun since(m: Moment): Duration? = until(m)?.unaryMinus()

    public operator fun plus(d: Duration): Moment = moment + d
}

/** Time left until this deadline (negative once passed), or null if it was lost across a reboot. */
public fun Deadline.remaining(now: Now): Duration? = now.until(this)

/** True when this deadline can never be resolved: set in an earlier boot, before the clock was trusted. */
public fun Moment.isLost(now: Now): Boolean = now.until(this) == null

/**
 * The same instant expressed in the current boot when possible, with its wall time filled in once the clock
 * is trusted, so it also survives the next reboot. Unchanged when it can't be resolved.
 */
public fun Moment.rebased(now: Now): Moment {
    val offset = now.until(this) ?: return this
    return Moment(now.bootId, now.elapsedMs + offset.inWholeMilliseconds, wallMs ?: now.wallMs?.plus(offset.inWholeMilliseconds))
}

/** Time from this moment to [later]: by elapsed time within one boot, else by wall clock; null if unknown. */
public fun Moment.durationUntil(later: Moment): Duration? = when {
    bootId == later.bootId -> (later.elapsedMs - elapsedMs).milliseconds
    wallMs != null && later.wallMs != null -> (later.wallMs - wallMs).milliseconds
    else -> null
}
