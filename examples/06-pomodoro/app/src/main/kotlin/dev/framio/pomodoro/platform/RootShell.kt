package dev.framio.pomodoro.platform

import android.os.SystemClock

/** The backlight, as [DisplayPower] sees it: so its rules are unit-testable without root. */
interface Backlight {
    /** The current level 0..255, or null if it can't be read. Blocking (a root call): never on the main thread. */
    fun read(): Int?

    /** True if [level] (0..255) was written. Blocking. */
    fun write(level: Int): Boolean
}

/**
 * Pomodoro's only use of root: the backlight file. Two fixed commands; the one argument is checked to be 0..255.
 * One command per `su -c` (SuperSU here ignores multi-line scripts), polling `exitValue()` because
 * `Process.waitFor(timeout)` is API 26.
 */
class RootShell : Backlight {
    override fun write(level: Int): Boolean {
        require(level in 0..255) { "backlight level $level" }
        val start = SystemClock.elapsedRealtime()
        val r = runBlockingCommand("echo $level > $BACKLIGHT")
        Logs.event("root", "backlight $level ${if (r.first == 0) "ok" else "failed (${r.first}: ${r.second})"} ${SystemClock.elapsedRealtime() - start} ms")
        return r.first == 0
    }

    override fun read(): Int? = runBlockingCommand("cat $BACKLIGHT").takeIf { it.first == 0 }?.second?.trim()?.toIntOrNull()

    /** True when `su` answers as uid 0. Blocking. */
    fun available(): Boolean = runBlockingCommand("id").second.contains("uid=0")

    companion object {
        const val BACKLIGHT = "/sys/class/backlight/rk28_bl/brightness"
        private const val TIMEOUT_MS = 3_000L

        /** Exit -1: no su; -2: timeout. Synchronised: one root call at a time. */
        @Synchronized
        fun runBlockingCommand(command: String, timeoutMs: Long = TIMEOUT_MS): Pair<Int, String> = try {
            val p = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = Thread { p.inputStream.bufferedReader().forEachLine { synchronized(out) { out.appendLine(it) } } }.apply {
                isDaemon = true
                start()
            }
            val exit = waitFor(p, timeoutMs)
            if (exit == null) p.destroy() else reader.join(500)
            (exit ?: -2) to synchronized(out) { out.toString().trim() }
        } catch (e: Exception) { // no su binary
            -1 to (e.message ?: e.javaClass.simpleName)
        }

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
    }
}
