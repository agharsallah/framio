package dev.framio.pomodoro.core.engine

import dev.framio.pomodoro.core.model.CueStyle
import dev.framio.pomodoro.core.model.Display
import dev.framio.pomodoro.core.model.IntentionRule
import dev.framio.pomodoro.core.model.Mode
import dev.framio.pomodoro.core.model.ModeRules
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.ParkedThought
import dev.framio.pomodoro.core.model.Presets
import dev.framio.pomodoro.core.model.Rhythm
import dev.framio.pomodoro.core.model.SessionId
import dev.framio.pomodoro.core.model.SessionRecord
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.core.model.Task
import dev.framio.pomodoro.core.model.TaskId
import dev.framio.pomodoro.core.rhythm.BreakKind
import dev.framio.pomodoro.core.rhythm.BreakPolicy
import dev.framio.pomodoro.core.rhythm.FlowRhythm
import dev.framio.pomodoro.core.time.Now
import dev.framio.pomodoro.core.time.durationUntil
import dev.framio.pomodoro.core.time.rebased
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The whole focus loop as one pure function: no Android, no clock but [now], no I/O. Every state change and
 * every effect follows from (state, input, now). Invalid inputs return the state unchanged with a reason.
 *
 * On top of the input's own effects (log, cues, bring-to-front) it derives the rest by comparing before and
 * after: wake, service, display, soundscape, snapshot and settings writes (see [Derive]).
 */
public fun reduce(state: FocusState, input: Input, now: Now, config: EngineConfig = EngineConfig.Default): Next {
    if (!state.loaded && input !is Input.Restore) return Next(state, rejected = "not loaded yet")
    val step = Reducer(now, config).step(rollDay(state, now), input)
    if (step.rejected != null) return Next(state, rejected = step.rejected)
    if (input !is Input.Restore) return Next(step.state, step.effects + Derive.effects(state, step.state, now, config))
    // After a restore, sync everything, and write back only what resolving or repairing changed
    val after = step.state
    val writes = listOfNotNull(
        Effect.Persist(after.snapshot(savedAt = now.moment)).takeIf { after.snapshot() != (input.snapshot?.copy(savedAt = null) ?: Snapshot()) },
        Effect.SaveSettings(after.settings).takeIf { after.settings != input.settings },
    )
    return Next(after, step.effects + Derive.effects(null, after, now, config) + writes)
}

/** A new local day resets the daily count (sessions finished while the clock was unknown stay on the first day). */
internal fun rollDay(s: FocusState, now: Now): FocusState {
    val date = now.date ?: return s
    if (s.today == date) return s
    return s.copy(today = date, completedToday = if (s.today == null) s.completedToday else 0)
}

private class Step(val state: FocusState, val effects: List<Effect> = emptyList(), val rejected: String? = null)

private class Reducer(private val now: Now, private val config: EngineConfig) {

    fun step(s: FocusState, input: Input): Step = when (input) {
        is Input.Restore -> restore(input)
        Input.TimeTrusted -> Step(rebaseAll(s))
        Input.DeadlineReached -> deadline(s)
        is Input.SelectMode -> selectMode(s, input)
        is Input.OpenRitual -> openRitual(s, input)
        is Input.SetRitualSkip -> setRitualSkip(s, input)
        Input.CancelRitual -> cancelRitual(s)
        is Input.Begin -> beginInput(s, input)
        Input.Pause -> pause(s)
        Input.Resume -> resume(s)
        is Input.EndEarly -> endEarly(s, input.resumeNote, input.resumeInk)
        Input.Complete -> complete(s)
        is Input.Extend -> extend(s, input.by)
        is Input.Park -> park(s, input.ink)
        is Input.ClearParked -> clearParked(s, input.index)
        is Input.WrapUp -> wrapUp(s, input)
        Input.SkipBreak -> skipBreak(s)
        is Input.ExtendBreak -> extendBreak(s, input.by)
        Input.StartNext -> startNext(s)
        Input.Home -> home(s)
        Input.DismissNotice -> Step(s.copy(notice = null))
        is Input.AddTask -> addTask(s, input)
        is Input.SetEstimate -> setEstimate(s, input)
        is Input.RemoveTask -> removeTask(s, input)
        is Input.ChangeSettings -> Step(s.copy(settings = input.settings.normalized()))
        is Input.SaveMode -> saveMode(s, input.mode)
        is Input.DeleteMode -> deleteMode(s, input)
        is Input.MoveMode -> moveMode(s, input)
    }

