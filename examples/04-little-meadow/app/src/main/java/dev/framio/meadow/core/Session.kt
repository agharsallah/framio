package dev.framio.meadow.core

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the child may do right now. */
enum class Gate {
    Open,
    /** Bedtime window: only the quiet corner, dim and warm. */
    Night,
    /** A short movement break after a while of play. */
    Break,
    /** Today's minutes are used up: "let's play outside". */
    DailyLimit,
    /** A grown-up locked it ("Pip is resting"). */
    Locked,
}

/** The rules, without Android: unit-tested. */
object SessionRules {
    fun gate(
        settings: Settings,
        nowMs: Long,
        todaySec: Int,
        sinceBreakSec: Int,
        bonusSec: Int,
        locked: Boolean,
    ): Gate {
        if (locked) return Gate.Locked
        val limit = settings.dailyLimitMin * 60
        if (limit > 0 && todaySec >= limit + bonusSec) return Gate.DailyLimit
        if (settings.breakEveryMin > 0 && sinceBreakSec >= settings.breakEveryMin * 60) return Gate.Break
        if (settings.bedtimeMode && Clock.plausible(nowMs)) {
            val bed = Clock.parseMinutes(settings.bedtime)
            val wake = Clock.parseMinutes(settings.wakeUp)
            if (bed != null && wake != null && Clock.inNight(Clock.minuteOfDay(nowMs), bed, wake)) return Gate.Night
        }
        return Gate.Open
    }

    /** Minutes left today, or null without a limit. */
    fun minutesLeft(settings: Settings, todaySec: Int, bonusSec: Int): Int? =
        if (settings.dailyLimitMin == 0) null else ((settings.dailyLimitMin * 60 + bonusSec - todaySec) / 60).coerceAtLeast(0)
}

/**
 * Play-time bookkeeping. [tick] is called once a second while the app is in front and the child
 * is actually playing (not on the rest screen, not during the night light).
 */
class Session(
    private val settings: SettingsStore,
    private val progress: Progress,
    private val prefs: SharedPreferences,
) {
    private var sinceBreakSec = 0
    private var bonusDay = prefs.getString(BONUS_DAY, "") ?: ""
    private var bonusSec = prefs.getInt(BONUS_SEC, 0)
    private var locked = prefs.getBoolean(LOCKED, false)
    private var forcedBreak = false

    private val state = MutableStateFlow(compute())
    val gate: StateFlow<Gate> = state.asStateFlow()

    val isLocked get() = locked
    val minutesLeft: Int? get() = SessionRules.minutesLeft(settings.value, progress.todaySeconds(), todayBonus())
    val minutesSinceBreak get() = sinceBreakSec / 60

    fun tick(playId: String?, counts: Boolean) {
        if (counts) {
            sinceBreakSec++
            progress.addSeconds(playId, 1)
        }
        refresh()
    }

    fun breakDone() {
        sinceBreakSec = 0
        forcedBreak = false
        refresh()
    }

    /** A wiggle break right now (from the dashboard). */
    fun forceBreak() {
        forcedBreak = true
        refresh()
    }

    fun lock(on: Boolean) {
        locked = on
        prefs.edit().putBoolean(LOCKED, on).apply()
        progress.log(if (on) "Locked from the dashboard" else "Unlocked")
        refresh()
    }

    fun addBonus(minutes: Int) {
        val day = Clock.dayKey()
        if (bonusDay != day) {
            bonusDay = day
            bonusSec = 0
        }
        bonusSec += minutes * 60
        prefs.edit().putString(BONUS_DAY, bonusDay).putInt(BONUS_SEC, bonusSec).apply()
        progress.log("+$minutes min today")
        refresh()
    }

    fun refresh() {
        state.value = compute()
    }

    private fun todayBonus() = if (bonusDay == Clock.dayKey()) bonusSec else 0

    private fun compute(): Gate {
        val g = SessionRules.gate(settings.value, System.currentTimeMillis(), progress.todaySeconds(), sinceBreakSec, todayBonus(), locked)
        return if (forcedBreak && (g == Gate.Open || g == Gate.Night)) Gate.Break else g
    }

    private companion object {
        const val LOCKED = "locked"
        const val BONUS_DAY = "bonusDay"
        const val BONUS_SEC = "bonusSec"
    }
}
