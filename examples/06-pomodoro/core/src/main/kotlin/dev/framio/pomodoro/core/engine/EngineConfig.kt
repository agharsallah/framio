package dev.framio.pomodoro.core.engine

import kotlin.time.Duration

/**
 * Engine parameters that aren't user settings.
 *
 * @param timeScale real time per logical time. 1 in production; 1/60 runs "minutes as seconds" for device
 *   tests, so a 25-minute block takes 25 s. Deadlines are real; the session log stays logical.
 */
public data class EngineConfig(val timeScale: Double = 1.0) {
    init {
        require(timeScale > 0 && timeScale <= 1) { "timeScale must be in (0, 1]" }
    }

    /** Logical → real. */
    public fun real(d: Duration): Duration = if (timeScale == 1.0) d else d * timeScale

    /** Real → logical. */
    public fun logical(d: Duration): Duration = if (timeScale == 1.0) d else d / timeScale

    public companion object {
        public val Default: EngineConfig = EngineConfig()
        public val MinutesAsSeconds: EngineConfig = EngineConfig(1.0 / 60)
    }
}
