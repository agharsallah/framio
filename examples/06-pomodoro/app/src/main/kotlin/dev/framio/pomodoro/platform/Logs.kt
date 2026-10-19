package dev.framio.pomodoro.platform

import android.util.Log

/**
 * Structured log lines for tests on the frame: `adb logcat -s Pomodoro`.
 *  - `EVENT|area|text`: something happened (a phase change, a cue, a root write)
 *  - `CHECK|area|name|PASS/WARN/FAIL|detail`: a self-test result (`--es cmd selftest`)
 * Release builds keep these (Log.i/w); nothing here is secret.
 */
object Logs {
    const val TAG = "Pomodoro"

    fun event(area: String, text: String) {
        Log.i(TAG, "EVENT|$area|$text")
    }

    fun warn(area: String, text: String, e: Throwable? = null) {
        Log.w(TAG, "EVENT|$area|$text", e)
    }

    fun check(area: String, name: String, status: Status, detail: String) {
        Log.i(TAG, "CHECK|$area|$name|$status|$detail")
    }

    enum class Status { PASS, WARN, FAIL }
}
