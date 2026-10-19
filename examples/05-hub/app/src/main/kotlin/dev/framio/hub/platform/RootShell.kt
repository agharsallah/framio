package dev.framio.hub.platform

import android.os.SystemClock
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
 * The Hub's only use of root: the backlight. Two fixed commands; the one argument is a level checked to be
 * 0..255, so nothing from the network can ever reach a root shell.
 *
 * One long-running `su` reads commands on stdin, each followed by an end marker with the exit code (no
 * process start per call). If that doesn't work on this SuperSU (spike S3), it falls back to one `su -c`
 * per call; these calls are rare either way.
 */
class RootShell {
    private val mutex = Mutex()
    private var session: Session? = null
    private var persistentBroken = false
    private var counter = 0

    data class Result(val exit: Int, val out: String) {
        val ok get() = exit == 0
    }

    suspend fun backlight(level: Int): Boolean {
        require(level in 0..255) { "backlight level $level" }
        val start = SystemClock.elapsedRealtime()
        val r = run("echo $level > $BACKLIGHT")
        Logs.event("root", "backlight $level ${if (r.ok) "ok" else "failed (${r.exit}: ${r.out})"} ${SystemClock.elapsedRealtime() - start} ms")
        return r.ok
    }

    suspend fun readBacklight(): Int? = run("cat $BACKLIGHT").takeIf { it.ok }?.out?.trim()?.toIntOrNull()

    /** True when su answers as uid 0. */
    suspend fun available(): Boolean = run("id").out.contains("uid=0")

    private suspend fun run(command: String): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!persistentBroken) {
                persistent(command)?.let { return@withLock it }
                persistentBroken = true
                session?.close()
                session = null
                Logs.event("root", "persistent su didn't answer; using su -c per call")
            }
            once(command)
        }
    }

    /** Null if the persistent shell doesn't work. */
    private fun persistent(command: String): Result? {
        val s = session ?: Session.open()?.also { session = it } ?: return null
        val marker = "__END_${++counter}__"
        return s.exec(command, marker, TIMEOUT_MS) ?: run {
            s.close()
            session = null
            null
        }
    }

    private fun once(command: String): Result = try {
        val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { synchronized(out) { out.appendLine(it) } } }.apply {
            isDaemon = true
            start()
        }
        val exit = waitFor(p, TIMEOUT_MS)
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

    companion object {
        const val BACKLIGHT = "/sys/class/backlight/rk28_bl/brightness"

        /** The first call may show SuperSU's prompt on another frame; here root is silent, 3 s is plenty. */
        private const val TIMEOUT_MS = 3_000L
    }
}