    private fun reject(reason: String) = Step(FocusState(), rejected = reason)

    private fun wrongPhase(s: FocusState, input: String) = reject("$input not possible in ${s.phase::class.simpleName}")

    // Start-up

    private fun restore(input: Input.Restore): Step {
        val settings = input.settings.normalized()
        val snap = input.snapshot ?: return Step(rollDay(FocusState(loaded = true, settings = settings), now))
        val consistent = when {
            snap.phase == Phase.Idle -> snap.copy(run = null, session = null)
            snap.run == null -> Snapshot(
                today = snap.today, completedToday = snap.completedToday, lastResumeNote = snap.lastResumeNote, lastResumeInk = snap.lastResumeInk, tasks = snap.tasks,
                rituals = snap.rituals, parkedLot = snap.parkedLot + snap.session?.parked.orEmpty(),
            )
            else -> snap
        }
        val s = rebaseAll(rollDay(FocusState.of(consistent, settings), now))
        return when (val p = s.phase) {
            is Phase.Focus -> {
                val left = p.deadline?.let { now.until(it) ?: return interrupt(s, p.startedAt.durationUntil(snap.savedAt ?: p.startedAt)) }
                when {
                    left != null && !left.isPositive() -> finishFocus(s, p)
                    left == null && now.since(p.startedAt) == null -> interrupt(s, p.startedAt.durationUntil(snap.savedAt ?: p.startedAt))
                    else -> Step(s)
                }
            }
            is Phase.Paused -> {
                val lost = p.capAt != null && now.until(p.capAt) == null
                Step(if (lost) s.copy(phase = p.copy(capAt = null, overCap = true)) else s)
            }
            is Phase.Break -> {
                val left = now.until(p.deadline)
                if (left == null || !left.isPositive()) {
                    // The break ran out while the app was dead: wait on "Ready?" rather than start a block unseen
                    val logged = logPending(s)
                    Step(dropSession(logged.state).copy(phase = Phase.Ready), logged.effects)
                } else {
                    Step(s)
                }
            }
            else -> Step(s)
        }
    }

    /** Fills in wall times for this boot's moments once the clock is trusted, and moves older ones into this boot. */
    private fun rebaseAll(s: FocusState): FocusState {
        val phase = when (val p = s.phase) {
            is Phase.Focus -> p.copy(deadline = p.deadline?.rebased(now), startedAt = p.startedAt.rebased(now))
            is Phase.Paused -> p.copy(since = p.since.rebased(now), capAt = p.capAt?.rebased(now))
            is Phase.Break -> p.copy(deadline = p.deadline.rebased(now))
            else -> p
        }
        val session = s.session?.let { it.copy(start = it.start.rebased(now)) }
        return s.copy(phase = phase, session = session)
    }

    // Time

