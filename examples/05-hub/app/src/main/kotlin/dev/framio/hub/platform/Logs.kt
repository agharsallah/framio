package dev.framio.hub.platform

import android.util.Log

/**
 * Structured log lines for tests on the frame: `adb logcat -s FramioHub`.
 *  - `EVENT|area|text`: something happened (a knock, a root write, a feed refresh)
 *  - `CHECK|area|name|PASS/WARN/FAIL|detail`: a self-test result
 * Release builds keep these (Log.i/w/e); R8 strips Log.d and Log.v (proguard-rules.pro).
 */
object Logs {
    const val TAG = "FramioHub"

    fun event(area: String, text: String) {
        Log.i(TAG, "EVENT|$area|$text")
    }

    fun check(area: String, name: String, status: Status, detail: String) {
        Log.i(TAG, "CHECK|$area|$name|$status|$detail")
    }

    fun warn(area: String, text: String, e: Throwable? = null) {
        Log.w(TAG, "EVENT|$area|$text", e)
    }

    enum class Status { PASS, WARN, FAIL }

    /** Calendar links are secrets: keep the host, hide the rest. */
    fun redact(url: String): String = Regex("""^(https?://[^/]+/).*$""").replace(url) { "${it.groupValues[1]}****" }
}
