package dev.framio.lab.core

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Runs the labs' automated probes one after another.
 * Every result is logged as `CHECK|lab|name|STATUS|detail` (tag [TAG]) and the full run is written to
 * `files/selftest.json`, so a run started over adb can be read back without looking at the screen:
 *
 *     adb shell am start -n dev.framio.lab/.LabActivity --ez selftest true
 *     adb logcat -s FramioLab
 *     adb shell run-as dev.framio.lab cat files/selftest.json
 */
class SelfTestRunner(
    private val context: Context,
    private val scope: CoroutineScope,
    private val labs: () -> List<Lab>,
) {

    var running by mutableStateOf(false)
        private set

    /** Title of the lab being probed right now. */
    var current by mutableStateOf<String?>(null)
        private set

    val results = mutableStateListOf<Check>()

    fun forLab(id: String): List<Check> = results.filter { it.lab == id }

    /** Runs all labs, or only [onlyLab]. Results of other labs are kept when running one. */
    fun run(onlyLab: String? = null) {
        if (running) return
        running = true
        scope.launch {
            val chosen = labs().filter { it.selfTest != null && (onlyLab == null || it.id == onlyLab) }
            if (onlyLab == null) results.clear() else results.removeAll { it.lab == onlyLab }
            Log.i(TAG, "SELFTEST_START labs=${chosen.joinToString(",") { it.id }}")
            for (lab in chosen) {
                current = lab.title
                val checks = withContext(Dispatchers.Default) {
                    withTimeoutOrNull(LAB_TIMEOUT_MS) {
                        try {
                            lab.selfTest!!(context)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) { // incl. NoSuchMethodError from an API newer than Android 6
                            listOf(Check(lab.id, "self-test", Status.FAIL, "${e.javaClass.simpleName}: ${e.message}"))
                        }
                    } ?: listOf(Check(lab.id, "self-test", Status.FAIL, "timed out after ${LAB_TIMEOUT_MS / 1000}s"))
                }
                checks.forEach { Log.i(TAG, "CHECK|${it.lab}|${it.name}|${it.status}|${it.detail.replace('\n', ' ')}") }
                results.addAll(checks)
            }
            current = null
            val file = withContext(Dispatchers.IO) { writeReport() }
            val counts = Status.entries.joinToString(" ") { s -> "${s.name.lowercase()}=${results.count { it.status == s }}" }
            Log.i(TAG, "SELFTEST_DONE $counts report=${file.path}")
            running = false
        }
    }

    private fun writeReport(): File {
        val json = JSONObject()
            .put("time", System.currentTimeMillis())
            .put("checks", JSONArray().apply {
                results.forEach { c ->
                    put(JSONObject().put("lab", c.lab).put("name", c.name).put("status", c.status.name).put("detail", c.detail))
                }
            })
        return File(context.filesDir, "selftest.json").apply { writeText(json.toString(2)) }
    }

    companion object {
        const val TAG = "FramioLab"
        private const val LAB_TIMEOUT_MS = 90_000L
    }
}