    private fun deadline(s: FocusState): Step = when (val p = s.phase) {
        is Phase.Focus -> {
            val rhythm = s.mode.rhythm
            if (p.deadline != null) {
                val left = now.until(p.deadline)
                when {
                    left == null -> interrupt(s, null)
                    !left.isPositive() -> finishFocus(s, p)
                    else -> Step(s)
                }
            } else {
                val session = s.session
                val focused = now.since(p.startedAt)?.let(config::logical)
                when {
                    focused == null -> interrupt(s, null)
                    // The nudge is a glow and a line of copy on the Flow screen, never a sound (no alarm mid-flow)
                    rhythm is Rhythm.Flow && session != null && !session.nudged && FlowRhythm.nudgeDue(rhythm, focused) ->
                        Step(s.copy(session = session.copy(nudged = true)))
                    else -> Step(s)
                }
            }
        }
        is Phase.Paused -> {
            val cap = p.capAt
            val left = cap?.let { now.until(it) }
            if (cap != null && !p.overCap && (left == null || !left.isPositive())) {
                Step(s.copy(phase = p.copy(overCap = true)), cue(s.mode, CueKind.PauseCap))
            } else {
                Step(s)
            }
        }
        is Phase.Break -> {
            val left = now.until(p.deadline)
            if (left == null || !left.isPositive()) endBreak(s, announce = true) else Step(s)
        }
        else -> Step(s)
    }

    // Home and ritual

    private fun selectMode(s: FocusState, input: Input.SelectMode): Step {
        if (s.settings.mode(input.id) == null) return reject("unknown mode ${input.id.value}")
        val settings = s.settings.copy(selectedMode = input.id)
        return when (s.phase) {
            Phase.Idle -> Step(s.copy(settings = settings))
            is Phase.Ritual -> Step(s.copy(settings = settings, phase = Phase.Ritual(input.id, s.phase.intention), run = null))
            else -> wrongPhase(s, "SelectMode")
        }
    }

    private fun openRitual(s: FocusState, input: Input.OpenRitual): Step {
        val intention = input.intention?.trim()?.takeIf { it.isNotEmpty() }?.take(Task.MAX_TITLE)
        return when (s.phase) {
            Phase.Idle -> Step(s.copy(phase = Phase.Ritual(s.settings.selected.id, intention), run = null, notice = null))
            Phase.Ready -> Step(s.copy(phase = Phase.Ritual(s.mode.id, intention ?: s.run?.intent?.intention)))
            else -> wrongPhase(s, "OpenRitual")
        }
    }

    private fun setRitualSkip(s: FocusState, input: Input.SetRitualSkip): Step {
        val mode = s.settings.mode(input.mode) ?: s.run?.mode?.takeIf { it.id == input.mode } ?: return reject("unknown mode ${input.mode.value}")
        val memory = s.ritual(mode.id) ?: RitualMemory(mode.id)
        if (input.skip && (!mode.ritual.hasScreen || !mode.ritual.skippable)) return reject("${mode.name}'s ritual can't be skipped")
        if (input.skip && memory.runs == 0) return reject("the ritual can be skipped after its first run")
        return Step(s.copy(rituals = remember(s.rituals, memory.copy(skip = input.skip))))
    }

    /** Puts [memory] first, replacing the mode's old one; keeps a bounded list. */
    private fun remember(rituals: List<RitualMemory>, memory: RitualMemory): List<RitualMemory> =
        (listOf(memory) + rituals.filter { it.mode != memory.mode }).take(MAX_RITUALS)

    private fun cancelRitual(s: FocusState): Step {
        if (s.phase !is Phase.Ritual) return wrongPhase(s, "CancelRitual")
        return Step(s.copy(phase = if (s.run != null) Phase.Ready else Phase.Idle))
    }

