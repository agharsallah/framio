package dev.framio.pomodoro.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.framio.pomodoro.core.time.BootClock
import dev.framio.pomodoro.core.time.TimeGate
import java.io.File

/** This boot's monotonic clock: `elapsedRealtime` plus the kernel's boot id, which makes it comparable across kills. */
class AndroidBootClock(override val bootId: String = readBootId()) : BootClock {
    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()

    companion object {
        /** `/proc/sys/kernel/random/boot_id` changes every boot and is readable without root (M0 §7). */
        fun readBootId(): String = try {
            File("/proc/sys/kernel/random/boot_id").readText().trim()
        } catch (_: Exception) {
            // Without it every process looks like a new boot: deadlines fall back to the wall clock (safe, less exact)
            "unknown-" + (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000
        }
    }
}

/**
 * Feeds the system clock to [TimeGate]. The frame has no RTC: it boots at 01-01, then shows 2021-05-15, then the
 * real time about 36 s later (M0 §7). The gate trusts only times from the firmware's build date on (`Build.TIME`),
 * so this offers the clock at start and again on every `ACTION_TIME_CHANGED`.
 */
class TimeWatcher(
    private val gate: TimeGate,
    private val wall: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) {
    /** True if the gate took the clock as its (new) anchor. */
    fun offer(why: String): Boolean {
        val first = !gate.isTrusted
        val ok = gate.offer(wall(), elapsed())
        if (ok && first) Logs.event("time", "clock trusted ($why)")
        if (!ok) Logs.event("time", "clock not trusted yet ($why): ${wall()}")
        return ok
    }

    /** Calls [onChanged] with the gate's verdict whenever the clock or the zone changes. */
    fun register(context: Context, onChanged: (trusted: Boolean) -> Unit) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                onChanged(offer(intent.action?.substringAfterLast('.') ?: "change"))
            }
        }
        // Protected system broadcasts arrive whatever the export flag; NOT_EXPORTED keeps other apps out
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
