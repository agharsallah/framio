package dev.framio.lab.core

import android.content.Context
import android.content.pm.PackageManager
import android.view.KeyEvent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * What a lab may ask of the activity: its window (brightness, orientation), runtime permissions,
 * key events, a full-screen overlay and the shared self-test runner. Labs reach it via [LocalHost].
 */
class LabHost(
    val context: Context,
    val window: Window,
    scope: CoroutineScope,
    labs: () -> List<Lab>,
    private val setOrientation: (Int) -> Unit,
    private val launchPermissions: (Array<String>) -> Unit,
) {
    var selected by mutableStateOf(OVERVIEW)

    /** Drawn above everything (e.g. full-screen test patterns). Tap handling is up to the overlay. */
    var overlay by mutableStateOf<(@Composable () -> Unit)?>(null)

    val selfTest = SelfTestRunner(context.applicationContext, scope, labs)

    // ---- keys ----

    private val _keys = MutableSharedFlow<KeyEvent>(extraBufferCapacity = 64)
    val keys: SharedFlow<KeyEvent> = _keys

    /** True while the input lab is open: keys are logged and not passed on (volume keys won't change volume). */
    var captureKeys = false

    /** Returns true if the key was consumed. */
    fun onKey(event: KeyEvent): Boolean {
        _keys.tryEmit(event)
        return captureKeys
    }

    // ---- permissions ----

    /** Bumped after every permission result, so composables re-read [granted]. */
    var permissionVersion by mutableIntStateOf(0)
        private set

    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun request(vararg permissions: String) = launchPermissions(arrayOf(*permissions))

    fun onPermissionsResult() {
        permissionVersion++
    }

    // ---- window ----

    fun orientation(requested: Int) = setOrientation(requested)

    companion object {
        const val OVERVIEW = "overview"
    }
}

val LocalHost = staticCompositionLocalOf<LabHost> { error("LabHost not provided") }
