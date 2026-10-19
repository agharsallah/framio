package dev.framio.pomodoro.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalTime

@Serializable
public enum class Theme {
    @SerialName("ink") Ink,
    @SerialName("paper") Paper,
    @SerialName("forest") Forest,
    @SerialName("tomato") Tomato,
}

/** How large the Full display's timer face is drawn (Settings → Appearance). */
@Serializable
public enum class TimerSize {
    @SerialName("small") Small,
    @SerialName("medium") Medium,
    @SerialName("large") Large,
}

/**
 * Everything you choose. Stored on its own (DataStore), apart from the engine snapshot. Every field has a default, so
 * a file written by an older version (fewer keys) still decodes (see `SettingsMigrationTest`).
 *
 * @param accent overrides every mode's accent when set (0..7); null = each mode's own
 * @param face the timer face for every mode when set; null = each mode's own
 * @param evening evening amber on: from [eveningStart] until [eveningEnd] the theme turns warm and the brightness is
 *   capped (only while the wall clock is trusted). A flag, not a null start: explicitNulls is off, so a null would
 *   read back as the default.
 * @param soundVolume 0..1, applied to cues (never the system volume)
 * @param soundscapeVolume 0..1, applied to the soundscape of a mode that has one
 * @param systemKeyboard type with the system IME instead of the built-in keyboard
 * @param modes in carousel order (built-in presets may be moved too), at most [MAX_MODES]
 */
@Serializable
public data class Settings(
    @SerialName("theme") val theme: Theme = Theme.Ink,
    @SerialName("accent") val accent: Int? = null,
    @SerialName("dailyTarget") val dailyTarget: Int = DEFAULT_TARGET,
    @SerialName("evening") val evening: Boolean = true,
    @Serializable(with = LocalTimeAsString::class)
    @SerialName("eveningStart") val eveningStart: LocalTime = DEFAULT_EVENING,
    @Serializable(with = LocalTimeAsString::class)
    @SerialName("eveningEnd") val eveningEnd: LocalTime = DEFAULT_MORNING,
    @SerialName("reduceMotion") val reduceMotion: Boolean = false,
    @SerialName("soundVolume") val soundVolume: Float = 0.6f,
    @SerialName("soundscapeVolume") val soundscapeVolume: Float = DEFAULT_SOUNDSCAPE_VOLUME,
    @SerialName("face") val face: Face? = null,
    @SerialName("timerSize") val timerSize: TimerSize = TimerSize.Medium,
    @SerialName("systemKeyboard") val systemKeyboard: Boolean = false,
    @SerialName("modes") val modes: List<Mode> = Presets.all,
    @SerialName("selectedMode") val selectedMode: ModeId = Presets.DEEP_ID,
) {
    public fun mode(id: ModeId): Mode? = modes.firstOrNull { it.id == id }

    /** The selected mode, or the first one if it went missing. */
    public val selected: Mode get() = mode(selectedMode) ?: modes.firstOrNull() ?: Presets.deep

    /** [mode]'s face, honouring the [face] override. */
    public fun faceOf(mode: Mode): Face = face ?: mode.environment.face

    /** The evening amber window's start, or null when it is switched off. */
    public val amberFrom: LocalTime? get() = eveningStart.takeIf { evening }

    /**
     * Repairs a loaded file: built-in presets always match this version of the app (and come back, at the end, if
     * missing), every mode keeps its place in your order, duplicates go, your own modes beyond [MAX_MODES] are dropped
     * from the end, the target and volumes stay in range, and the selected mode exists.
     */
    public fun normalized(): Settings {
        val seen = HashSet<ModeId>()
        val ordered = modes.mapNotNull { m ->
            when {
                !seen.add(m.id) -> null
                else -> Presets.byId(m.id) ?: m.takeIf { !it.builtIn }?.copy(builtIn = false)
            }
        }
        val all = ordered + Presets.all.filter { p -> ordered.none { it.id == p.id } }
        var room = MAX_MODES - Presets.all.size
        val fixed = all.filter { m -> m.builtIn || room-- > 0 }
        return copy(
            modes = fixed,
            selectedMode = if (fixed.any { it.id == selectedMode }) selectedMode else Presets.DEEP_ID,
            dailyTarget = dailyTarget.coerceIn(1, MAX_TARGET),
            soundVolume = soundVolume.coerceIn(0f, 1f).takeIf { !it.isNaN() } ?: 0.6f,
            soundscapeVolume = soundscapeVolume.coerceIn(0f, 1f).takeIf { !it.isNaN() } ?: DEFAULT_SOUNDSCAPE_VOLUME,
            accent = accent?.takeIf { it in 0 until Mode.ACCENTS },
        )
    }

    public companion object {
        public const val DEFAULT_TARGET: Int = 6
        public const val MAX_TARGET: Int = 24
        public const val DEFAULT_SOUNDSCAPE_VOLUME: Float = 0.35f

        /** Evening amber from 20:00 (PLAN §3.2 row 13) until 06:00. */
        public val DEFAULT_EVENING: LocalTime = LocalTime.of(20, 0)
        public val DEFAULT_MORNING: LocalTime = LocalTime.of(6, 0)

        /** The Home carousel shows at most this many modes, and there are no more than that. */
        public const val MAX_MODES: Int = 8
    }
}