    private fun beginInput(s: FocusState, input: Input.Begin): Step {
        val p = s.phase
        val base = when {
            p is Phase.Ritual && s.run?.mode?.id == p.modeId -> s.mode
            p is Phase.Ritual -> s.settings.mode(p.modeId) ?: s.settings.selected
            p == Phase.Ready -> s.mode
            p == Phase.Idle -> s.settings.selected
            else -> return wrongPhase(s, "Begin")
        }
        val mode = withDisplay(base, input.display)
        val override = input.focusOverride
        if (override != null && (!override.isPositive() || override > MAX_BLOCK)) return reject("focus length out of range")
        val rule = mode.ritual.intention
        var tasks = s.tasks
        val task: Task? = when {
            rule == IntentionRule.Off -> null
            input.taskId != null -> s.task(input.taskId) ?: return reject("unknown task ${input.taskId.value}")
            !input.intention.isNullOrBlank() -> {
                val title = input.intention.trim().take(Task.MAX_TITLE)
                tasks.firstOrNull { it.title.equals(title, ignoreCase = true) } ?: Task(newTaskId(tasks), title).also { tasks = listOf(it) + tasks }
            }
            else -> null
        }
        val intention = if (rule == IntentionRule.Off) null else input.intention?.trim()?.takeIf { it.isNotEmpty() } ?: task?.title
        if (rule == IntentionRule.Required && intention == null) return reject("this mode needs an intention")
        val ifThen = input.ifThen?.trim()?.takeIf { it.isNotEmpty() && mode.ritual.ifThen }?.take(MAX_IF_THEN)
        val intent = Intent(intention, ifThen, task?.id)
        val continuing = s.run != null && s.run.mode.id == mode.id && p != Phase.Idle
        val run = if (continuing) s.run.copy(mode = mode, intent = intent) else Run(mode, intent)
        val rituals = input.checked?.let { checked ->
            val old = s.ritual(mode.id) ?: RitualMemory(mode.id)
            // Only the mode's own items, in its order
            val ticks = mode.ritual.checklist.map { it.id }.filter { it in checked }
            remember(s.rituals, old.copy(ticks = ticks, ifThen = ifThen, runs = old.runs + 1, display = input.display ?: old.display))
        } ?: s.rituals
        return begin(s.copy(tasks = touch(tasks, task?.id), rituals = rituals), run, override)
    }

    /** A dark mode (Calm or Blackout) run with the other dark display; Full modes and null leave [mode] as it is. */
    private fun withDisplay(mode: Mode, display: Display?): Mode {
        val current = mode.environment.display
        if (display == null || current == Display.Full || display == Display.Full || display == current) return mode
        return mode.copy(environment = mode.environment.copy(display = display))
    }

    /** Starts a block of [run]; a fixed rhythm's length can be overridden once. */
    private fun begin(s: FocusState, run: Run, override: Duration?): Step {
        val rhythm = run.mode.rhythm
        val planned = when (rhythm) {
            is Rhythm.Fixed -> override ?: rhythm.focus
            is Rhythm.Flow -> null
        }
        val id = SessionId("${now.bootId.take(8)}-${now.elapsedMs}")
        val session = ActiveSession(id = id, start = now.moment, planned = planned)
        val phase = Phase.Focus(id, planned?.let { now + config.real(it) }, now.moment)
        return Step(s.copy(phase = phase, run = run, session = session, notice = null))
    }

    private fun startNext(s: FocusState): Step {
        val run = s.run
        if (s.phase != Phase.Ready || run == null) return wrongPhase(s, "StartNext")
        return next(s, run)
    }

    /** The next block of [run] with the same intention (the task may have been removed meanwhile). */
    private fun next(s: FocusState, run: Run): Step {
        val task = s.task(run.intent.task)
        val intent = if (task == null) run.intent.copy(task = null) else run.intent
        return begin(s.copy(tasks = touch(s.tasks, task?.id)), run.copy(intent = intent), null)
    }

    // During focus

    private fun pause(s: FocusState): Step {
        val p = s.phase as? Phase.Focus ?: return wrongPhase(s, "Pause")
        val session = s.session ?: return wrongPhase(s, "Pause")
        val left = p.deadline?.let { now.until(it) }
        if (left != null && !left.isPositive()) return finishFocus(s, p)
        val elapsed = (now.since(p.startedAt) ?: Duration.ZERO).coerceAtLeast(Duration.ZERO)
        val cap = s.mode.rhythm.pauseCap?.let { now + config.real(it) }
        return Step(s.copy(phase = Phase.Paused(session.id, left, elapsed, now.moment, cap)))
    }

    private fun resume(s: FocusState): Step {
        val p = s.phase as? Phase.Paused ?: return wrongPhase(s, "Resume")
        return Step(s.copy(phase = Phase.Focus(p.session, p.remaining?.let { now + it }, now.moment - p.elapsed)))
    }

