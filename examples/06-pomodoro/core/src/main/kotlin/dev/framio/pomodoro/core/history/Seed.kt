package dev.framio.pomodoro.core.history

import dev.framio.pomodoro.core.model.InkCodec
import dev.framio.pomodoro.core.model.ModeId
import dev.framio.pomodoro.core.model.Outcome
import dev.framio.pomodoro.core.model.SessionId
import dev.framio.pomodoro.core.model.SessionRecord
import dev.framio.pomodoro.core.model.Stroke
import dev.framio.pomodoro.core.model.TaskId
import dev.framio.pomodoro.core.notes.Entry
import dev.framio.pomodoro.core.notes.EntryId
import dev.framio.pomodoro.core.notes.JournalOp
import dev.framio.pomodoro.core.notes.NoteBody
import dev.framio.pomodoro.core.notes.TagId
import dev.framio.pomodoro.core.notes.Taxonomy
import dev.framio.pomodoro.core.time.Moment
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Realistic made-up history for development and device measurements (debug adb `--es cmd seed`): a few sessions on
 * most weekdays, fewer at weekends, some days off; a mix of modes, tasks and outcomes; distractions that lean towards
 * the phone in the afternoon; ratings that are a little better in the morning; parked thoughts as real ink. Everything
 * it makes has an id starting with [PREFIX], so it can be removed again line by line.
 */
public object HistorySeed {
    public const val PREFIX: String = "seed-"

    public data class Seeded(val records: List<SessionRecord>, val ops: List<JournalOp>)

    private val tasks = listOf(
        "Thesis ch.3", "Review PR", "Taxes", "Grant report", "Reading", "Slides for Friday", "Inbox zero", "Garden plan",
    )
    private val weights = listOf(30, 18, 8, 14, 10, 8, 7, 5)

    /** [days] days ending yesterday (today stays the user's own), in [zone]. Same [seed], same history. */
    public fun generate(days: Int, today: LocalDate, zone: ZoneId, seed: Int = 7): Seeded {
        val rnd = Random(seed)
        val records = ArrayList<SessionRecord>()
        val ops = ArrayList<JournalOp>()
        var n = 0
        var e = 0
        for (back in days downTo 1) {
            val date = today.minusDays(back.toLong())
            val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
            if (rnd.nextInt(100) < (if (weekend) 55 else 12)) continue
            val count = if (weekend) 1 + rnd.nextInt(3) else 2 + rnd.nextInt(6)
            var at = LocalTime.of(8, 30).plusMinutes(rnd.nextLong(90))
            repeat(count) {
                if (at.hour >= 21) return@repeat
                val mode = pick(rnd, listOf(40, 35, 15, 10))
                val (modeId, modeName, planned) = when (mode) {
                    0 -> Triple("deep", "Deep", 50.minutes)
                    1 -> Triple("classic", "Classic", 25.minutes)
                    2 -> Triple("flow", "Flow", null)
                    else -> Triple("gentle", "Gentle start", 10.minutes)
                }
                val outcome = Outcome.entries[pick(rnd, listOf(45, 20, 8, 12, 12, 3))]
                val full = planned ?: (18 + rnd.nextInt(60)).minutes
                val focused = if (outcome.completed) full else full * (0.3 + rnd.nextDouble() * 0.6)
                val taskIx = pick(rnd, weights)
                val wall = date.atTime(at).atZone(zone).toInstant().toEpochMilli()
                val id = SessionId("$PREFIX${n++}")
                val morning = at.hour < 12
                val rating = if (outcome.completed && rnd.nextInt(100) < 55) {
                    (if (morning) 3 + rnd.nextInt(3) else 2 + rnd.nextInt(4)).coerceAtMost(5)
                } else {
                    null
                }
                records += SessionRecord(
                    id = id,
                    mode = ModeId(modeId),
                    modeName = modeName,
                    task = TaskId("${PREFIX}t$taskIx"),
                    taskTitle = tasks[taskIx],
                    intention = tasks[taskIx],
                    planned = planned,
                    focused = focused,
                    outcome = outcome,
                    rating = rating,
                    resumeNote = if (outcome == Outcome.EndedEarly && rnd.nextInt(100) < 40) "pick up at ${tasks[taskIx].lowercase()} §${1 + rnd.nextInt(6)}" else null,
                    start = Moment(PREFIX + "boot", 0, wall),
                )
                // Distractions: about one a session, more in long blocks, the phone more in the afternoon
                val mean = focused / 40.minutes
                val k = poisson(rnd, mean)
                repeat(k) {
                    val tag = distractionTag(rnd, afternoon = at.hour >= 14)
                    val second = if (rnd.nextInt(100) < 12) distractionTag(rnd, at.hour >= 14).takeIf { it != tag } else null
                    val recalled = rnd.nextInt(100) < 15
                    ops += JournalOp.Put(
                        Entry.Distraction(
                            EntryId("${PREFIX}e${e++}"), id,
                            if (recalled) null else (focused * rnd.nextDouble()).roundToMinute(),
                            wall + focused.inWholeMilliseconds, listOfNotNull(tag, second),
                        ),
                    )
                }
                if (outcome == Outcome.EndedEarly && rnd.nextInt(100) < 70) {
                    val why = listOf(Taxonomy.LEAVE, Taxonomy.DONE, Taxonomy.PERSON, Taxonomy.URGENT, Taxonomy.TIRED)[pick(rnd, listOf(35, 20, 20, 15, 10))]
                    ops += JournalOp.Put(Entry.EndReason(EntryId("${PREFIX}e${e++}"), id, focused, wall + focused.inWholeMilliseconds, listOf(why)))
                }
                if (rnd.nextInt(100) < 18) {
                    val at2 = (focused * rnd.nextDouble()).roundToMinute()
                    ops += JournalOp.Put(
                        Entry.Parked(EntryId("${PREFIX}e${e++}"), id, at2, wall + at2.inWholeMilliseconds, NoteBody.ink(scribble(rnd)), resolved = rnd.nextBoolean()),
                    )
                }
                if (rnd.nextInt(100) < 5) {
                    ops += JournalOp.Put(Entry.Note(EntryId("${PREFIX}e${e++}"), id, null, wall + focused.inWholeMilliseconds, NoteBody.text("went well after the first ten minutes")))
                }
                at = at.plusMinutes(focused.inWholeMinutes + 5 + rnd.nextLong(40))
                if (rnd.nextInt(100) < 20) at = at.plusMinutes(60) // lunch, a meeting
            }
        }
        return Seeded(records, ops)
    }

