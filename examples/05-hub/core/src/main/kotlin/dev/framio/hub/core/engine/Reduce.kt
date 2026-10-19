package dev.framio.hub.core.engine

import dev.framio.hub.core.board.Expiry
import dev.framio.hub.core.model.Deadline
import dev.framio.hub.core.model.HubState
import dev.framio.hub.core.model.Item
import dev.framio.hub.core.model.Note
import dev.framio.hub.core.model.NoteBody
import dev.framio.hub.core.model.Palette
import dev.framio.hub.core.model.Routine
import dev.framio.hub.core.model.Run
import dev.framio.hub.core.model.Timer
import dev.framio.hub.core.time.LocalTimes
import dev.framio.hub.core.time.contains
import dev.framio.hub.core.time.isValid
import dev.framio.hub.core.time.local
import dev.framio.hub.core.timers.Melodies
import dev.framio.hub.core.timers.deadlineIn
import dev.framio.hub.core.timers.isActive
import dev.framio.hub.core.timers.isLost
import dev.framio.hub.core.timers.rebased
import dev.framio.hub.core.timers.remainingMs

/** Limits and durations the reducer enforces. */
object Rules {
    /** A ringing timer goes quiet after this and becomes a "finished" chip. */
    const val RING_MS = 2 * 60_000L

    /** Finished chips nobody cleared go away after this. */
    const val DONE_KEEP_MS = 6 * 3_600_000L
    const val MAX_TIMER_MS = 24 * 3_600_000L
    const val MIN_TIMER_MS = 1_000L
    const val MAX_LABEL = 40
    const val MAX_TEXT = 500
    const val MAX_ITEMS = 50
    const val MAX_STEPS = 12
    const val MAX_NAME = 40
}

/**
 * The Hub's behaviour, as one pure function: no I/O, no Android, no clock reads. Everything a finger, a
 * phone or adb can do goes through here, which is how it's all tested on the JVM.
 */
fun reduce(state: HubState, input: Input, now: Now): Next {
    val step = when (input) {
        is Input.Wire -> wire(state, input.command, now)
        Input.Tick -> Step(tick(state, now))
        Input.DoubleKnock -> topRinging(state, now)?.let { Step(state.copy(timers = state.timers - it)) } ?: Step(state)
        Input.TimeTrusted -> Step(tick(fillWallTimes(state, now), now))
    }
    return finish(state, step, now)
}

private class Step(val state: HubState, val effects: List<Effect> = emptyList(), val rejected: String? = null)

private fun rejected(state: HubState, why: String) = Step(state, rejected = why)

private fun wire(state: HubState, c: Command, now: Now): Step {
    if (c.id.isNotEmpty() && c.id in state.seen) return rejected(state, "duplicate")
    val step = command(state, c, now)
    if (step.rejected != null || c.id.isEmpty()) return step
    return Step(step.state.copy(seen = (step.state.seen + c.id).takeLast(HubState.SEEN_MAX)), step.effects)
}