    private fun endEarly(s: FocusState, note: String?, ink: String?): Step {
        val focused = when (val p = s.phase) {
            is Phase.Focus -> now.since(p.startedAt)
            is Phase.Paused -> p.elapsed
            else -> return wrongPhase(s, "EndEarly")
        }
        val resume = note?.trim()?.takeIf { it.isNotEmpty() }
        val resumeInk = ink?.takeIf { it.isNotEmpty() && it.length <= MAX_INK }
        val session = s.session?.copy(focused = config.logical((focused ?: Duration.ZERO).coerceAtLeast(Duration.ZERO)))
        val record = session?.let { record(s, it, Outcome.EndedEarly, null, resume, resumeInk) }
        return Step(
            dropSession(s).copy(phase = Phase.Idle, run = null).withResume(resume, resumeInk),
            listOfNotNull(record?.let(Effect::AppendLog)),
        )
    }

    private fun complete(s: FocusState): Step {
        if (s.mode.rhythm !is Rhythm.Flow) return reject("Complete is for Flow; end a fixed block with EndEarly")
        return when (val p = s.phase) {
            is Phase.Focus -> finishFocus(s, p)
            is Phase.Paused -> finishAfter(s, p.elapsed)
            else -> wrongPhase(s, "Complete")
        }
    }

    private fun extend(s: FocusState, by: Duration): Step {
        if (!by.isPositive() || by > 1.hours) return reject("extend by 1 s to 1 h")
        val session = s.session ?: return wrongPhase(s, "Extend")
        val planned = session.planned ?: return reject("Flow has no end to extend")
        val phase = when (val p = s.phase) {
            is Phase.Focus -> p.copy(deadline = p.deadline?.plus(config.real(by)))
            is Phase.Paused -> p.copy(remaining = p.remaining?.plus(config.real(by)))
            else -> return wrongPhase(s, "Extend")
        }
        return Step(s.copy(phase = phase, session = session.copy(planned = planned + by)))
    }

    private fun park(s: FocusState, ink: String): Step {
        if (s.phase !is Phase.Focus && s.phase !is Phase.Paused) return wrongPhase(s, "Park")
        val session = s.session ?: return wrongPhase(s, "Park")
        if (ink.isEmpty() || ink.length > MAX_INK) return reject("empty or oversized ink")
        if (session.parked.size >= MAX_PARKED) return reject("parking lot is full")
        val thought = ParkedThought(ink, now.moment)
        val at = Timing.focused(s, now, config) ?: Duration.ZERO
        return Step(s.copy(session = session.copy(parked = session.parked + thought)), listOf(Effect.ThoughtParked(session.id, thought, at)))
    }

    private fun clearParked(s: FocusState, index: Int?): Step {
        val session = s.session
        val current = session != null && (s.phase is Phase.Focus || s.phase is Phase.Paused || s.phase is Phase.WrapUp || s.phase is Phase.Break)
        val list = if (current) session.parked else s.parkedLot
        if (index != null && index !in list.indices) return reject("no parked thought $index")
        val left = if (index == null) emptyList() else list.filterIndexed { i, _ -> i != index }
        val cleared = if (index == null) list else listOf(list[index])
        val effects = if (cleared.isEmpty()) emptyList() else listOf(Effect.ThoughtsCleared(cleared))
        return Step(if (current) s.copy(session = session.copy(parked = left)) else s.copy(parkedLot = left), effects)
    }

    /** The block is over for good: its parked thoughts that weren't cleared join the kept ones. */
    private fun dropSession(s: FocusState): FocusState {
        val parked = s.session?.parked.orEmpty()
        return s.copy(session = null, parkedLot = if (parked.isEmpty()) s.parkedLot else (s.parkedLot + parked).takeLast(MAX_PARKED))
    }

