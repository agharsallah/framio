package dev.framio.mawaqit.remote

import android.util.Log
import dev.framio.mawaqit.core.Envelope
import dev.framio.mawaqit.core.RemoteSettings
import dev.framio.mawaqit.data.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Remote control from anywhere, without opening a port: the frame keeps one streaming subscription to an ntfy topic
 * and answers there. Every message is sealed with [Envelope] (AES-GCM, key only on the frame and in the owner's link),
 * so the public topic only ever carries opaque text. The frame never publishes on its own: ntfy.sh limits anonymous use.
 */
class NtfyBridge(private val rpc: Rpc, private val scope: CoroutineScope) {

    private var job: Job? = null
    private var current: Triple<String, String, String>? = null
    private val seen = LinkedHashSet<String>()

    @Volatile var connected = false
        private set

    fun apply(r: RemoteSettings) {
        val wanted = r.takeIf { it.ntfyEnabled && it.ntfyTopic.isNotEmpty() && it.ntfyKey.isNotEmpty() }
        val key = wanted?.let { Triple(it.ntfyServer, it.ntfyTopic, it.ntfyKey) }
        if (key == current && (key == null || job?.isActive == true)) return
        job?.cancel()
        current = key
        connected = false
        if (wanted != null) job = scope.launch(Dispatchers.IO) { subscribe(wanted) }
    }

    private suspend fun subscribe(r: RemoteSettings) {
        var since = "30s" // on (re)start, pick up a request sent while we were connecting
        var failures = 0
        while (scope.isActive) {
            try {
                val url = "${r.ntfyServer}/${enc(r.ntfyTopic)}/json?since=${enc(since)}"
                val c = Net.stream(url, readTimeoutMs = 120_000) // ntfy sends a keepalive every 45 s
                connected = true
                failures = 0
                Log.i(TAG, "subscribed to ${r.ntfyServer}/${r.ntfyTopic.take(10)}…")
                try {
                    runInterruptible {
                        c.inputStream.bufferedReader().forEachLine { line ->
                            val event = try { JSONObject(line) } catch (_: JSONException) { return@forEachLine }
                            event.optString("id").takeIf { it.isNotEmpty() }?.let { since = it }
                            if (event.optString("event") == "message") onMessage(r, event.optString("message"))
                        }
                    }
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.d(TAG, "stream ended: ${e.message}")
            }
            connected = false
            failures++
            delay((5_000L shl (failures - 1).coerceAtMost(5)).coerceAtMost(120_000L))
        }
    }

    private fun onMessage(r: RemoteSettings, message: String) {
        val text = Envelope.open(message, r.ntfyKey, r.ntfyTopic) ?: return // not ours, tampered, or our own echo
        val req = try { JSONObject(text) } catch (_: JSONException) { return }
        if (req.optString("t") != "rpc") return
        val id = req.optString("id")
        val age = System.currentTimeMillis() - req.optLong("ts")
        if (id.length < 8 || age > MAX_AGE_MS || age < -MAX_SKEW_MS || !remember(id)) {
            Log.w(TAG, "dropped request (age ${age / 1000} s, id ${id.take(6)})")
            return
        }
        scope.launch {
            val cmd = req.optString("cmd")
            val res = JSONObject().put("v", 1).put("t", "res").put("id", id).put("ts", System.currentTimeMillis())
            try {
                res.put("ok", true).put("result", rpc.call(cmd, req.optJSONObject("args") ?: JSONObject(), lan = false))
            } catch (e: Rpc.Failure) {
                res.put("ok", false).put("error", e.code).put("message", e.message)
            } catch (e: Exception) {
                Log.e(TAG, "rpc $cmd failed", e)
                res.put("ok", false).put("error", "internal").put("message", e.toString())
            }
            publish(r, res)
        }
    }

    private suspend fun publish(r: RemoteSettings, j: JSONObject) = withContext(Dispatchers.IO) {
        try {
            Net.post("${r.ntfyServer}/${enc(r.ntfyTopic)}", Envelope.seal(j.toString(), r.ntfyKey, r.ntfyTopic), mapOf("Content-Type" to "text/plain"))
        } catch (e: Exception) {
            Log.w(TAG, "publish failed: ${e.message}")
        }
    }

    @Synchronized
    private fun remember(id: String): Boolean {
        if (!seen.add(id)) return false
        if (seen.size > 256) seen.remove(seen.first())
        return true
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val TAG = "Ntfy"
        const val MAX_AGE_MS = 10 * 60_000L
        const val MAX_SKEW_MS = 2 * 60_000L
    }
}
