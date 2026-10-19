package dev.framio.pomodoro.platform

import android.content.Context
import android.provider.Settings
import androidx.core.content.edit
import dev.framio.pomodoro.core.engine.DisplayLevel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Remembers, across a kill, that we turned the backlight off and what to bring it back to. Written before the write. */
interface DarkMarker {
    fun save(wrote: Int, day: Int)

    /** (level we wrote, level to restore), or null when we left the backlight alone. */
    fun load(): Pair<Int, Int>?

    fun clear()
}

/** [DarkMarker] in SharedPreferences, with `commit()`: the next line may be a kill -9. */
class PrefsDarkMarker(context: Context) : DarkMarker {
    private val prefs = context.getSharedPreferences("display", Context.MODE_PRIVATE)

    override fun save(wrote: Int, day: Int) {
        prefs.edit(commit = true) {
            putInt(WROTE, wrote)
            putInt(DAY, day)
        }
    }

    override fun load(): Pair<Int, Int>? =
        if (prefs.contains(WROTE)) prefs.getInt(WROTE, 0) to prefs.getInt(DAY, FALLBACK_LEVEL) else null

    override fun clear() {
        prefs.edit(commit = true) { clear() }
    }

    private companion object {
        const val WROTE = "wrote"
        const val DAY = "day"
    }
}

/** Only if Android has no brightness setting at all: a mid level that's readable in a room. */
const val FALLBACK_LEVEL: Int = 128

/** Android's own brightness setting: the fallback level when we never saw the frame lit before going dark. */
fun systemBacklightLevel(context: Context): Int = try {
    Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS).coerceIn(10, 255)
} catch (_: Settings.SettingNotFoundException) {
    FALLBACK_LEVEL
}

/**
 * The root backlight (Blackout, M3), adapted from 05-hub with the M0 rules:
 *  - Restore whenever Pomodoro leaves the screen, exits, crashes or starts.
 *  - Restore **only if the backlight still holds the value we wrote**. Frameo owns the brightness and has a 23:00
 *    standby: if something else changed the level meanwhile, that change wins.
 *  - The level restored is the one read just before going dark, else Android's setting, never a constant.
 * Blocking root calls run on [io]; the mutex keeps them in order.
 */
