package dev.framio.pomodoro.core.engine

import dev.framio.pomodoro.core.model.Settings
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** JSON for the settings and snapshot files: tolerant of unknown keys and values from newer versions. */
public object CoreJson {
    public val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    public fun encodeSettings(settings: Settings): String = json.encodeToString(Settings.serializer(), settings)

    /** Null if [text] isn't readable settings; the caller then starts from defaults. Normalized. */
    public fun decodeSettings(text: String): Settings? = decode { json.decodeFromString(Settings.serializer(), text).normalized() }

    public fun encodeSnapshot(snapshot: Snapshot): String = json.encodeToString(Snapshot.serializer(), snapshot)

    /** Null if [text] isn't a readable snapshot. */
    public fun decodeSnapshot(text: String): Snapshot? = decode { json.decodeFromString(Snapshot.serializer(), text) }

    private inline fun <T> decode(block: () -> T): T? = try {
        block()
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: java.time.DateTimeException) {
        null
    }
}
