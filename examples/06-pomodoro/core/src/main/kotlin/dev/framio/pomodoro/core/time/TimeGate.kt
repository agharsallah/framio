package dev.framio.pomodoro.core.time

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/** Wall time [wallMs] was true at [elapsedMs] (elapsedRealtime, this boot). */
public data class TimeAnchor(val wallMs: Long, val elapsedMs: Long)

/**
 * Decides when the wall clock can be trusted. The frame has no RTC: it boots at 01-01 01:00 and only learns
 * the time from the network. Until then statistics wait, and sessions are back-dated afterwards from their
 * elapsed time.
 *
 * Trusted time is an anchor to elapsed time, not an offset from the system clock, so it stays right when
 * Android corrects (or someone changes) the system clock later. Thread-safe.
 *
 * On this frame the clock reads 01-01 01:00 at boot, then 2021-05-15 02:00, then the real time about 36 s
 * after boot (M0). So "a plausible year" isn't enough: pass the firmware's build time as [notBefore]
 * (`Build.TIME`), and offer the clock again on `ACTION_TIME_CHANGED`.
 */
public class TimeGate(notBefore: Long = EARLIEST) {
    private val floor = maxOf(notBefore, EARLIEST)

    private val _anchor = MutableStateFlow<TimeAnchor?>(null)
    public val anchor: StateFlow<TimeAnchor?> = _anchor.asStateFlow()

    public val isTrusted: Boolean get() = _anchor.value != null

    /** Wall time at [elapsedMs] of this boot, or null while untrusted. */
    public fun wallAt(elapsedMs: Long): Long? = _anchor.value?.let { it.wallMs + (elapsedMs - it.elapsedMs) }

    /**
     * Offers what the system clock says at [elapsedMs]; returns true if it became (or refreshed) the anchor.
     * Times before `notBefore` (the boot clock, Android's 2021 placeholder) or after 2100 are refused.
     */
    public fun offer(wallMs: Long, elapsedMs: Long): Boolean {
        if (!plausible(wallMs) || wallMs < floor) return false
        _anchor.value = TimeAnchor(wallMs, elapsedMs)
        return true
    }

    public companion object {
        /** 2026-01-01: a clock showing anything earlier hasn't been set. */
        public val EARLIEST: Long = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        private val LATEST: Long = Instant.parse("2100-01-01T00:00:00Z").toEpochMilli()

        public fun plausible(wallMs: Long): Boolean = wallMs in EARLIEST until LATEST
    }
}