private fun command(s: HubState, c: Command, now: Now): Step = when (c) {
    is TimerStart -> when {
        c.ms !in Rules.MIN_TIMER_MS..Rules.MAX_TIMER_MS -> rejected(s, "duration must be 1 s to 24 h")
        s.timers.count { it.isActive } >= HubState.MAX_TIMERS -> rejected(s, "at most ${HubState.MAX_TIMERS} timers")
        s.timers.any { it.id == c.id } -> rejected(s, "timer exists")
        else -> {
            val active = s.timers.filter { it.isActive }
            val timer = Timer(
                id = c.id,
                label = c.label.trim().take(Rules.MAX_LABEL).ifEmpty { "Timer" },
                totalMs = c.ms,
                color = Palette.free(active.map { it.color }),
                melody = Palette.free(active.map { it.melody }, Melodies.count),
                run = Run.Running(now.deadlineIn(c.ms)),
            )
            Step(s.copy(timers = s.timers + timer))
        }
    }

    is TimerPause -> updateTimer(s, c.timerId) { t ->
        val run = t.run as? Run.Running ?: return@updateTimer null
        val remaining = run.until.remainingMs(now) ?: return@updateTimer null
        t.copy(run = Run.Paused(remaining.coerceAtLeast(0)))
    }

    is TimerResume -> updateTimer(s, c.timerId) { t ->
        val run = t.run as? Run.Paused ?: return@updateTimer null
        t.copy(run = Run.Running(now.deadlineIn(run.remainingMs)))
    }

    is TimerAdd -> if (c.ms !in Rules.MIN_TIMER_MS..Rules.MAX_TIMER_MS) {
        rejected(s, "duration must be 1 s to 24 h")
    } else {
        updateTimer(s, c.timerId) { t ->
            when (val run = t.run) {
                is Run.Running -> t.copy(run = Run.Running(run.until.copy(elapsedMs = run.until.elapsedMs + c.ms, wallMs = run.until.wallMs?.plus(c.ms))), totalMs = t.totalMs + c.ms)
                is Run.Paused -> t.copy(run = Run.Paused(run.remainingMs + c.ms), totalMs = t.totalMs + c.ms)
                is Run.Ringing, is Run.Done, Run.Interrupted -> t.copy(run = Run.Running(now.deadlineIn(c.ms)), totalMs = c.ms)
            }
        }
    }

    is TimerCancel -> if (s.timers.any { it.id == c.timerId }) Step(s.copy(timers = s.timers.filterNot { it.id == c.timerId })) else rejected(s, "no such timer")

    is TimerStop -> {
        val target = if (c.timerId != null) s.timers.firstOrNull { it.id == c.timerId } else topRinging(s, now)
        when {
            target == null -> rejected(s, "nothing is ringing")
            target.run is Run.Ringing || target.run is Run.Done -> Step(s.copy(timers = s.timers - target))
            else -> rejected(s, "timer isn't ringing")
        }
    }

    is TimerClear -> Step(s.copy(timers = s.timers.filter { it.isActive }))

    is NoteAdd -> {
        val problem = noteProblem(c.body)
        when {
            problem != null -> rejected(s, problem)
            s.notes.size >= HubState.MAX_NOTES -> rejected(s, "the board is full")
            s.notes.any { it.id == c.id } -> rejected(s, "note exists")
            else -> {
                val body = normalize(c.body, c.id)
                val note = Note(
                    id = c.id,
                    body = body,
                    author = c.author?.trim()?.take(Rules.MAX_NAME)?.ifEmpty { null },
                    keep = c.keep,
                    createdWallMs = now.wallMs,
                    expiresWallMs = now.wallMs?.let { Expiry.expiresAt(c.keep, it, now.zone) },
                )
                val cue = if (c.from != FROM_FRAME && !isQuiet(s, now)) listOf(Effect.Play(Cue.NoteArrived)) else emptyList()
                Step(s.copy(notes = s.notes + note), cue)
            }
        }
    }

    is NoteRemove -> {
        val note = s.notes.firstOrNull { it.id == c.noteId }
        if (note == null) {
            rejected(s, "no such note")
        } else {
            val cleanup = (note.body as? NoteBody.Scribble)?.let { listOf(Effect.DeleteScribble(it.fileId)) } ?: emptyList()
            Step(s.copy(notes = s.notes - note), cleanup)
        }
    }

    is NoteKeep -> updateNote(s, c.noteId) { n ->
        n.copy(keep = c.keep, expiresWallMs = n.createdWallMs?.let { Expiry.expiresAt(c.keep, it, now.zone) })
    }

    is ItemAdd -> updateChecklist(s, c.noteId) { list ->
        val text = c.text.trim().take(Rules.MAX_LABEL * 3)
        if (text.isEmpty() || list.items.size >= Rules.MAX_ITEMS) null else list.copy(items = list.items + Item(c.id.ifEmpty { "i${list.items.size}" }, text))
    }

    is ItemCheck -> updateChecklist(s, c.noteId) { list ->
        if (list.items.none { it.id == c.itemId }) null else list.copy(items = list.items.map { if (it.id == c.itemId) it.copy(done = c.done) else it })
    }

    is ItemRemove -> updateChecklist(s, c.noteId) { list ->
        if (list.items.none { it.id == c.itemId }) null else list.copy(items = list.items.filterNot { it.id == c.itemId })
    }

    is BoardSeen -> Step(s.copy(boardSeenWallMs = now.wallMs ?: s.boardSeenWallMs))

    is RoutinePut -> {
        val r = c.routine
        val existing = s.routines.firstOrNull { it.id == r.id }
        when {
            r.id.isBlank() || r.title.isBlank() -> rejected(s, "a routine needs an id and a title")
            r.steps.isEmpty() || r.steps.size > Rules.MAX_STEPS -> rejected(s, "a routine has 1 to ${Rules.MAX_STEPS} steps")
            r.steps.map { it.id }.toSet().size != r.steps.size -> rejected(s, "step ids must be unique")
            !LocalTimes.isValid(r.resetAt) || (r.window != null && !r.window.isValid()) -> rejected(s, "times must be HH:mm")
            existing == null && s.routines.size >= HubState.MAX_ROUTINES -> rejected(s, "at most ${HubState.MAX_ROUTINES} routines")
            else -> {
                val ticks = existing?.steps?.associate { it.id to it.done } ?: emptyMap()
                val routine = r.copy(
                    title = r.title.trim().take(Rules.MAX_LABEL),
                    steps = r.steps.map { it.copy(label = it.label.trim().take(Rules.MAX_LABEL), done = ticks[it.id] ?: false) },
                    lastResetDay = existing?.lastResetDay ?: now.wallMs?.let { LocalTimes.resetDay(r.resetAt, it, now.zone) },
                )
                Step(s.copy(routines = if (existing == null) s.routines + routine else s.routines.map { if (it.id == r.id) routine else it }))
            }
        }
    }

    is RoutineRemove -> if (s.routines.any { it.id == c.routineId }) Step(s.copy(routines = s.routines.filterNot { it.id == c.routineId })) else rejected(s, "no such routine")

    is RoutineStep -> {
        val routine = s.routines.firstOrNull { it.id == c.routineId }
        if (routine == null || routine.steps.none { it.id == c.stepId }) {
            rejected(s, "no such step")
        } else {
            val updated = routine.copy(steps = routine.steps.map { if (it.id == c.stepId) it.copy(done = c.done) else it })
            val finished = c.done && updated.steps.all { it.done } && !routine.steps.all { it.done }
            val cue = when {
                !c.done -> emptyList()
                finished -> listOf(Effect.Play(Cue.RoutineDone))
                else -> listOf(Effect.Play(Cue.StepDone))
            }
            Step(s.copy(routines = s.routines.map { if (it.id == routine.id) updated else it }), cue)
        }
    }

    is RoutineReset -> {
        val routine = s.routines.firstOrNull { it.id == c.routineId }
        if (routine == null) rejected(s, "no such routine") else Step(s.copy(routines = s.routines.map { if (it.id == routine.id) untick(it) else it }))
    }

    is PersonPut -> {
        val p = c.person
        val exists = s.people.any { it.id == p.id }
        when {
            p.id.isBlank() || p.name.isBlank() -> rejected(s, "a person needs an id and a name")
            !exists && s.people.size >= HubState.MAX_PEOPLE -> rejected(s, "at most ${HubState.MAX_PEOPLE} people")
            else -> {
                val person = p.copy(name = p.name.trim().take(Rules.MAX_NAME), color = p.color.mod(Palette.colors.size))
                Step(s.copy(people = if (exists) s.people.map { if (it.id == p.id) person else it } else s.people + person))
            }
        }
    }

    is PersonRemove -> if (s.people.none { it.id == c.personId }) {
        rejected(s, "no such person")
    } else {
        Step(
            s.copy(
                people = s.people.filterNot { it.id == c.personId },
                routines = s.routines.map { if (it.personId == c.personId) it.copy(personId = null) else it },
                calendars = s.calendars.map { if (it.personId == c.personId) it.copy(personId = null) else it },
            ),
        )
    }

    is CalendarPut -> {
        val feed = c.calendar
        val exists = s.calendars.any { it.id == feed.id }
        val url = c.url?.trim()?.let { if (it.startsWith("webcal://")) "https://" + it.removePrefix("webcal://") else it }
        when {
            feed.id.isBlank() || feed.name.isBlank() -> rejected(s, "a calendar needs an id and a name")
            url != null && !url.startsWith("https://") -> rejected(s, "calendar links must be https:// or webcal://")
            !exists && url == null -> rejected(s, "a new calendar needs its link")
            !exists && s.calendars.size >= HubState.MAX_CALENDARS -> rejected(s, "at most ${HubState.MAX_CALENDARS} calendars")
            else -> {
                val clean = feed.copy(name = feed.name.trim().take(Rules.MAX_NAME))
                val calendars = if (exists) s.calendars.map { if (it.id == feed.id) clean else it } else s.calendars + clean
                Step(s.copy(calendars = calendars), url?.let { listOf(Effect.PutSecret(calendarSecret(feed.id), it)) } ?: emptyList())
            }
        }
    }

    is CalendarRemove -> if (s.calendars.none { it.id == c.calendarId }) {
        rejected(s, "no such calendar")
    } else {
        Step(s.copy(calendars = s.calendars.filterNot { it.id == c.calendarId }), listOf(Effect.PutSecret(calendarSecret(c.calendarId), null)))
    }

    is SettingsPatch -> SettingsRules.merge(s.settings, c.patch).fold(
        onSuccess = { merged -> SettingsRules.problem(merged)?.let { rejected(s, it) } ?: Step(s.copy(settings = merged)) },
        onFailure = { rejected(s, "bad settings: ${it.message?.lineSequence()?.firstOrNull()}") },
    )
}

