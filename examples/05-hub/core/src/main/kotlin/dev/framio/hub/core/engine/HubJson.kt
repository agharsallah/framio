package dev.framio.hub.core.engine

import dev.framio.hub.core.model.HubState
import dev.framio.hub.core.model.Settings
import dev.framio.hub.core.time.LocalTimes
import dev.framio.hub.core.time.isValid
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** One JSON configuration for the state file, the LAN API, the relay and adb. */
val HubJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true // an older Hub reads a newer phone's command; a newer Hub reads an older state file
    encodeDefaults = true
}

object Commands {
    /** Decodes a command from any transport, or null with the reason. */
    fun decode(json: String): Result<Command> = try {
        Result.success(HubJson.decodeFromString(Command.serializer(), json))
    } catch (e: SerializationException) {
        Result.failure(e)
    } catch (e: IllegalArgumentException) {
        Result.failure(e)
    }

    fun encode(command: Command): String = HubJson.encodeToString(Command.serializer(), command)
}

object SettingsRules {
    /** Merges [patch] into [settings] key by key (nested objects too); null clears a value. */
    fun merge(settings: Settings, patch: JsonObject): Result<Settings> = try {
        val current = HubJson.encodeToJsonElement(Settings.serializer(), settings).jsonObject
        Result.success(HubJson.decodeFromJsonElement(Settings.serializer(), deepMerge(current, patch)))
    } catch (e: SerializationException) {
        Result.failure(e)
    } catch (e: IllegalArgumentException) {
        Result.failure(e)
    }

    private fun deepMerge(base: JsonObject, patch: JsonObject): JsonObject = JsonObject(
        base + patch.mapValues { (key, value) ->
            val old: JsonElement? = base[key]
            if (value is JsonObject && old is JsonObject) deepMerge(old, value) else value
        },
    )

    /** Null when valid, else why not. */
    fun problem(s: Settings): String? = when {
        s.location != null && (s.location.latitude !in -90.0..90.0 || s.location.longitude !in -180.0..180.0) -> "location out of range"
        !LocalTimes.isValid(s.night.start) || !LocalTimes.isValid(s.night.end) -> "night times must be HH:mm"
        s.night.sunsetOffsetMin !in -120..360 -> "sunset offset must be -120..360 min"
        s.night.rampMin !in 0..60 -> "ramp must be 0..60 min"
        s.quiet != null && !s.quiet.isValid() -> "quiet hours must be HH:mm"
        s.dayBacklight != null && s.dayBacklight !in 1..255 -> "backlight must be 1..255"
        s.dayBrightness != null && s.dayBrightness !in 0.05f..1f -> "brightness must be 0.05..1"
        else -> null
    }
}

object StateCodec {
    fun encode(state: HubState): String = HubJson.encodeToString(HubState.serializer(), state)

    /** Reads a state file of this or an older schema. */
    fun decode(json: String): HubState = HubJson.decodeFromString(HubState.serializer(), Migrations.migrate(json))
}

/** Schema migrations, one function per version step. Every past version has a fixture file in the tests. */
object Migrations {
    fun migrate(json: String): String {
        val obj = HubJson.parseToJsonElement(json).jsonObject
        val from = obj["schema"]?.toString()?.toIntOrNull() ?: 1
        require(from <= HubState.SCHEMA) { "state schema $from is newer than this Hub (${HubState.SCHEMA})" }
        // Schema 1 is the first; add steps here as `if (from < 2) obj = v1to2(obj)`.
        return json
    }
}
