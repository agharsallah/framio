package dev.framio.mawaqit.data

import android.content.Context
import androidx.core.content.edit
import dev.framio.mawaqit.core.Envelope
import dev.framio.mawaqit.core.PresenceSettings
import dev.framio.mawaqit.core.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONException
import org.json.JSONObject
import java.security.SecureRandom

/** The settings as one JSON document in SharedPreferences, observed as a StateFlow. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(withSecrets(load()))
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val current get() = _settings.value

    init {
        save(_settings.value) // persists freshly generated secrets
    }

    /** Merges [patch] into the current settings (see [Settings.merge]); returns the result. */
    @Synchronized
    fun patch(patch: JSONObject): Settings {
        // an older panel's photo-frame mode ("frame") lands in "presence"
        val merged = Settings.fromJson(Settings.merge(current.toJson(), PresenceSettings.upgradePatch(patch)))
        return set(merged)
    }

    @Synchronized
    fun set(settings: Settings): Settings {
        val s = withSecrets(settings)
        save(s)
        _settings.value = s
        return s
    }

    /** New ntfy topic and key: links shared before stop working. */
    fun rotateRemote(): Settings = set(current.copy(remote = current.remote.copy(ntfyTopic = "", ntfyKey = "")))

    private fun load(): Settings = try {
        Settings.fromJson(prefs.getString(KEY, null)?.let(::JSONObject))
    } catch (_: JSONException) {
        Settings()
    }

    private fun save(s: Settings) {
        prefs.edit { putString(KEY, s.toJson().toString()) }
    }

    /** The PIN, topic and key are generated once, on the frame: never typed by anyone, never defaulted. */
    private fun withSecrets(s: Settings): Settings {
        val r = s.remote
        if (r.pin.length >= 4 && r.ntfyTopic.length >= 16 && r.ntfyKey.isNotEmpty()) return s
        return s.copy(
            remote = r.copy(
                pin = r.pin.takeIf { it.length >= 4 } ?: (100_000 + random.nextInt(900_000)).toString(),
                ntfyTopic = r.ntfyTopic.takeIf { it.length >= 16 } ?: "framio-" + (1..20).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString(""),
                ntfyKey = r.ntfyKey.ifEmpty { Envelope.newKey() },
            ),
        )
    }

    private companion object {
        const val KEY = "json"
        const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789" // no look-alikes
        val random = SecureRandom()
    }
}
