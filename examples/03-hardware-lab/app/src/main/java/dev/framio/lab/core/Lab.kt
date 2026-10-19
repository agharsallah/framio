package dev.framio.lab.core

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * One module of the app: a page in the side rail plus an optional automated self-test.
 * Each lab lives in its own package under `labs/` and only depends on `core/`.
 */
class Lab(
    /** Stable id, also used by `adb shell am start … --es lab <id>`. */
    val id: String,
    val title: String,
    /** One line for the overview tile. */
    val summary: String,
    val accent: Color,
    /** Automated probes. Runs off the main thread; may suspend (e.g. listen to a sensor for a second). */
    val selfTest: (suspend (Context) -> List<Check>)? = null,
    val content: @Composable () -> Unit,
)
