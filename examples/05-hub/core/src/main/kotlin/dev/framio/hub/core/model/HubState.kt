package dev.framio.hub.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything the household owns: timers, notes, routines, people and settings. Persisted as one JSON file,
 * shared with phones as-is. Never holds secrets (calendar URLs and keys live in the secrets store).
 */
@Serializable
data class HubState(
    val schema: Int = SCHEMA,
    /** +1 on every change; phones sync on it. */
    val rev: Long = 0,
    val timers: List<Timer> = emptyList(),
    val notes: List<Note> = emptyList(),
    val routines: List<Routine> = emptyList(),
    val people: List<Person> = emptyList(),
    val calendars: List<CalendarFeed> = emptyList(),
    val settings: Settings = Settings(),
    /** When someone last opened the Board, for the "new note" chip. */
    val boardSeenWallMs: Long? = null,
    /** The last [SEEN_MAX] command ids, so a command delivered twice (the relay delivers at least once) applies once. */
    val seen: List<String> = emptyList(),
) {
    fun person(id: String?): Person? = id?.let { pid -> people.firstOrNull { it.id == pid } }

    companion object {
        const val SCHEMA = 1
        const val SEEN_MAX = 256
        const val MAX_TIMERS = 8
        const val MAX_NOTES = 60
        const val MAX_ROUTINES = 8
        const val MAX_PEOPLE = 8
        const val MAX_CALENDARS = 8
    }
}

/**
 * A point in time that survives the process dying (same boot, via [elapsedMs]) and, once the wall clock
 * is trusted, a reboot (via [wallMs]). `elapsedRealtime` keeps counting while the frame's clock is still
 * at 01:00 after boot, so timers never depend on the clock being right.
 */
@Serializable
data class Deadline(val bootId: String, val elapsedMs: Long, val wallMs: Long? = null)

@Serializable
data class Timer(
    val id: String,
    val label: String,
    val totalMs: Long,
    /** Index into the palette; two running timers never share one. */
    val color: Int,
    /** Index into [dev.framio.hub.core.timers.Melodies]; also unique among running timers. */
    val melody: Int,
    val run: Run,
)

@Serializable
sealed interface Run {
    @Serializable @SerialName("running")
    data class Running(val until: Deadline) : Run

    @Serializable @SerialName("paused")
    data class Paused(val remainingMs: Long) : Run

    /** Ringing until someone stops it, or for [dev.framio.hub.core.engine.Rules.RING_MS], then [Done]. */
    @Serializable @SerialName("ringing")
    data class Ringing(val since: Deadline) : Run

    /** Finished and quiet: shown as a chip ("Pasta finished 2 min ago") until someone touches the screen. */
    @Serializable @SerialName("done")
    data class Done(val at: Deadline) : Run

    /** The frame restarted before the clock could be trusted, so when it ends is unknown. Shown, never dropped. */
    @Serializable @SerialName("interrupted")
    data object Interrupted : Run
}

@Serializable
data class Note(
    val id: String,
    val body: NoteBody,
    val author: String? = null,
    val keep: Keep = Keep.ThreeDays,
    /** Null while the clock isn't trusted yet; filled in when it is. */
    val createdWallMs: Long? = null,
    val expiresWallMs: Long? = null,
)

@Serializable
enum class Keep {
    @SerialName("today") Today,
    @SerialName("3d") ThreeDays,
    @SerialName("pinned") Pinned,
}

@Serializable
sealed interface NoteBody {
    @Serializable @SerialName("text")
    data class Text(val text: String) : NoteBody

    @Serializable @SerialName("checklist")
    data class Checklist(val title: String, val items: List<Item>) : NoteBody

    /** The strokes live in files/scribbles/<fileId>.bin (see ScribbleCodec), so the state stays a few KB. */
    @Serializable @SerialName("scribble")
    data class Scribble(val fileId: String, val strokeCount: Int) : NoteBody
}

@Serializable
data class Item(val id: String, val text: String, val done: Boolean = false)

@Serializable
data class Routine(
    val id: String,
    val title: String,
    val personId: String? = null,
    val steps: List<Step>,
    /** Local "HH:mm" when every step unticks again. */
    val resetAt: String = "04:00",
    /** Shown as a chip on Ambient inside this window, with a "leave in" countdown to its end. */
    val window: TimeWindow? = null,
    /** The local date (ISO) of the last reset. */
    val lastResetDay: String? = null,
)

@Serializable
data class Step(val id: String, val label: String, val icon: String, val done: Boolean = false)

@Serializable
data class Person(val id: String, val name: String, val color: Int)

/** A calendar the household subscribes to. Its URL is a secret and lives in the secrets store under [id]. */
@Serializable
data class CalendarFeed(val id: String, val name: String, val personId: String? = null)

/** Local times "HH:mm"; the window may cross midnight (21:30–07:00). */
@Serializable
data class TimeWindow(val start: String, val end: String)

@Serializable
data class Location(val latitude: Double, val longitude: Double, val name: String = "")

@Serializable
data class Settings(
    val location: Location? = null,
    val night: NightSettings = NightSettings(),
    /** Everything but alerts is silent inside this window. */
    val quiet: TimeWindow? = TimeWindow("21:30", "07:00"),
    /** Keep the service (phones, timers) running after Exit when no timer needs it. */
    val keepRunning: Boolean = true,
    val knock: Boolean = true,
    val reduceMotion: Boolean = false,
    val hourlyChime: Boolean = false,
    /** Backlight level (sysfs, 1..255) to restore after night. Read from the frame in day mode, never hard-coded. */
    val dayBacklight: Int? = null,
    /** Window brightness in day mode, 0.05..1; null = the system's. */
    val dayBrightness: Float? = null,
)

@Serializable
data class NightSettings(
    val enabled: Boolean = true,
    /** "schedule" uses [start]; "sunset" uses sunset + [sunsetOffsetMin] (needs a location). */
    val mode: NightMode = NightMode.Schedule,
    val start: String = "22:30",
    val end: String = "06:30",
    val sunsetOffsetMin: Int = 180,
    /** Brightness ramps from off to the day level over this many minutes before [end]. */
    val rampMin: Int = 15,
)

@Serializable
enum class NightMode {
    @SerialName("schedule") Schedule,
    @SerialName("sunset") Sunset,
}