    /** Focus ran to [p]'s deadline (or Flow stopped now): count it and go to the wrap-up or the break. */
    private fun finishFocus(s: FocusState, p: Phase.Focus): Step {
        val real = if (p.deadline != null) p.startedAt.durationUntil(p.deadline) else now.since(p.startedAt)
        return finishAfter(s, (real ?: Duration.ZERO).coerceAtLeast(Duration.ZERO))
    }

    private fun finishAfter(s: FocusState, real: Duration): Step {
        val run = s.run ?: return reject("no run")
        val session = s.session ?: return reject("no session")
        val focused = config.logical(real)
        val cycle = run.cycle + 1
        val plan = BreakPolicy.after(run.mode.rhythm, cycle, focused)
        val done = s.copy(
            run = run.copy(cycle = cycle),
            session = session.copy(focused = focused),
            completedToday = s.completedToday + 1,
        )
        val effects = cue(run.mode, CueKind.FocusEnd) + Effect.BringToFront
        val phase = if (run.mode.rhythm.autoBreak) {
            breakPhase(plan.kind, plan.length, cycle, pendingWrapUp = true)
        } else {
            Phase.WrapUp(session.id, plan.kind, plan.length)
        }
        return Step(done.copy(phase = phase), effects)
    }

    private fun breakPhase(kind: BreakKind, length: Duration, cycle: Int, pendingWrapUp: Boolean) =
        Phase.Break(kind, now + config.real(length), length, BreakPrompt.forBreak(kind, cycle), pendingWrapUp)

    private fun interrupt(s: FocusState, real: Duration?): Step {
        val session = s.session?.copy(focused = config.logical((real ?: Duration.ZERO).coerceAtLeast(Duration.ZERO)))
        val record = session?.let { record(s, it, Outcome.Interrupted, null, null, null) }
        return Step(
            dropSession(s).copy(phase = Phase.Idle, run = null, notice = Notice.Interrupted),
            listOfNotNull(record?.let(Effect::AppendLog)),
        )
    }

    // After focus

    private fun wrapUp(s: FocusState, input: Input.WrapUp): Step {
        if (input.rating != null && input.rating !in 1..5) return reject("rating is 1..5")
        if (input.outcome == Outcome.EndedEarly || input.outcome == Outcome.Interrupted) return reject("not a wrap-up outcome")
        val session = s.session ?: return wrongPhase(s, "WrapUp")
        val note = input.resumeNote?.trim()?.takeIf { it.isNotEmpty() }
        val ink = input.resumeInk?.takeIf { it.isNotEmpty() && it.length <= MAX_INK }
        val outcome = input.outcome ?: Outcome.Unrated
        val p = s.phase
        val pending = (p is Phase.WrapUp || (p is Phase.Break && p.pendingWrapUp)) && !session.logged
        if (!pending) return wrongPhase(s, "WrapUp")
        val record = record(s, session, outcome, input.rating, note, ink) ?: return reject("no run")
        val logged = s.copy(session = session.copy(logged = true)).withResume(note, ink)
        val phase = when (p) {
            is Phase.WrapUp -> breakPhase(p.next, p.breakFor, s.run?.cycle ?: 1, pendingWrapUp = false)
            is Phase.Break -> p.copy(pendingWrapUp = false)
        }
        return Step(logged.copy(phase = phase), listOf(Effect.AppendLog(record)))
    }

    private fun skipBreak(s: FocusState): Step = when (s.phase) {
        is Phase.Break, is Phase.WrapUp -> endBreak(s, announce = false)
        else -> wrongPhase(s, "SkipBreak")
    }

    private fun extendBreak(s: FocusState, by: Duration): Step {
        val p = s.phase as? Phase.Break ?: return wrongPhase(s, "ExtendBreak")
        if (!by.isPositive() || by > MAX_BREAK_STEP) return reject("extend a break by 1 s to 30 min")
        if (p.length + by > MAX_BREAK) return reject("a break lasts at most 90 min")
        return Step(s.copy(phase = p.copy(deadline = p.deadline + config.real(by), length = p.length + by)))
    }

