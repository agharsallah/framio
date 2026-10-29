package dev.framio.launcher

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Root on this frame: SuperSU grants every app silently (device-log). The launcher uses it for what Android 6 keeps
 * from ordinary apps: closing other apps, listing their processes, and granting itself the permissions the frame has
 * no Settings screen for.
 *
 * Commands are only ever built here from fixed strings and checked arguments ([pkg] validates package names), so
 * nothing typed or received can reach the shell.
 *
 * One long-running `su` reads commands on stdin, each followed by an end marker with the exit code: no process start
 * per call (~100 ms each on the RK3126). If that shell stops answering, it falls back to one `su -c` per call
 * (this SuperSU ignores multi-line `-c` scripts, so every command is a single line).
 */
object Root {

    data class Result(val exit: Int, val out: String) {
        val ok get() = exit == 0
    }

    private val mutex = Mutex()
    private var session: Session? = null
    private var persistentBroken = false
    private var counter = 0

    @Volatile
    var available: Boolean? = null
        private set

    suspend fun check(): Boolean = available ?: run("id").out.contains("uid=0").also { available = it }

    suspend fun run(command: String, timeoutMs: Long = TIMEOUT_MS): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!persistentBroken) {
                persistent(command, timeoutMs)?.let { return@withLock it }
                persistentBroken = true
                session?.close()
                session = null
                Log.w(TAG, "persistent su didn't answer; using su -c per call")
            }
            once(command, timeoutMs)
        }
    }

    /** A package name, or an exception: the only free-form argument that ever goes into a command. */
    fun pkg(name: String): String {
        require(PACKAGE.matches(name)) { "not a package name: $name" }
        return name
    }

    suspend fun forceStop(pkg: String) = run("am force-stop ${pkg(pkg)}").ok

    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    /** Null if the persistent shell doesn't work. */
    private fun persistent(command: String, timeoutMs: Long): Result? {
        val s = session ?: Session.open()?.also { session = it } ?: return null
        val marker = "__END_${++counter}__"
        return s.exec(command, marker, timeoutMs) ?: run {
            s.close()
            session = null
            null
        }
    }

    private fun once(command: String, timeoutMs: Long): Result = try {
        val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { synchronized(out) { out.appendLine(it) } } }.apply {
            isDaemon = true
            start()
        }
        val exit = waitFor(p, timeoutMs)
        if (exit == null) p.destroy() else reader.join(500)
        Result(exit ?: -2, synchronized(out) { out.toString().trim() })
    } catch (e: Exception) { // no su binary
        Result(-1, e.message ?: e.javaClass.simpleName)
    }

    /** Process.waitFor(timeout) is API 26: poll exitValue() instead. Null on timeout. */
    private fun waitFor(p: Process, timeoutMs: Long): Int? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                return p.exitValue()
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(20)
            }
        }
        return null
    }

    private class Session(private val process: Process) {
        private val writer = OutputStreamWriter(process.outputStream)
        private val lines = LinkedBlockingQueue<String>()

        init {
            Thread {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).forEachLine { lines.put(it) }
                } catch (_: Exception) {
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        fun exec(command: String, marker: String, timeoutMs: Long): Result? = try {
            lines.clear() // leftovers of a command that timed out
            writer.write("$command 2>&1; echo $marker $?\n")
            writer.flush()
            val out = StringBuilder()
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            var result: Result? = null
            while (result == null) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) break
                val line = lines.poll(left, TimeUnit.MILLISECONDS) ?: break
                if (line.startsWith(marker)) {
                    result = Result(line.removePrefix(marker).trim().toIntOrNull() ?: -3, out.toString().trim())
                } else {
                    out.appendLine(line)
                }
            }
            result
        } catch (_: Exception) {
            null
        }

        fun close() {
            try {
                writer.write("exit\n")
                writer.flush()
            } catch (_: Exception) {
            }
            process.destroy()
        }

        companion object {
            fun open(): Session? = try {
                Session(ProcessBuilder("su").redirectErrorStream(true).start())
            } catch (_: Exception) {
                null
            }
        }
    }

    /** `dumpsys` and `settings` start a Java VM (~1 s here): generous, but a missing su still fails fast. */
    private const val TIMEOUT_MS = 6_000L
    private const val TAG = "Root"
}
