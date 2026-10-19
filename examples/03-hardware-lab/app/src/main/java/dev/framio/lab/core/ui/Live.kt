package dev.framio.lab.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Re-reads [read] every [periodMs] on a background thread, only while the screen is visible.
 * Most hardware readouts (CPU frequency, Wi-Fi RSSI, memory) are polled with this.
 */
@Composable
fun <T> rememberPolled(periodMs: Long, vararg keys: Any?, read: suspend () -> T): State<T?> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState<T?>(null, lifecycle, *keys) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = withContext(Dispatchers.IO) { runCatching { read() }.getOrNull() }
                delay(periodMs)
            }
        }
    }
}