class DisplayPower(
    private val backlight: Backlight,
    private val marker: DarkMarker,
    private val io: CoroutineDispatcher,
    private val systemLevel: () -> Int,
) {
    private val mutex = Mutex()

    /** What we last wrote: null = we haven't touched the backlight. */
    @Volatile var written: Int? = null
        private set

    @Volatile private var dayLevel: Int? = null

    val isDark: Boolean get() = written == 0

    /** We changed the backlight (dark, or lifted to a peek level from dark) and haven't given it back yet. */
    val owns: Boolean get() = written != null

    suspend fun off(): Boolean = mutex.withLock {
        withContext(io) {
            if (written == 0) return@withContext true
            val current = backlight.read()
            if (written == null && current != null && current > 0) dayLevel = current
            val day = dayLevel ?: systemLevel()
            marker.save(0, day) // first: a kill right after the write must still know to restore
            if (backlight.write(0)) {
                written = 0
                Logs.event("display", "backlight off (day level $day)")
                true
            } else {
                marker.clear()
                false
            }
        }
    }

    /**
     * Steps the backlight we own from what we wrote to [level] over [over] (Blackout's peek: up to a low level and
     * back to 0). Compare-and-set first: if the backlight no longer reads what we wrote, someone else has it, and we
     * let go instead (as [restore] would). The marker follows every step, so a kill mid-ramp still recovers.
     * False when we don't own the backlight or let go of it.
     */
    suspend fun ramp(level: Int, over: Duration): Boolean = mutex.withLock {
        withContext(io) {
            val from = written ?: return@withContext false
            val now = backlight.read()
            if (now != from) {
                Logs.event("display", "ramp: backlight is $now, not our $from; someone else changed it, leaving it")
                written = null
                marker.clear()
                return@withContext false
            }
            stepTo(from, level.coerceIn(0, 255), over)
        }
    }

    /**
     * Deep's sunrise from Blackout: the backlight rises from dark to the day level (at most [ceiling]) over [over], then
     * is ours no more (like a restore). Same compare-and-set rule. True if it reached the day level.
     */
    suspend fun sunrise(over: Duration, ceiling: Int? = null): Boolean = mutex.withLock {
        withContext(io) {
            val from = written ?: return@withContext false
            val now = backlight.read()
            // Evening amber caps the window's brightness: rise to the cap, not above it and then down
            val day = (dayLevel ?: systemLevel()).let { if (ceiling != null) minOf(it, ceiling) else it }
            val ok = if (now == from) {
                stepTo(from, day, over)
            } else {
                Logs.event("display", "sunrise: backlight is $now, not our $from; leaving it")
                false
            }
            if (ok) Logs.event("display", "sunrise done at $day over $over")
            written = null
            marker.clear()
            ok
        }
    }

    /** Writes from [from] to [to] in steps spread over [over] (root writes take tens of ms each: time-based). */
    private suspend fun stepTo(from: Int, to: Int, over: Duration): Boolean {
        val totalMs = over.inWholeMilliseconds.coerceAtLeast(0)
        val steps = (totalMs / STEP_MS).toInt().coerceIn(1, MAX_STEPS)
        val day = dayLevel ?: systemLevel()
        val start = System.nanoTime()
        for (i in 1..steps) {
            val v = from + (to - from) * i / steps
            marker.save(v, day) // first: a kill right after the write must still know it was ours
            if (!backlight.write(v)) return false
            written = v
            if (i < steps) {
                val due = totalMs * i / steps
                val spent = (System.nanoTime() - start) / 1_000_000
                if (due > spent) delay(due - spent)
            }
        }
        if (to == 0) written = 0
        return true
    }

    /** Back to the level from before, if we changed it and nobody else has since. True if it wrote. */
    suspend fun restore(why: String): Boolean = mutex.withLock { withContext(io) { restoreNow(why) } }

    /**
     * At start: a kill -9 while dark skips every handler. If the marker says we turned it off and the backlight
     * still reads what we wrote, turn it back on. A dark screen without our marker is someone else's (Frameo's
     * standby) and stays dark.
     */
    suspend fun recoverAtStart() = mutex.withLock {
        withContext(io) {
            val (wrote, day) = marker.load() ?: return@withContext
            val now = backlight.read()
            if (now == wrote) {
                backlight.write(day)
                Logs.event("display", "backlight was left at $wrote by a killed run; restored $day")
            } else {
                Logs.event("display", "start: backlight is $now, not our $wrote; leaving it")
            }
            marker.clear()
            written = null
        }
    }

    /** For the crash handler: the same rule, synchronously, without the mutex (the process is about to die). */
    fun restoreBlocking() {
        try {
            restoreNow("crash")
        } catch (_: Exception) {
        }
    }

    private fun restoreNow(why: String): Boolean {
        val wrote = written ?: return false
        val now = backlight.read()
        val ok = if (now == wrote) {
            backlight.write(dayLevel ?: systemLevel())
        } else {
            Logs.event("display", "$why: backlight is $now, not our $wrote; someone else changed it, leaving it")
            false
        }
        written = null
        marker.clear()
        return ok
    }

    private companion object {
        /** One root write per step at most this often; a 10 s sunrise is 20 steps of about 9 levels. */
        const val STEP_MS = 500L
        const val MAX_STEPS = 24
    }
}

/**
 * What the activity's window should do: its brightness for [level], reached over [ramp] (Deep's sunrise). [lifted]:
 * Calm is lifted to the peek level for a moment (a knock's peek, the hold panel, the park pad, the end prompt).
 * [cap]: evening amber's ceiling (0..1); the window is never brighter than that, the system level included.
 */
data class WindowLight(val level: DisplayLevel, val ramp: Duration = Duration.ZERO, val lifted: Boolean = false, val cap: Float? = null)

/**
 * Carries out `SetDisplay`: Normal, Dim and Calm are the window's own brightness (no permission, only our window,
 * applied by the activity); Blackout is the root backlight. Requests are applied one at a time, latest wins.
 * The 05-hub rule holds: the window brightness is never changed while the backlight is off, so the backlight is
 * restored first.
 *
 * [light] lifts a dark display for a moment (Deep's knock peek, the hold panel, the park pad, the end prompt): Calm's
 * window fades up to [WindowLevels.PEEK]; Blackout's backlight is stepped up to [PEEK_BACKLIGHT] through
 * [DisplayPower] (same compare-and-set rule, same marker) and back to 0. A sunrise (SetDisplay Normal with a ramp)
 * from Blackout steps the backlight up to the day level.
 */
class DisplayController(private val power: DisplayPower, scope: CoroutineScope) {
    private val target = MutableStateFlow(WindowLight(DisplayLevel.Normal))
    private val lit = MutableStateFlow(false)
    private val capped = MutableStateFlow(false)
    private val _window = MutableStateFlow(WindowLight(DisplayLevel.Normal))

    /** Collected by the activity while started. */
    val window: StateFlow<WindowLight> = _window.asStateFlow()

    init {
        scope.launch {
            // Lifting only means something in Calm and Blackout: elsewhere (a sunrise in progress) it must not restart anything
            var wasCapped = false
            combine(target, lit, capped) { light, lifted, cap -> Triple(light, lifted && light.level.isDark(), cap) }
                .distinctUntilChanged()
                .collectLatest { (light, lifted, cap) ->
                    // The evening cap arriving (or leaving) on its own eases in, it doesn't jump
                    val eased = if (cap != wasCapped && !light.ramp.isPositive()) light.copy(ramp = EVENING_RAMP) else light
                    wasCapped = cap
                    apply(eased.copy(cap = if (cap) EVENING_CAP else null), lifted)
                }
        }
    }

