package dev.framio.meadow.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/** Writes through a temp file + rename, so a power cut (the frame has no battery) never leaves half a file. */
object JsonFile {
    fun read(file: File): JSONObject? = try {
        if (file.exists()) JSONObject(file.readText()) else null
    } catch (e: Exception) {
        Log.w(TAG, "unreadable ${file.name}, using defaults", e)
        null
    }

    fun write(file: File, json: JSONObject) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

/** The settings, observable by the UI and changed by the parent screen, the dashboard and adb. */
class SettingsStore(private val file: File) {

    private val state = MutableStateFlow(load())
    val flow: StateFlow<Settings> = state.asStateFlow()
    val value: Settings get() = state.value

    private fun load(): Settings {
        val s = JsonFile.read(file)?.let(Settings::fromJson) ?: Settings()
        return if (s.pin.length == 4) s else s.copy(pin = "%04d".format(Random.nextInt(1000, 10000))).also { save(it) }
    }

    fun update(change: (Settings) -> Settings) {
        state.update(change)
        save(state.value)
    }

    @Synchronized
    private fun save(s: Settings) = JsonFile.write(file, s.toJson().put("pin", s.pin))
}

const val TAG = "Meadow"