/** The secrets-store key for a calendar's URL. */
fun calendarSecret(calendarId: String) = "calendar.$calendarId"

/** Commands the frame's own UI sends carry this `from`. */
const val FROM_FRAME = "frame"

private fun updateTimer(s: HubState, id: String, change: (Timer) -> Timer?): Step {
    val timer = s.timers.firstOrNull { it.id == id } ?: return rejected(s, "no such timer")
    val updated = change(timer) ?: return rejected(s, "not possible in this state")
    return Step(s.copy(timers = s.timers.map { if (it.id == id) updated else it }))
}

private fun updateNote(s: HubState, id: String, change: (Note) -> Note?): Step {
    val note = s.notes.firstOrNull { it.id == id } ?: return rejected(s, "no such note")
    val updated = change(note) ?: return rejected(s, "not possible for this note")
    return Step(s.copy(notes = s.notes.map { if (it.id == id) updated else it }))
}

private fun updateChecklist(s: HubState, id: String, change: (NoteBody.Checklist) -> NoteBody.Checklist?): Step =
    updateNote(s, id) { n -> (n.body as? NoteBody.Checklist)?.let(change)?.let { n.copy(body = it) } }

private fun noteProblem(body: NoteBody): String? = when (body) {
    is NoteBody.Text -> if (body.text.isBlank()) "empty note" else if (body.text.length > Rules.MAX_TEXT) "note too long" else null
    is NoteBody.Checklist -> when {
        body.title.isBlank() && body.items.isEmpty() -> "empty checklist"
        body.items.size > Rules.MAX_ITEMS -> "too many items"
        else -> null
    }
    is NoteBody.Scribble -> if (body.fileId.isBlank() || body.strokeCount <= 0) "empty scribble" else null
}

