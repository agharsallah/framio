package dev.framio.hub.core.day

/** The screens behind the dock. */
enum class Panel { Today, Week, Timers, Board, Routines, Settings }

/** What the Hub shows while it's on screen. A ringing timer's Alert is drawn above any of these. */
sealed interface Mode {
    data object Ambient : Mode
    data class Open(val panel: Panel) : Mode

    /** Night: the backlight is off. */
    data object Night : Mode

    /** Night, with the dim night clock showing for a few seconds after a tap or a knock. */
    data object NightPeek : Mode

    /** Lying face down: backlight off, sounds muted except alerts. */
    data object Asleep : Mode
}

sealed interface ModeEvent {
    /** Any touch anywhere: resets the idle timer. */
    data object Interaction : ModeEvent

    /** A tap on Ambient or on the dark Night screen. */
    data object Tap : ModeEvent
    data object DoubleKnock : ModeEvent
    data class Open(val panel: Panel) : ModeEvent
    data object Close : ModeEvent
    data class NightChanged(val night: Boolean) : ModeEvent
    data class FaceDown(val down: Boolean) : ModeEvent

    /** Time passed: idle timeouts and the end of a night peek. */
    data object Tick : ModeEvent
}

data class ModeState(
    val mode: Mode = Mode.Ambient,
    /** elapsedRealtime of the last interaction. */
    val lastInputMs: Long = 0,
    val night: Boolean = false,
    val faceDown: Boolean = false,
    val peekUntilMs: Long = 0,
) {
    /** True when the backlight should be off. A ringing alert overrides this (see the shell). */
    val dark get() = mode == Mode.Night || mode == Mode.Asleep
}

/**
 * The Hub's navigation, as a pure state machine: six destinations, an always-on home and returns on idle
 * timeouts don't need a back stack (the frame has no Back key anyway). Tested on a virtual clock.
 */
object ModeMachine {
    const val TODAY_IDLE_MS = 30_000L
    const val PANEL_IDLE_MS = 90_000L
    const val SETTINGS_IDLE_MS = 180_000L
    const val PEEK_MS = 10_000L

    fun step(s: ModeState, event: ModeEvent, nowMs: Long): ModeState = when (event) {
        ModeEvent.Interaction -> s.copy(lastInputMs = nowMs)
        ModeEvent.Tap, ModeEvent.DoubleKnock -> when (s.mode) {
            Mode.Ambient -> s.copy(mode = Mode.Open(Panel.Today), lastInputMs = nowMs)
            Mode.Night, Mode.NightPeek -> s.copy(mode = Mode.NightPeek, peekUntilMs = nowMs + PEEK_MS, lastInputMs = nowMs)
            else -> s.copy(lastInputMs = nowMs)
        }
        is ModeEvent.Open -> if (s.mode == Mode.Asleep) s else s.copy(mode = Mode.Open(event.panel), lastInputMs = nowMs)
        ModeEvent.Close -> s.copy(mode = rest(s), lastInputMs = nowMs)
        is ModeEvent.NightChanged -> {
            val t = s.copy(night = event.night)
            when {
                t.mode == Mode.Asleep -> t
                event.night && t.mode == Mode.Ambient -> t.copy(mode = Mode.Night)
                !event.night && (t.mode == Mode.Night || t.mode == Mode.NightPeek) -> t.copy(mode = Mode.Ambient)
                else -> t // a panel stays open; night starts when it times out
            }
        }
        is ModeEvent.FaceDown -> {
            val t = s.copy(faceDown = event.down)
            if (event.down) t.copy(mode = Mode.Asleep) else if (s.mode == Mode.Asleep) t.copy(mode = rest(t), lastInputMs = nowMs) else t
        }
        ModeEvent.Tick -> when (val m = s.mode) {
            is Mode.Open -> if (nowMs - s.lastInputMs >= idleMs(m.panel)) s.copy(mode = rest(s)) else s
            Mode.NightPeek -> if (nowMs >= s.peekUntilMs) s.copy(mode = Mode.Night) else s
            Mode.Ambient -> if (s.night) s.copy(mode = Mode.Night) else s
            else -> s
        }
    }

    /** Where the Hub settles when nothing is open. */
    private fun rest(s: ModeState): Mode = when {
        s.faceDown -> Mode.Asleep
        s.night -> Mode.Night
        else -> Mode.Ambient
    }

    fun idleMs(panel: Panel) = when (panel) {
        Panel.Today -> TODAY_IDLE_MS
        Panel.Settings -> SETTINGS_IDLE_MS
        else -> PANEL_IDLE_MS
    }

    /** When the next Tick can change something, in elapsed ms; null when nothing is pending. */
    fun nextTimeoutMs(s: ModeState): Long? = when (val m = s.mode) {
        is Mode.Open -> s.lastInputMs + idleMs(m.panel)
        Mode.NightPeek -> s.peekUntilMs
        else -> null
    }
}