    /** The break is over: log an unanswered wrap-up, then start the next block or wait on "Ready?". */
    private fun endBreak(s: FocusState, announce: Boolean): Step {
        val logged = logPending(s)
        val run = s.run
        val announced = if (announce) cue(s.mode, CueKind.BreakEnd) + Effect.BringToFront else emptyList()
        val after = dropSession(logged.state)
        if (run != null && BreakPolicy.autoFocus(run.mode.rhythm)) {
            val next = next(after, run)
            return Step(next.state, logged.effects + announced + next.effects)
        }
        return Step(after.copy(phase = Phase.Ready), logged.effects + announced)
    }

    private fun home(s: FocusState): Step = when (s.phase) {
        is Phase.Ritual, is Phase.WrapUp, is Phase.Break, Phase.Ready -> {
            val logged = logPending(s)
            Step(dropSession(logged.state).copy(phase = Phase.Idle, run = null), logged.effects)
        }
        else -> wrongPhase(s, "Home")
    }

    /** Logs a finished block whose wrap-up was never answered, as unrated. */
    private fun logPending(s: FocusState): Step {
        val session = s.session
        if (session == null || session.logged || session.focused == null) return Step(s)
        val record = record(s, session, Outcome.Unrated, null, null, null) ?: return Step(s)
        return Step(s.copy(session = session.copy(logged = true)), listOf(Effect.AppendLog(record)))
    }

    /** A new resume note (words, ink or both) replaces the last one as a unit; none keeps the last. */
    private fun FocusState.withResume(note: String?, ink: String?): FocusState =
        if (note == null && ink == null) this else copy(lastResumeNote = note, lastResumeInk = ink)

    private fun record(s: FocusState, session: ActiveSession, outcome: Outcome, rating: Int?, note: String?, ink: String?): SessionRecord? {
        val run = s.run ?: return null
        return SessionRecord(
            id = session.id,
            mode = run.mode.id,
            modeName = run.mode.name,
            task = run.intent.task,
            taskTitle = s.task(run.intent.task)?.title,
            intention = run.intent.intention,
            planned = session.planned,
            focused = session.focused ?: Duration.ZERO,
            outcome = outcome,
            rating = rating,
            resumeNote = note,
            resumeInk = ink,
            parkedCount = session.parked.size,
            start = session.start.rebased(now),
        )
    }

    private fun cue(mode: Mode, kind: CueKind): List<Effect> =
        if (mode.environment.cues == CueStyle.Off) emptyList() else listOf(Effect.PlayCue(kind, mode.environment.cues))

    // Tasks

    /** Unique among [tasks], even for several tasks added at the same instant. */
    private fun newTaskId(tasks: List<Task>): TaskId {
        val base = "t-${now.bootId.take(8)}-${now.elapsedMs}"
        var id = TaskId(base)
        var n = 1
        while (tasks.any { it.id == id }) id = TaskId("$base-${n++}")
        return id
    }

    /** Moves [id] to the front with one more use; drops the least used beyond the limit. */
    private fun touch(tasks: List<Task>, id: TaskId?): List<Task> {
        val task = tasks.firstOrNull { it.id == id } ?: return tasks
        val used = task.copy(useCount = task.useCount + 1, lastUsedWallMs = now.wallMs ?: task.lastUsedWallMs)
        return trim(listOf(used) + tasks.filter { it.id != id })
    }

    private fun trim(tasks: List<Task>): List<Task> {
        if (tasks.size <= Task.MAX_TASKS) return tasks
        // Keep the 10 most recent no matter what; then the most used
        val recent = tasks.take(10)
        val rest = tasks.drop(10).sortedByDescending { it.useCount }.take(Task.MAX_TASKS - 10).toSet()
        return recent + tasks.drop(10).filter { it in rest }
    }

