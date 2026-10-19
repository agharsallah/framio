package dev.framio.lab.core

import android.util.Log

/**
 * Logs things a person did during a hands-on test (a knock, a clap, a key press) as `EVENT|lab|text`,
 * so they can be checked afterwards with `adb logcat -s FramioLab`.
 */
object Events {
    private val lastByKey = HashMap<String, Long>()

    fun log(lab: String, text: String) {
        Log.i(SelfTestRunner.TAG, "EVENT|$lab|$text")
    }

    /** Logs at most once per [intervalMs] for the same [key] (e.g. a loud mic block). */
    fun throttled(lab: String, key: String, intervalMs: Long, text: () -> String) {
        val now = System.currentTimeMillis()
        synchronized(lastByKey) {
            if (now - (lastByKey[key] ?: 0L) < intervalMs) return
            lastByKey[key] = now
        }
        log(lab, text())
    }
}
