package dev.framio.lab.labs.time

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Minimal SNTP client (RFC 4330): how far off is the frame's clock?
 * Packet building and offset math are pure functions, unit-tested; [query] does the UDP round trip.
 */
object Sntp {

    /** Seconds from 1900 (NTP epoch) to 1970 (Unix epoch). */
    const val EPOCH_OFFSET_S = 2_208_988_800L

    data class Result(val server: String, val offsetMs: Long, val roundTripMs: Long, val serverTimeMs: Long, val stratum: Int)

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
        return (seconds - EPOCH_OFFSET_S) * 1000 + (fraction * 1000 + 0x80000000L) / 0x100000000L // round to nearest ms
    }

    /**
     * Clock offset θ = ((T2 − T1) + (T3 − T4)) / 2 and round trip δ = (T4 − T1) − (T3 − T2), where
     * T1 = request sent (local), T2 = received (server), T3 = reply sent (server), T4 = reply received (local).
     */
    fun offset(t1: Long, t2: Long, t3: Long, t4: Long): Pair<Long, Long> = ((t2 - t1) + (t3 - t4)) / 2 to (t4 - t1) - (t3 - t2)

    fun query(server: String = "pool.ntp.org", timeoutMs: Int = 3000): Result {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val address = InetAddress.getByName(server)
            val t1 = System.currentTimeMillis()
            val req = request(t1)
            socket.send(DatagramPacket(req, req.size, address, 123))
            val resp = ByteArray(48)
            socket.receive(DatagramPacket(resp, resp.size))
            val t4 = System.currentTimeMillis()
            val t2 = readTimestamp(resp, 32)
            val t3 = readTimestamp(resp, 40)
            val (theta, delta) = offset(t1, t2, t3, t4)
            return Result("$server (${address.hostAddress})", theta, delta, t4 + theta, resp[1].toInt() and 0xFF)
        }
    }
}