/** Trims text and gives checklist items ids derived from the note's id when they have none. */
private fun normalize(body: NoteBody, noteId: String): NoteBody = when (body) {
    is NoteBody.Text -> body.copy(text = body.text.trim())
    is NoteBody.Checklist -> body.copy(
        title = body.title.trim().take(Rules.MAX_LABEL),
        items = body.items.mapIndexed { i, item -> item.copy(id = item.id.ifBlank { "$noteId.$i" }, text = item.text.trim().take(Rules.MAX_LABEL * 3)) },
    )
    is NoteBody.Scribble -> body
}

private fun untick(r: Routine) = r.copy(steps = r.steps.map { it.copy(done = false) })

private fun isQuiet(s: HubState, now: Now): Boolean {
    val quiet = s.settings.quiet ?: return false
    val local = now.local() ?: return false
    return quiet.contains(local.toLocalTime())
}

/** The timer whose alert is showing: the one that started ringing last. */
fun topRinging(s: HubState, now: Now): Timer? =
    s.timers.filter { it.run is Run.Ringing }.maxByOrNull { (it.run as Run.Ringing).since.remainingMs(now) ?: Long.MIN_VALUE }

/** Time passed: timers end, go quiet or are lost; notes expire; routines reset. */
private fun tick(s: HubState, now: Now): HubState {
    val timers = s.timers.mapNotNull { t -> tickTimer(t, now) }
    val wall = now.wallMs
    val notes = if (wall == null) s.notes else s.notes.filterNot { Expiry.isExpired(it, wall) }
    val routines = if (wall == null) {
        s.routines
    } else {
        s.routines.map { r ->
            val day = LocalTimes.resetDay(r.resetAt, wall, now.zone)
            when (r.lastResetDay) {
                day -> r
                null -> r.copy(lastResetDay = day)
                else -> untick(r).copy(lastResetDay = day)
            }
        }
    }
    return s.copy(timers = timers, notes = notes, routines = routines)
}