    private suspend fun apply(light: WindowLight, lifted: Boolean) {
        if (light.level == DisplayLevel.Blackout) {
            if (!power.owns) power.off()
            if (lifted) {
                power.ramp(PEEK_BACKLIGHT, LIGHT_UP)
            } else if (power.owns && !power.isDark) {
                power.ramp(0, LIGHT_DOWN)
            }
            return
        }
        if (power.owns) {
            if (light.ramp.isPositive()) power.sunrise(light.ramp, light.cap?.let { (it * 255).toInt() }) else power.restore("display ${light.level}")
        }
        val calmLift = light.level == DisplayLevel.Calm
        if (calmLift && !lifted && _window.value.level != DisplayLevel.Calm) {
            // Into Calm (Begin): the backlight drops after the breath, not during it. Android ramps a window's
            // brightness change itself, writing the backlight every frame for ~0.8 s in system_server; under Begin's
            // animation that was most of Deep's jank (atrace, LOG M6). A newer request cancels the wait.
            delay(CALM_SETTLE)
        }
        _window.value = when {
            calmLift && lifted -> WindowLight(DisplayLevel.Calm, LIGHT_UP, lifted = true, cap = light.cap)
            calmLift && _window.value.lifted -> WindowLight(DisplayLevel.Calm, LIGHT_DOWN, cap = light.cap)
            else -> light
        }
    }

    private fun DisplayLevel.isDark() = this == DisplayLevel.Calm || this == DisplayLevel.Blackout

    fun set(level: DisplayLevel, ramp: Duration) {
        // A break dims the frame gently rather than with a jump (it shouldn't itself catch the eye)
        val over = if (level == DisplayLevel.Dim && !ramp.isPositive()) DIM_RAMP else ramp
        Logs.event("display", "$level${if (over.isPositive()) " over $over" else ""}")
        target.value = WindowLight(level, over)
    }

    /** Lifts (or lowers again) a Calm or Blackout display; no effect on the others. */
    fun light(on: Boolean) {
        if (lit.value != on) Logs.event("display", if (on) "lift" else "lower")
        lit.value = on
    }

    /** Evening amber: caps the window's brightness at [EVENING_CAP] (eased over [EVENING_RAMP]). */
    fun evening(on: Boolean) {
        if (capped.value != on) Logs.event("display", if (on) "evening cap on" else "evening cap off")
        capped.value = on
    }

    /** The activity stopped (another app, Exit): never leave the frame dark behind us. */
    fun onLeftScreen() {
        if (power.owns) target.value = WindowLight(DisplayLevel.Normal)
    }

    companion object {
        /** Blackout's peek: a low backlight (of 255), enough to read the time across a desk at night. */
        const val PEEK_BACKLIGHT: Int = 64
        val LIGHT_UP: Duration = 500.milliseconds
        val LIGHT_DOWN: Duration = 1.seconds

        /** Into a break's Dim level. */
        val DIM_RAMP: Duration = 1500.milliseconds

        /** Evening amber's brightness ceiling (of the window's 0..1): about 89/255, a dim reading light. */
        const val EVENING_CAP: Float = 0.35f
        val EVENING_RAMP: Duration = 3.seconds

        /** Into Calm, the window dims once Begin's breath (3 s) has settled. */
        val CALM_SETTLE: Duration = 3.seconds
    }
}

/**
 * Window brightness per level (0..1, or -1 for "the system's"). Android 6 clamps a window's brightness to the
 * setting's minimum (`config_screenBrightnessSettingMinimum`, 30 of 255 on the frame, LOG M0 d.): anything below
 * 30/255 ≈ 0.12 is that same floor. Calm sits on the floor; its content is itself drawn at about 12 % luminance.
 */
object WindowLevels {
    const val SYSTEM: Float = -1f
    const val DIM: Float = 0.3f
    const val CALM: Float = 0.12f

    /** Calm lifted for a peek or a panel. */
    const val PEEK: Float = 0.3f

    fun of(light: WindowLight): Float = if (light.lifted) PEEK else of(light.level)

    /** [of] under the evening cap, given the system's own level (0..1) for "the system's". */
    fun capped(light: WindowLight, system: Float): Float {
        val level = of(light)
        val cap = light.cap ?: return level
        return minOf(if (level < 0) system else level, cap)
    }

    fun of(level: DisplayLevel): Float = when (level) {
        DisplayLevel.Normal -> SYSTEM
        DisplayLevel.Dim -> DIM
        DisplayLevel.Calm -> CALM
        DisplayLevel.Blackout -> SYSTEM // never reached: the window is left alone while the backlight is off
    }
}
