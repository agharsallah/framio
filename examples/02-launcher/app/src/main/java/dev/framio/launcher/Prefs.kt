package dev.framio.launcher

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Where the home button sits: on the left or right edge, [y] from the top (0) to the bottom (1). */
data class DotPlace(val right: Boolean = false, val y: Float = 1f)

/**
 * Everything the user can set on the launcher. Small and read at once, so SharedPreferences (already in memory after
 * the first read) is enough: no DataStore, no extra library in a 1 GB frame's HOME process.
 */
data class Config(
    /** What opens by itself after a boot: [PHOTOS], a flattened ComponentName, or null for Home. */
    val startup: String? = null,
    val startupDelaySec: Int = 10,
    /** Minutes Home may sit untouched before [startup] comes back; 0 = never. */
    val idleReturnMin: Int = 0,
    val dotEnabled: Boolean = true,
    val dotSize: Int = 56,
    /** Opacity of the dot once it has rested a few seconds. */
    val dotRestAlpha: Float = 0.45f,
    val dotPlace: DotPlace = DotPlace(),
    /** framio apps the user took off the shelf (packages). */
    val hidden: Set<String> = emptySet(),
    /** Other apps the user put on the shelf (packages). */
    val pinned: Set<String> = emptySet(),
    /** Packages "Close all" leaves running. Frameo by default: it is the frame's slideshow. */
    val keepRunning: Set<String> = setOf(Apps.FRAMEO),
) {
    companion object {
        const val PHOTOS = "photos"
    }
}

class Prefs(context: Context) {

    private val sp: SharedPreferences = context.applicationContext.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun load(): Config {
        val d = Config()
        return Config(
            startup = sp.getString(K_STARTUP, null),
            startupDelaySec = sp.getInt(K_STARTUP_DELAY, d.startupDelaySec),
            idleReturnMin = sp.getInt(K_IDLE_RETURN, d.idleReturnMin),
            dotEnabled = sp.getBoolean(K_DOT_ON, d.dotEnabled),
            dotSize = sp.getInt(K_DOT_SIZE, d.dotSize),
            dotRestAlpha = sp.getFloat(K_DOT_ALPHA, d.dotRestAlpha),
            dotPlace = DotPlace(sp.getBoolean(K_DOT_RIGHT, d.dotPlace.right), sp.getFloat(K_DOT_Y, d.dotPlace.y)),
            // copies: the sets SharedPreferences returns must not be modified
            hidden = sp.getStringSet(K_HIDDEN, null)?.toSet() ?: d.hidden,
            pinned = sp.getStringSet(K_PINNED, null)?.toSet() ?: d.pinned,
            keepRunning = sp.getStringSet(K_KEEP, null)?.toSet() ?: d.keepRunning,
        )
    }

    fun save(c: Config) {
        sp.edit {
            putString(K_STARTUP, c.startup)
            putInt(K_STARTUP_DELAY, c.startupDelaySec)
            putInt(K_IDLE_RETURN, c.idleReturnMin)
            putBoolean(K_DOT_ON, c.dotEnabled)
            putInt(K_DOT_SIZE, c.dotSize)
            putFloat(K_DOT_ALPHA, c.dotRestAlpha)
            putBoolean(K_DOT_RIGHT, c.dotPlace.right)
            putFloat(K_DOT_Y, c.dotPlace.y)
            putStringSet(K_HIDDEN, c.hidden)
            putStringSet(K_PINNED, c.pinned)
            putStringSet(K_KEEP, c.keepRunning)
        }
    }

    /** The service and the activity share the process: each hears the other's changes. */
    fun listen(onChange: () -> Unit): SharedPreferences.OnSharedPreferenceChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }.also { sp.registerOnSharedPreferenceChangeListener(it) }

    fun unlisten(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(l)

    /** The boot the startup app last ran in (see [Startup]). */
    var lastBootId: String?
        get() = sp.getString(K_BOOT, null)
        set(v) = sp.edit { putString(K_BOOT, v) }

    private companion object {
        const val K_STARTUP = "startup"
        const val K_STARTUP_DELAY = "startup_delay"
        const val K_IDLE_RETURN = "idle_return"
        const val K_DOT_ON = "dot_on"
        const val K_DOT_SIZE = "dot_size"
        const val K_DOT_ALPHA = "dot_alpha"
        const val K_DOT_RIGHT = "dot_right"
        const val K_DOT_Y = "dot_y"
        const val K_HIDDEN = "hidden"
        const val K_PINNED = "pinned"
        const val K_KEEP = "keep_running"
        const val K_BOOT = "boot_id"
    }
}