private fun tickTimer(t: Timer, now: Now): Timer? = when (val run = t.run) {
    is Run.Running -> {
        val remaining = run.until.remainingMs(now)
        when {
            remaining == null -> if (run.until.isLost(now)) t.copy(run = Run.Interrupted) else t
            remaining > 0 -> t.copy(run = Run.Running(run.until.rebased(now)))
            -remaining >= Rules.RING_MS -> t.copy(run = Run.Done(run.until.rebased(now))) // ended while the Hub wasn't running
            else -> t.copy(run = Run.Ringing(run.until.rebased(now)))
        }
    }
    is Run.Ringing -> {
        val age = run.since.remainingMs(now)?.let { -it }
        when {
            age == null -> if (run.since.isLost(now)) t.copy(run = Run.Interrupted) else t
            age >= Rules.RING_MS -> t.copy(run = Run.Done(run.since.rebased(now)))
            else -> t.copy(run = Run.Ringing(run.since.rebased(now)))
        }
    }
    is Run.Done -> {
        val age = run.at.remainingMs(now)?.let { -it }
        if (age != null && age >= Rules.DONE_KEEP_MS) null else t
    }
    is Run.Paused, Run.Interrupted -> t
}

/** The clock was just trusted: notes and deadlines made before that get their wall times. */
private fun fillWallTimes(s: HubState, now: Now): HubState {
    val wall = now.wallMs ?: return s
    fun Deadline.filled() = if (wallMs == null && bootId == now.bootId) rebased(now) else this
    return s.copy(
        timers = s.timers.map { t ->
            when (val run = t.run) {
                is Run.Running -> t.copy(run = Run.Running(run.until.filled()))
                is Run.Ringing -> t.copy(run = Run.Ringing(run.since.filled()))
                is Run.Done -> t.copy(run = Run.Done(run.at.filled()))
                else -> t
            }
        },
        notes = s.notes.map { n ->
            if (n.createdWallMs != null) n else n.copy(createdWallMs = wall, expiresWallMs = Expiry.expiresAt(n.keep, wall, now.zone))
        },
    )
}

/** Bumps rev on a real change, and turns a change of the ringing stack into Ring/Silence. */
private fun finish(old: HubState, step: Step, now: Now): Next {
    var next = step.state
    val effects = step.effects.toMutableList()
    val changed = next.copy(rev = old.rev, seen = old.seen) != old
    if (changed) {
        next = next.copy(rev = old.rev + 1)
        effects += Effect.Changed(next.rev)
    }
    val before = topRinging(old, now)
    val after = topRinging(next, now)
    if (after != null && after.id != before?.id) effects.add(0, Effect.Ring(after.id, after.melody))
    if (after == null && before != null) effects.add(0, Effect.Silence)
    return Next(next, effects, step.rejected)
}
