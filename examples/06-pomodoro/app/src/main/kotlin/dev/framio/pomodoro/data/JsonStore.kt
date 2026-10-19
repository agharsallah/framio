package dev.framio.pomodoro.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import dev.framio.pomodoro.core.engine.CoreJson
import dev.framio.pomodoro.core.engine.Snapshot
import dev.framio.pomodoro.core.model.Settings
import dev.framio.pomodoro.platform.Logs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * One JSON file kept by DataStore: atomic whole-file writes (temp file + rename), one writer. The engine's state is
 * the truth in memory; this store only follows it. Writes are conflated: [offer] keeps only the latest value, so a
 * burst of changes costs at most two writes. A file that can't be read is copied aside (`<name>.bad-<time>.json`),
 * never silently lost, and the store starts from [default].
 */
open class JsonStore<T : Any>(
    private val file: File,
    scope: CoroutineScope,
    io: CoroutineDispatcher,
    default: T,
    encode: (T) -> String,
    decode: (String) -> T?,
) {
    private val store: DataStore<T> = DataStoreFactory.create(
        serializer = JsonSerializer(default, encode, decode),
        corruptionHandler = ReplaceFileCorruptionHandler { e ->
            val aside = File(file.parentFile, "${file.nameWithoutExtension}.bad-${System.currentTimeMillis()}.json")
            try {
                file.copyTo(aside, overwrite = true)
            } catch (_: Exception) {
            }
            Logs.warn("store", "${file.name} was unreadable, kept as ${aside.name}, starting fresh", e)
            default
        },
        scope = CoroutineScope(scope.coroutineContext + io),
        produceFile = { file },
    )
    private val pending = MutableStateFlow<T?>(null)

    init {
        scope.launch(io) {
            pending.filterNotNull().collect { value ->
                try {
                    store.updateData { value }
                } catch (e: Exception) {
                    Logs.warn("store", "${file.name}: write failed: ${e.message}", e)
                }
            }
        }
    }

    /** The stored value, or the default if there is none (or it was unreadable). */
    suspend fun load(): T = store.data.first()

    /** Queues a write of [value]; only the latest pending value is written. */
    fun offer(value: T) {
        pending.value = value
    }

    /** Writes now and waits (tests, and the self-test). */
    suspend fun write(value: T) {
        store.updateData { value }
    }

    private class JsonSerializer<T : Any>(
        override val defaultValue: T,
        private val encode: (T) -> String,
        private val decode: (String) -> T?,
    ) : Serializer<T> {
        override suspend fun readFrom(input: InputStream): T =
            decode(input.readBytes().decodeToString()) ?: throw CorruptionException("unreadable JSON")

        override suspend fun writeTo(t: T, output: OutputStream) = output.write(encode(t).encodeToByteArray())
    }
}

/** Settings and modes (`settings.json`). Decoding normalizes: presets always match this version of the app. */
class SettingsStore(file: File, scope: CoroutineScope, io: CoroutineDispatcher) :
    JsonStore<Settings>(file, scope, io, Settings(), CoreJson::encodeSettings, CoreJson::decodeSettings)

/**
 * The engine snapshot (`snapshot.json`): the phase, deadlines and run, written on every change, so a killed process
 * resumes exactly where it was. The default is an empty Idle snapshot.
 */
class SnapshotStore(file: File, scope: CoroutineScope, io: CoroutineDispatcher) :
    JsonStore<Snapshot>(file, scope, io, Snapshot(), CoreJson::encodeSnapshot, CoreJson::decodeSnapshot)
