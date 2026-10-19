package dev.framio.hub.core.engine

import dev.framio.hub.core.model.CalendarFeed
import dev.framio.hub.core.model.Keep
import dev.framio.hub.core.model.NoteBody
import dev.framio.hub.core.model.Person
import dev.framio.hub.core.model.Routine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * What anyone may ask the Hub to do: the frame's own UI, phones (LAN or relay) and adb all send the same JSON,
 * e.g. `{"type":"timer.start","id":"…","from":"lan:pixel","label":"Pasta","ms":480000}`.
 * [id] is chosen by the sender; a repeated id is ignored. Entities a command creates take the command's id.
 */
@Serializable
sealed interface Command {
    val id: String
    val from: String
}

// Timers

@Serializable @SerialName("timer.start")
data class TimerStart(override val id: String = "", override val from: String = "", val label: String, val ms: Long) : Command

@Serializable @SerialName("timer.pause")
data class TimerPause(override val id: String = "", override val from: String = "", val timerId: String) : Command

@Serializable @SerialName("timer.resume")
data class TimerResume(override val id: String = "", override val from: String = "", val timerId: String) : Command

/** +1 min, +5 min. On a ringing or finished timer it starts it again with [ms]. */
@Serializable @SerialName("timer.add")
data class TimerAdd(override val id: String = "", override val from: String = "", val timerId: String, val ms: Long) : Command

/** Removes a timer whatever its state. */
@Serializable @SerialName("timer.cancel")
data class TimerCancel(override val id: String = "", override val from: String = "", val timerId: String) : Command

/** Stops a ringing timer: [timerId], or the one on top of the stack when null (Stop button, double knock). */
@Serializable @SerialName("timer.stop")
data class TimerStop(override val id: String = "", override val from: String = "", val timerId: String? = null) : Command

/** Clears the "finished" and "interrupted" chips (someone touched the screen). */
@Serializable @SerialName("timer.clear")
data class TimerClear(override val id: String = "", override val from: String = "") : Command

// Board

@Serializable @SerialName("note.add")
data class NoteAdd(
    override val id: String = "",
    override val from: String = "",
    val body: NoteBody,
    val author: String? = null,
    val keep: Keep = Keep.ThreeDays,
) : Command

@Serializable @SerialName("note.remove")
data class NoteRemove(override val id: String = "", override val from: String = "", val noteId: String) : Command

@Serializable @SerialName("note.keep")
data class NoteKeep(override val id: String = "", override val from: String = "", val noteId: String, val keep: Keep) : Command

@Serializable @SerialName("item.add")
data class ItemAdd(override val id: String = "", override val from: String = "", val noteId: String, val text: String) : Command

@Serializable @SerialName("item.check")
data class ItemCheck(override val id: String = "", override val from: String = "", val noteId: String, val itemId: String, val done: Boolean) : Command

@Serializable @SerialName("item.remove")
data class ItemRemove(override val id: String = "", override val from: String = "", val noteId: String, val itemId: String) : Command

/** Someone opened the Board: new notes are no longer new. */
@Serializable @SerialName("board.seen")
data class BoardSeen(override val id: String = "", override val from: String = "") : Command

// Routines

/** Adds or replaces a routine. Steps that keep their id keep their tick. */
@Serializable @SerialName("routine.put")
data class RoutinePut(override val id: String = "", override val from: String = "", val routine: Routine) : Command

@Serializable @SerialName("routine.remove")
data class RoutineRemove(override val id: String = "", override val from: String = "", val routineId: String) : Command

@Serializable @SerialName("routine.step")
data class RoutineStep(override val id: String = "", override val from: String = "", val routineId: String, val stepId: String, val done: Boolean) : Command

@Serializable @SerialName("routine.reset")
data class RoutineReset(override val id: String = "", override val from: String = "", val routineId: String) : Command

// People, calendars, settings

@Serializable @SerialName("person.put")
data class PersonPut(override val id: String = "", override val from: String = "", val person: Person) : Command

@Serializable @SerialName("person.remove")
data class PersonRemove(override val id: String = "", override val from: String = "", val personId: String) : Command

/** [url] is a secret: it goes to the secrets store, never into the state. Null keeps the stored one. */
@Serializable @SerialName("calendar.put")
data class CalendarPut(override val id: String = "", override val from: String = "", val calendar: CalendarFeed, val url: String? = null) : Command

@Serializable @SerialName("calendar.remove")
data class CalendarRemove(override val id: String = "", override val from: String = "", val calendarId: String) : Command

/** A partial [dev.framio.hub.core.model.Settings] object, merged key by key: `{"night":{"start":"23:00"}}`. */
@Serializable @SerialName("settings.patch")
data class SettingsPatch(override val id: String = "", override val from: String = "", val patch: JsonObject) : Command