    private fun addTask(s: FocusState, input: Input.AddTask): Step {
        val title = input.title.trim().take(Task.MAX_TITLE)
        if (title.isEmpty()) return reject("empty title")
        if (input.estimateSessions != null && input.estimateSessions !in 1..MAX_ESTIMATE) return reject("estimate is 1..$MAX_ESTIMATE")
        val existing = s.tasks.firstOrNull { it.title.equals(title, ignoreCase = true) }
        val task = existing?.copy(estimateSessions = input.estimateSessions ?: existing.estimateSessions)
            ?: Task(newTaskId(s.tasks), title, input.estimateSessions)
        return Step(s.copy(tasks = trim(listOf(task) + s.tasks.filter { it.id != task.id })))
    }

    private fun setEstimate(s: FocusState, input: Input.SetEstimate): Step {
        if (input.sessions != null && input.sessions !in 1..MAX_ESTIMATE) return reject("estimate is 1..$MAX_ESTIMATE")
        if (s.task(input.taskId) == null) return reject("unknown task ${input.taskId.value}")
        return Step(s.copy(tasks = s.tasks.map { if (it.id == input.taskId) it.copy(estimateSessions = input.sessions) else it }))
    }

    private fun removeTask(s: FocusState, input: Input.RemoveTask): Step {
        if (s.task(input.taskId) == null) return reject("unknown task ${input.taskId.value}")
        return Step(s.copy(tasks = s.tasks.filter { it.id != input.taskId }))
    }

    // Modes

    private fun saveMode(s: FocusState, mode: Mode): Step {
        if (mode.builtIn || Presets.byId(mode.id) != null) return reject("built-in modes are read-only; duplicate one")
        ModeRules.problem(mode)?.let { return reject(it) }
        val modes = s.settings.modes
        val exists = modes.any { it.id == mode.id }
        if (!exists && modes.size >= Settings.MAX_MODES) return reject("at most ${Settings.MAX_MODES} modes")
        val updated = if (exists) modes.map { if (it.id == mode.id) mode else it } else modes + mode
        val before = s.settings.mode(mode.id)
        val rituals = if (before != null && before.environment.display != mode.environment.display) {
            s.rituals.map { if (it.mode == mode.id) it.copy(display = null) else it }
        } else {
            s.rituals
        }
        return Step(s.copy(settings = s.settings.copy(modes = updated), rituals = rituals))
    }

    private fun moveMode(s: FocusState, input: Input.MoveMode): Step {
        val modes = s.settings.modes
        val from = modes.indexOfFirst { it.id == input.id }
        if (from < 0) return reject("unknown mode ${input.id.value}")
        val to = (from + input.by).coerceIn(0, modes.lastIndex)
        if (to == from) return Step(s)
        val list = modes.toMutableList()
        list.add(to, list.removeAt(from))
        return Step(s.copy(settings = s.settings.copy(modes = list)))
    }

    private fun deleteMode(s: FocusState, input: Input.DeleteMode): Step {
        val mode = s.settings.mode(input.id) ?: return reject("unknown mode ${input.id.value}")
        if (mode.builtIn) return reject("built-in modes can't be deleted")
        val settings = s.settings.copy(
            modes = s.settings.modes.filter { it.id != input.id },
            selectedMode = if (s.settings.selectedMode == input.id) Presets.DEEP_ID else s.settings.selectedMode,
        )
        val phase = s.phase
        val back = if (phase is Phase.Ritual && phase.modeId == input.id && s.run == null) Phase.Idle else phase
        return Step(s.copy(settings = settings, phase = back, rituals = s.rituals.filter { it.mode != input.id }))
    }

    companion object {
        val MAX_BLOCK: Duration = 4.hours
        val MAX_BREAK: Duration = 90.minutes
        val MAX_BREAK_STEP: Duration = 30.minutes
        const val MAX_INK = 64_000
        const val MAX_PARKED = 30
        const val MAX_IF_THEN = 120
        const val MAX_RITUALS = 16
        const val MAX_ESTIMATE = 99
    }
}
