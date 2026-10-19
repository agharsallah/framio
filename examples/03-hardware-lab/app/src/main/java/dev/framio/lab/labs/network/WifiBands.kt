package dev.framio.lab.labs.network

/** Wi-Fi frequency ↔ channel/band, and IPv4 int formatting. Pure Kotlin, unit-tested. */
object WifiBands {

    fun band(mhz: Int): String = when (mhz) {
        in 2400..2500 -> "2.4 GHz"
        in 4900..5900 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        else -> "?"
    }

    fun channel(mhz: Int): Int = when (mhz) {
        2484 -> 14
        in 2412..2472 -> (mhz - 2407) / 5
        in 5000..5900 -> (mhz - 5000) / 5
        in 5955..7115 -> (mhz - 5950) / 5
        else -> -1
    }

    /** WifiInfo.getIpAddress() is little-endian: 0x0100A8C0 is 192.168.0.1. */
    fun ipv4(littleEndian: Int): String =
        listOf(0, 8, 16, 24).joinToString(".") { ((littleEndian ushr it) and 0xFF).toString() }

    /** Rough quality from RSSI, like the status bar icon (0..4). */
    fun bars(rssi: Int): Int = when {
        rssi >= -55 -> 4
        rssi >= -66 -> 3
        rssi >= -77 -> 2
        rssi >= -88 -> 1
        else -> 0
    }
}
