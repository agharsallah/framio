package dev.framio.mawaqit.data

import android.util.Log
import dev.framio.mawaqit.core.AdhkarSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Phone reminders: one short plain-text message to the user's own ntfy topic (subscribed in the ntfy app) when a
 * scheduled reminder fires. Opt-in and off by default. Unlike the remote control (remote/NtfyBridge, end-to-end
 * encrypted, answers only), this is published in clear on purpose so the phone can show it: it carries only the
 * reminder's text, nothing else of the frame. At most [MAX_PER_DAY] a day, well inside ntfy.sh's anonymous limits.
 */
class AdhkarPush(private val scope: CoroutineScope) {
    private var day: LocalDate? = null
    private var sent = 0

    /** False when it is off, has no topic, or today's quota is used. Publishing happens in the background. */
    fun send(s: AdhkarSettings, today: LocalDate, title: String, text: String, test: Boolean = false): Boolean {
        if ((!s.pushEnabled && !test) || s.pushTopic.isEmpty()) return false
        if (day != today) {
            day = today
            sent = 0
        }
        if (sent >= MAX_PER_DAY) {
            Log.w(TAG, "daily quota of $MAX_PER_DAY used, not sent: $title")
            return false
        }
        sent++
        // JSON publishing (POST to the server's root) keeps Arabic titles intact: HTTP headers are Latin-1 only
        val body = JSONObject().put("topic", s.pushTopic).put("title", title.take(120)).put("message", text.take(400))
            .put("tags", JSONArray().put("crescent_moon")).toString()
        scope.launch(Dispatchers.IO) {
            try {
                Net.post(s.pushServer + "/", body, mapOf("Content-Type" to "application/json"))
                Log.i(TAG, "sent to ${s.pushServer}/${s.pushTopic.take(4)}…: $title")
            } catch (e: Exception) {
                Log.w(TAG, "push failed: ${e.message}")
            }
        }
        return true
    }

    private companion object {
        const val TAG = "AdhkarPush"
        const val MAX_PER_DAY = 12
    }
}