    private fun distractionTag(rnd: Random, afternoon: Boolean): TagId {
        val tags = listOf(Taxonomy.PHONE, Taxonomy.WANDER, Taxonomy.PERSON, Taxonomy.NOISE, Taxonomy.URGENT, Taxonomy.HUNGER, Taxonomy.TIRED, Taxonomy.TECH)
        val w = if (afternoon) listOf(36, 18, 12, 8, 7, 5, 9, 5) else listOf(22, 24, 16, 12, 9, 8, 3, 6)
        return tags[pick(rnd, w)]
    }

    private fun pick(rnd: Random, weights: List<Int>): Int {
        var r = rnd.nextInt(weights.sum())
        for ((i, w) in weights.withIndex()) {
            if (r < w) return i
            r -= w
        }
        return weights.lastIndex
    }

    private fun poisson(rnd: Random, mean: Double): Int {
        var k = 0
        var p = rnd.nextDouble()
        val l = kotlin.math.exp(-mean)
        while (p > l && k < 8) {
            k++
            p *= rnd.nextDouble()
        }
        return k
    }

    private fun Duration.roundToMinute(): Duration = inWholeMinutes.minutes

    /** A one-word scribble: two or three wavy strokes across the pad. */
    private fun scribble(rnd: Random): String {
        val strokes = (0 until 2 + rnd.nextInt(2)).map { s ->
            val x0 = 0.1f + s * 0.28f
            val pts = FloatArray(2 * 18)
            for (i in 0 until 18) {
                val t = i / 17f
                pts[2 * i] = x0 + t * 0.22f
                pts[2 * i + 1] = 0.5f + 0.25f * sin((t * 3 + s) * PI).toFloat() * (0.6f + rnd.nextFloat() * 0.4f)
            }
            Stroke(pts)
        }
        return InkCodec.encode(strokes)
    }
}
