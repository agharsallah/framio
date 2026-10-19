package dev.framio.hub.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import dev.framio.hub.core.engine.HubJson
import dev.framio.hub.core.engine.StateCodec
import dev.framio.hub.core.engine.StateStore
import dev.framio.hub.core.model.HubState
import dev.framio.hub.platform.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Persists [HubState] with DataStore: atomic whole-file writes, one writer. The engine's StateFlow is the
 * truth in memory; this store only follows it. A writer coroutine takes the latest state, conflated, so ten
 * quick taps cause at most two writes. A corrupt file is copied aside (`hub.bad-<time>.json`), never lost.
 */
class HubStateStore(context: Context, scope: CoroutineScope) : StateStore {
    private val file = context.dataStoreFile(FILE)
    private val store: DataStore<HubState> = DataStoreFactory.create(
        serializer = StateSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { e ->
            val aside = File(file.parentFile, "hub.bad-${System.currentTimeMillis()}.json")
            file.copyTo(aside, overwrite = true)
            Logs.warn("store", "state file was corrupt, kept as ${aside.name}, starting fresh", e)
            HubState()
        },
        scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
        produceFile = { file },
    )
    private val pending = MutableStateFlow<HubState?>(null)

    init {
        scope.launch(Dispatchers.IO) {
            pending.filterNotNull().conflate().collect { state ->
                try {
                    store.updateData { state }
                } catch (e: Exception) {
                    Logs.warn("store", "write failed: ${e.message}", e)
                }
            }
        }
    }

    override suspend fun load(): HubState = store.data.first()

    override fun offer(state: HubState) {
        pending.value = state
    }

    private object StateSerializer : Serializer<HubState> {
        override val defaultValue = HubState()

        override suspend fun readFrom(input: InputStream): HubState = try {
            StateCodec.decode(input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("unreadable state", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("unreadable state", e)
        }

        override suspend fun writeTo(t: HubState, output: OutputStream) = output.write(StateCodec.encode(t).encodeToByteArray())
    }

    companion object {
        const val FILE = "hub.json"
    }
}

/**
 * Secrets (calendar URLs, device tokens, the relay key) in their own file: never exported, never logged,
 * never part of the state phones read.
 */
class SecretsStore(context: Context, scope: CoroutineScope) {
    private val store: DataStore<Map<String, String>> = DataStoreFactory.create(
        serializer = SecretsSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { emptyMap() },
        scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
        produceFile = { context.dataStoreFile("secrets.json") },
    )

    val data = store.data

    suspend fun get(key: String): String? = store.data.first()[key]

    suspend fun put(key: String, value: String?) {
        store.updateData { if (value == null) it - key else it + (key to value) }
    }

    suspend fun update(change: (Map<String, String>) -> Map<String, String>) {
        store.updateData(change)
    }

    private object SecretsSerializer : Serializer<Map<String, String>> {
        private val serializer = MapSerializer(String.serializer(), String.serializer())
        override val defaultValue: Map<String, String> = emptyMap()

        override suspend fun readFrom(input: InputStream): Map<String, String> = try {
            HubJson.decodeFromString(serializer, input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("unreadable secrets", e)
        }

        override suspend fun writeTo(t: Map<String, String>, output: OutputStream) =
            output.write(HubJson.encodeToString(serializer, t).encodeToByteArray())
    }
}
