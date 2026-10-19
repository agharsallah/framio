package dev.framio.hub.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import dev.framio.hub.core.engine.HubClock
import dev.framio.hub.core.engine.Now
import dev.framio.hub.core.time.Sntp
import dev.framio.hub.core.time.TimeAnchor
import dev.framio.hub.core.time.TimeGate
import dev.framio.hub.core.time.TimeSource as AnchorSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

/**
 * The Hub's clock: elapsed time from `SystemClock`, the boot id from the kernel, and the wall clock only
 * once [TimeGate] trusts it (our own SNTP answer, or a system clock that's plausibly been set by NTP).
 * Emits on minute boundaries for the UI, and when the zone or trust changes.
 */
class TimeSource(private val context: Context, private val scope: CoroutineScope) : HubClock {
    val gate = TimeGate()
    val bootId: String = readBootId()
    private val _zone = MutableStateFlow(ZoneId.systemDefault())
    val zone: StateFlow<ZoneId> = _zone.asStateFlow()

    /** Called when the clock or zone changed: the scheduler recomputes. */
    var onChanged: () -> Unit = {}

    override fun now(): Now {
        val elapsed = SystemClock.elapsedRealtime()
        return Now(elapsed, bootId, gate.wallAt(elapsed), _zone.value)
    }

    /** [Now] at every minute boundary (while collected), plus whenever trust or the zone changes. */
    val minutes: Flow<Now> = combine(gate.anchor, _zone) { _, _ -> }.let { changes ->
        channelFlow {
            changes.collectLatest {
                while (true) {
                    val now = now()
                    send(now)
                    val wall = now.wallMs ?: now.elapsedMs
                    delay(60_000 - wall % 60_000 + 5) // just past the boundary, so the minute has changed
                }
            }
        }
    }

    /** [Now] every second, for countdowns (only collected where one is on screen). */
    val seconds: Flow<Now> = channelFlow {
        while (true) {
            val now = now()
            send(now)
            delay(1_000 - now.elapsedMs % 1_000 + 2)
        }
    }

    fun start() {
        trustSystemClockIfSet("start")
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
                        _zone.value = ZoneId.systemDefault()
                        Logs.event("time", "zone ${_zone.value}")
                    } else {
                        trustSystemClockIfSet("TIME_CHANGED")
                    }
                    onChanged()
                }
            },
            filter,
        )
        scope.launch { syncLoop() }
    }

    /** SNTP now and then: soon after start (Wi-Fi may need a while), then every 6 h to correct drift. */
    private suspend fun syncLoop() {
        var failures = 0
        while (true) {
            val ok = withContext(Dispatchers.IO) { syncOnce() }
            failures = if (ok) 0 else failures + 1
            delay(if (ok) 6 * 3_600_000L else minOf(10_000L shl minOf(failures, 6), 15 * 60_000L))
        }
    }

    private fun syncOnce(): Boolean = SERVERS.any { server ->
        try {
            val r = Sntp.query(server)
            val elapsed = SystemClock.elapsedRealtime()
            val wall = System.currentTimeMillis() + r.offsetMs
            val first = !gate.isTrusted
            if (gate.offer(TimeAnchor(wall, elapsed, AnchorSource.Sntp))) {
                Logs.event("time", "sntp $server offset ${r.offsetMs} ms rtt ${r.roundTripMs} ms${if (first) ", time trusted" else ""}")
                onChanged()
            }
            true
        } catch (e: Exception) {
            Logs.warn("time", "sntp $server failed: ${e.message}")
            false
        }
    }

    /** A system clock past 2026 was set by the network (the frame has no RTC), so it can be trusted. */
    fun trustSystemClockIfSet(why: String) {
        val wall = System.currentTimeMillis()
        if (TimeGate.plausible(wall) && gate.offer(TimeAnchor(wall, SystemClock.elapsedRealtime(), AnchorSource.System))) {
            Logs.event("time", "system clock trusted ($why)")
            onChanged()
        }
    }

    /** An HTTPS Date header, the last resort (1 s resolution). */
    fun offerHttpDate(wallMs: Long) {
        if (gate.offer(TimeAnchor(wallMs, SystemClock.elapsedRealtime(), AnchorSource.Http))) {
            Logs.event("time", "trusted from an HTTPS Date header")
            onChanged()
        }
    }

    val trusted: Flow<Boolean> = gate.anchor.map { it != null }.distinctUntilChanged()

    companion object {
        private val SERVERS = listOf("pool.ntp.org", "time.google.com", "time.cloudflare.com")

        /** `/proc/sys/kernel/random/boot_id` changes every boot: what makes elapsedRealtime comparable. */
        fun readBootId(): String = try {
            File("/proc/sys/kernel/random/boot_id").readText().trim()
        } catch (e: Exception) {
            // Without it, treat each process as a new boot: deadlines fall back to the wall clock (safe, less exact)
            "unknown-" + (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000
        }
    }
}
