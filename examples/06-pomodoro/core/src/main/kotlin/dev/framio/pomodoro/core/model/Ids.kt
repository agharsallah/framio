package dev.framio.pomodoro.core.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** A mode, such as `deep`. Built-in presets have fixed ids; your own modes get generated ones. */
@Serializable
@JvmInline
public value class ModeId(public val value: String)

/** One focus block. Unique across reboots: made from the boot id and the elapsed time it started at. */
@Serializable
@JvmInline
public value class SessionId(public val value: String)

/** A task you focus on ("Thesis ch.3"). */
@Serializable
@JvmInline
public value class TaskId(public val value: String)
