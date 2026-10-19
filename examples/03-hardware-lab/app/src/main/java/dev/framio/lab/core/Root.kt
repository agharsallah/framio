package dev.framio.lab.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Runs shell commands as root through SuperSU's `su`.
 * The first call shows SuperSU's Grant/Deny prompt on the frame; it waits for the tap, hence the long timeout.
 */
object Root {

    data class Result(val exit: Int, val out: String, val err: String) {
        val ok get() = exit == 0
    }

    suspend fun run(command: String, timeoutMs: Long = 20_000): Result = withContext(Dispatchers.IO) {
        try {
            // One command per `su -c`: this SuperSU (2.45) ignores multi-line scripts passed with -c
            val process = ProcessBuilder("su", "-c", command).start()
            // Drain both pipes on their own threads so a stuck su can't block us past the timeout
            val out = StringBuilder()
            val err = StringBuilder()
            val readers = listOf(process.inputStream to out, process.errorStream to err).map { (stream, sink) ->
                Thread { stream.bufferedReader().forEachLine { synchronized(sink) { sink.appendLine(it) } } }
                    .apply { isDaemon = true; start() }
            }
            val exit = waitFor(process, timeoutMs)
            if (exit == null) {
                process.destroy()
                Result(-2, out.toString().trim(), "timeout")
            } else {
                readers.forEach { it.join(1000) }
                Result(exit, out.toString().trim(), err.toString().trim())
            }
        } catch (e: Exception) { // no su binary
            Result(-1, "", e.message ?: e.javaClass.simpleName)
        }
    }

    /** Process.waitFor(timeout) only exists from API 26: poll exitValue() instead. Null on timeout. */
    private suspend fun waitFor(process: Process, timeoutMs: Long): Int? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                return process.exitValue()
            } catch (_: IllegalThreadStateException) { // still running
                delay(50)
            }
        }
        return null
    }

    /** `su -c id` → uid=0. */
    suspend fun available(): Boolean = run("id", timeoutMs = 30_000).out.contains("uid=0")
}
