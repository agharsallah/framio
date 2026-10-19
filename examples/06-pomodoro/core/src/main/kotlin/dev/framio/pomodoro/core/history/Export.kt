package dev.framio.pomodoro.core.history

import dev.framio.pomodoro.core.model.SessionRecord
import dev.framio.pomodoro.core.notes.Entry
import dev.framio.pomodoro.core.notes.JournalCodec
import dev.framio.pomodoro.core.notes.Taxonomy
import dev.framio.pomodoro.core.notes.body
import dev.framio.pomodoro.core.notes.kind
import dev.framio.pomodoro.core.notes.tags
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Everything in History as one JSON document (complete: re-importable, ink included) and as two CSV tables (for a
 * spreadsheet: one row per session, one row per entry). Dropped sessions are left out, corrections are applied.
 */
public object Export {
    public const val FORMAT: String = "pomodoro-history"
    public const val VERSION: Int = 1

    public fun json(index: HistoryIndex, taxonomy: Taxonomy, exportedAtMs: Long): String {
        val j = JournalCodec.json
        val doc = buildJsonObject {
            put("format", JsonPrimitive(FORMAT))
            put("version", JsonPrimitive(VERSION))
            put("exportedAt", JsonPrimitive(Instant.ofEpochMilli(exportedAtMs).truncatedTo(ChronoUnit.SECONDS).toString()))
            put("zone", JsonPrimitive(index.zone.id))
            put("tags", j.encodeToJsonElement(Taxonomy.serializer(), taxonomy))
            put(
                "sessions",
                JsonArray(
                    index.sessions.map { s ->
                        val rec = j.encodeToJsonElement(SessionRecord.serializer(), s.record) as JsonObject
                        JsonObject(
                            rec + buildJsonObject {
                                s.date?.let { put("date", JsonPrimitive(it.toString())) }
                                s.startLocal(index.zone)?.let { put("startLocal", JsonPrimitive(it.truncatedTo(ChronoUnit.SECONDS).toString())) }
                                if (s.legacyParked > 0) put("legacyParked", JsonPrimitive(s.legacyParked))
                                put("entries", JsonArray(s.entries.map { j.encodeToJsonElement(Entry.serializer(), it) }))
                            },
                        )
                    },
                ),
            )
        }
        return doc.toString()
    }

    /** One row per session. Times are local; durations in whole seconds (the log's precision, no rounding to minutes). */
    public fun sessionsCsv(index: HistoryIndex, taxonomy: Taxonomy, bom: Boolean = true): String = csv(
        bom,
        listOf(
            "session_id", "date", "start_local", "mode", "task", "intention", "planned_s", "focused_s", "outcome",
            "rating", "distractions", "distraction_reasons", "end_reasons", "notes", "parked", "resume_note",
        ),
        index.sessions.map { s ->
            val r = s.record
            val distractionTags = s.entries.filterIsInstance<Entry.Distraction>().flatMap { it.tags }
            val endTags = s.entries.filterIsInstance<Entry.EndReason>().flatMap { it.tags }
            listOf(
                r.id.value,
                s.date?.toString().orEmpty(),
                s.startLocal(index.zone)?.format(LOCAL).orEmpty(),
                r.modeName ?: r.mode.value,
                r.taskTitle.orEmpty(),
                r.intention.orEmpty(),
                r.planned?.inWholeSeconds?.toString().orEmpty(),
                r.focused.inWholeSeconds.toString(),
                r.outcome.name,
                r.rating?.toString().orEmpty(),
                s.distractions.toString(),
                distractionTags.joinToString("; ") { taxonomy.label(it) },
                endTags.joinToString("; ") { taxonomy.label(it) },
                s.entries.count { it is Entry.Note }.toString(),
                (s.entries.count { it is Entry.Parked } + s.legacyParked).toString(),
                s.entries.lastOrNull { it is Entry.ResumeNote }?.body?.text.orEmpty(),
            )
        },
    )

    /** One row per entry. Handwriting is exported as its InkCodec text (`ink` column), not as an image. */
    public fun entriesCsv(index: HistoryIndex, taxonomy: Taxonomy, bom: Boolean = true): String = csv(
        bom,
        listOf("session_id", "entry_id", "kind", "minute", "written_local", "tags", "text", "ink", "resolved"),
        index.sessions.flatMap { s ->
            s.entries.map { e ->
                listOf(
                    s.id.value,
                    e.id.value,
                    e.kind,
                    e.at?.inWholeMinutes?.toString().orEmpty(),
                    e.wallMs?.let { Instant.ofEpochMilli(it).atZone(index.zone).toLocalDateTime().format(LOCAL) }.orEmpty(),
                    e.tags.joinToString("; ") { taxonomy.label(it) },
                    e.body?.text.orEmpty(),
                    e.body?.ink.orEmpty(),
                    (e as? Entry.Parked)?.resolved?.toString().orEmpty(),
                )
            }
        },
    )

    private val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun csv(bom: Boolean, header: List<String>, rows: List<List<String>>): String = buildString {
        if (bom) append('﻿')
        appendRow(header)
        rows.forEach { appendRow(it) }
    }

    private fun StringBuilder.appendRow(cells: List<String>) {
        cells.forEachIndexed { i, c ->
            if (i > 0) append(',')
            append(cell(c))
        }
        append("\r\n")
    }

    /**
     * One RFC 4180 field. Quoted when it holds a comma, quote, CR or LF, or starts or ends with a space; quotes doubled.
     * A field a spreadsheet would run as a formula (`=`, `+`, `-`, `@`, tab, CR first) gets a leading `'` unless it is
     * a plain number, so an intention like "=HYPERLINK(…)" stays text.
     */
    public fun cell(raw: String): String {
        val safe = if (raw.isNotEmpty() && raw[0] in FORMULA && !NUMBER.matches(raw)) "'$raw" else raw
        val quote = safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
            safe.startsWith(' ') || safe.endsWith(' ')
        return if (quote) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    private const val FORMULA = "=+-@\t\r"
    private val NUMBER = Regex("[-+]?\\d+(\\.\\d+)?")
}
