package dev.framio.hub.core.time

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.time.Instant
import java.time.ZoneOffset

/** Where a trusted time came from, best first. */
enum class TimeSource { Sntp, System, Http }

/** Wall time [wallMs] was true at [elapsedMs] (elapsedRealtime, this boot). */
data class TimeAnchor(val wallMs: Long, val elapsedMs: Long, val source: TimeSource)

/**
 * Decides when the wall clock can be trusted. The frame has no RTC: it boots at 01-01 01:00 and only learns
 * the time from the network. Until then the Hub shows `--:--` rather than a wrong time.
 *
 * Trusted time is an anchor to elapsed time, not an offset from the system clock, so it stays right when
 * Android corrects (or someone changes) the system clock later.
 */
class TimeGate {
    private val _anchor = MutableStateFlow<TimeAnchor?>(null)
    val anchor: StateFlow<TimeAnchor?> = _anchor.asStateFlow()

    val isTrusted get() = _anchor.value != null

    /** Wall time at [elapsedMs], or null while untrusted. */
    fun wallAt(elapsedMs: Long): Long? = _anchor.value?.let { it.wallMs + (elapsedMs - it.elapsedMs) }

    /**
     * Offers a candidate; returns true if it became the anchor. Implausible times (before this app was
     * written) are refused. A better source wins; a fresh SNTP answer always replaces an older one.
     */
    fun offer(candidate: TimeAnchor): Boolean {
        if (!plausible(candidate.wallMs)) return false
        val current = _anchor.value
        val better = current == null || candidate.source.ordinal < current.source.ordinal ||
            (candidate.source == TimeSource.Sntp && current.source == TimeSource.Sntp)
        if (better) _anchor.value = candidate
        return better
    }

    companion object {
        /** 2026-01-01: a clock showing anything earlier hasn't been set. */
        val EARLIEST: Long = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        private val LATEST: Long = Instant.parse("2100-01-01T00:00:00Z").toEpochMilli()

        fun plausible(wallMs: Long) = wallMs in EARLIEST until LATEST

        /** Year of a wall time, for logs. */
        fun year(wallMs: Long) = Instant.ofEpochMilli(wallMs).atOffset(ZoneOffset.UTC).year
    }
}

/**
 * Minimal SNTP client (RFC 4330), taken from the Lab where it measured a 9 ms offset on this frame. Packet
 * building and offset math are pure and unit-tested; [query] does the UDP round trip.
 */
object Sntp {
    /** Seconds from 1900 (NTP epoch) to 1970 (Unix epoch). */
    const val EPOCH_OFFSET_S = 2_208_988_800L

    /** [offsetMs] corrects the local clock at the moment the reply arrived ([localReceiveMs]). */
    data class Result(val server: String, val offsetMs: Long, val roundTripMs: Long, val localReceiveMs: Long)

    /** 48-byte client request: LI 0, version 3, mode 3 (client), transmit time = [nowMs]. */
    fun request(nowMs: Long): ByteArray = ByteArray(48).also {
        it[0] = 0x1B
        writeTimestamp(it, 40, nowMs)
    }

    fun writeTimestamp(buf: ByteArray, offset: Int, ms: Long) {
        val seconds = ms / 1000 + EPOCH_OFFSET_S
        val fraction = (ms % 1000) * 0x100000000L / 1000
        for (i in 0..3) buf[offset + i] = (seconds shr (24 - 8 * i)).toByte()
        for (i in 0..3) buf[offset + 4 + i] = (fraction shr (24 - 8 * i)).toByte()
    }

    fun readTimestamp(buf: ByteArray, offset: Int): Long {
        var seconds = 0L
        var fraction = 0L
        for (i in 0..3) seconds = (seconds shl 8) or (buf[offset + i].toLong() and 0xFF)
        for (i in 0..3) fraction = (fraction shl 8) or (buf[offset + 4 + i].toLong() and 0xFF)
        return (seconds - EPOCH_OFFSET_S) * 1000 + (fraction * 1000 + 0x80000000L) / 0x100000000L
    }

    /** θ = ((T2 − T1) + (T3 − T4)) / 2 and δ = (T4 − T1) − (T3 − T2). */
    fun offset(t1: Long, t2: Long, t3: Long, t4: Long): Pair<Long, Long> = ((t2 - t1) + (t3 - t4)) / 2 to (t4 - t1) - (t3 - t2)

    /** Blocking; call on an IO thread. Throws on timeout or a bad reply. */
    fun query(server: String = "pool.ntp.org", timeoutMs: Int = 3000, localNow: () -> Long = System::currentTimeMillis): Result {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val address = InetAddress.getByName(server)
            val t1 = localNow()
            val req = request(t1)
            socket.send(DatagramPacket(req, req.size, address, 123))
            val resp = ByteArray(48)
            socket.receive(DatagramPacket(resp, resp.size))
            val t4 = localNow()
            val mode = resp[0].toInt() and 0x7
            val stratum = resp[1].toInt() and 0xFF
            check(mode == 4 && stratum in 1..15) { "not a server reply (mode $mode, stratum $stratum)" }
            val (theta, delta) = offset(t1, readTimestamp(resp, 32), readTimestamp(resp, 40), t4)
            return Result(server, theta, delta, t4)
        }
    }
}
