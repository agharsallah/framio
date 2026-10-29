// WifiManager's network calls are deprecated since API 29 for Wi-Fi suggestions; on the frame (API 23) they are the API
@file:Suppress("DEPRECATION")

package dev.framio.launcher

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One network in the list: the strongest access point of each name. [level] is 0..4 bars. */
data class Network(val ssid: String, val level: Int, val secured: Boolean, val saved: Boolean, val current: Boolean)

/**
 * Wi-Fi state for Home's status corner and the Controls panel, kept current by the system's broadcasts while
 * [start]ed. The network list is only kept while the Wi-Fi tab shows it ([listing]): Home needs just name and bars.
 *
 * The frame's RK915 radio is 2.4 GHz only (device-log), so every network listed is one it can join. Android 6 hands
 * scan results only to apps with the location permission *and* with location turned on; [Device.provision] grants the
 * first, and when the list still comes back empty, root `dumpsys wifi` has the same scan.
 */
class Wifi(context: Context, private val scope: CoroutineScope) {

    private val app = context.applicationContext
    private val wm = app.getSystemService(WifiManager::class.java)

    var enabled by mutableStateOf(wm.isWifiEnabled)
        private set

    /** Turning on or off, or scanning. */
    val busy get() = toggling || scanning

    private var toggling by mutableStateOf(false)
    private var scanning by mutableStateOf(false)

    /** The joined network's name, or what it is doing ("Connecting…"), null when not on any. */
    var status by mutableStateOf<String?>(null)
        private set

    /** Signal of the joined network, 0..4 bars, null when not joined. */
    var level by mutableStateOf<Int?>(null)
        private set

    var networks by mutableStateOf(emptyList<Network>())
        private set

    /** Set by the Wi-Fi tab while it is on screen. */
    var listing = false
        set(v) {
            field = v
            if (v) refreshList(fresh = true)
        }

    private var rootScanJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> {
                    scanning = false
                    refreshList(fresh = true)
                }
                WifiManager.NETWORK_STATE_CHANGED_ACTION -> refreshList(fresh = false)
                WifiManager.WIFI_STATE_CHANGED_ACTION -> when (wm.wifiState) {
                    WifiManager.WIFI_STATE_ENABLED -> {
                        toggling = false
                        scan()
                    }
                    WifiManager.WIFI_STATE_DISABLED -> {
                        toggling = false
                        scanning = false
                        networks = emptyList()
                    }
                }
            }
            refresh()
        }
    }

    fun start() {
        app.registerReceiver(receiver, IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
        })
        refresh()
    }

    fun stop() = app.unregisterReceiver(receiver)

    fun turn(on: Boolean) {
        if (wm.setWifiEnabled(on)) toggling = true
    }

    fun scan() {
        if (wm.isWifiEnabled && wm.startScan()) scanning = true
    }

    /** Joins a saved network, or saves a new one with [password] (null for an open network) and joins it. */
    fun connect(n: Network, password: String?): Boolean {
        val quoted = "\"${n.ssid}\""
        val id = saved().firstOrNull { it.SSID == quoted }?.networkId?.takeIf { password == null }
            ?: wm.addNetwork(WifiConfiguration().apply {
                SSID = quoted
                if (password == null) {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                } else {
                    preSharedKey = "\"$password\""
                }
            })
        if (id < 0) return false
        val ok = wm.enableNetwork(id, true) // true: disconnect from others and join this one
        wm.saveConfiguration()
        wm.reconnect()
        refresh()
        refreshList(fresh = false)
        return ok
    }

    /** Android 6 only lets an app remove networks it added itself; returns false for the others. */
    fun forget(n: Network): Boolean {
        val config = saved().firstOrNull { it.SSID == "\"${n.ssid}\"" } ?: return false
        val ok = wm.removeNetwork(config.networkId)
        if (ok) wm.saveConfiguration()
        refreshList(fresh = false)
        return ok
    }

    private fun refresh() {
        enabled = wm.isWifiEnabled
        val info = wm.connectionInfo
        val currentSsid = info?.ssid?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }
        status = when {
            !enabled -> null
            info == null -> null
            info.supplicantState == SupplicantState.COMPLETED && currentSsid != null -> currentSsid
            info.supplicantState in CONNECTING -> "Connecting…"
            else -> null
        }
        level = info?.rssi?.takeIf { status != null && status != "Connecting…" }?.let { WifiManager.calculateSignalLevel(it, 5) }
    }

    /**
     * The list, only while the tab shows it. [fresh]: a new scan arrived, so root's copy (one `dumpsys`, about a
     * second) may be read when Android hands us nothing; otherwise only names and flags are updated.
     */
    private fun refreshList(fresh: Boolean) {
        if (!listing || !wm.isWifiEnabled) return
        val current = wm.connectionInfo?.ssid?.removeSurrounding("\"")
        val saved = saved().mapNotNull { it.SSID?.removeSurrounding("\"") }.toSet()
        val results = scanResults()
        when {
            results.isNotEmpty() -> networks = merge(results.map { Seen(it.SSID, it.level, isSecured(it.capabilities)) }, saved, current)
            fresh && rootScanJob?.isActive != true -> rootScanJob = scope.launch { networks = merge(rootScan(), saved, current) }
            else -> networks = networks.map { it.copy(saved = it.ssid in saved, current = it.ssid == current) }
        }
    }

    // Lint asks for fine location (what API 29+ wants); API 23 needs coarse, granted by Device.provision. Without it
    // Android returns an empty list or throws, and the list falls back to root's view of the scan.
    @SuppressLint("MissingPermission")
    private fun saved(): List<WifiConfiguration> = try {
        wm.configuredNetworks.orEmpty()
    } catch (_: SecurityException) {
        emptyList()
    }

    @SuppressLint("MissingPermission")
    private fun scanResults(): List<ScanResult> = try {
        wm.scanResults.orEmpty()
    } catch (_: SecurityException) {
        emptyList()
    }

    private data class Seen(val ssid: String, val rssi: Int, val secured: Boolean)

    private fun merge(seen: List<Seen>, saved: Set<String>, current: String?): List<Network> =
        seen.filter { it.ssid.isNotBlank() }
            .groupBy { it.ssid }
            .map { (ssid, aps) ->
                val best = aps.maxBy { it.rssi }
                Network(ssid, WifiManager.calculateSignalLevel(best.rssi, 5), best.secured, ssid in saved, ssid == current)
            }
            // joined first, then saved, then by signal
            .sortedWith(compareByDescending<Network> { it.current }.thenByDescending { it.saved }.thenByDescending { it.level })

    private suspend fun rootScan(): List<Seen> {
        val out = Root.run("dumpsys wifi").takeIf { it.ok }?.out ?: return emptyList()
        return parseDumpsysScan(out).map { Seen(it.first, it.second, isSecured(it.third)) }
    }

    private fun isSecured(capabilities: String?) =
        capabilities != null && (capabilities.contains("WPA") || capabilities.contains("WEP") || capabilities.contains("PSK"))

    private companion object {
        const val UNKNOWN_SSID = "<unknown ssid>"
        val CONNECTING = setOf(
            SupplicantState.ASSOCIATING,
            SupplicantState.ASSOCIATED,
            SupplicantState.AUTHENTICATING,
            SupplicantState.FOUR_WAY_HANDSHAKE,
            SupplicantState.GROUP_HANDSHAKE,
        )
    }
}

/**
 * (ssid, rssi, flags) from the "Latest scan results" table of AOSP 6's `dumpsys wifi`:
 * `  bssid  frequency  rssi  age  ssid…  [flags]…`. The SSID may contain spaces: it runs up to the first `[`.
 */
internal fun parseDumpsysScan(out: String): List<Triple<String, Int, String>> {
    val lines = out.lines()
    val start = lines.indexOfFirst { it.contains("Latest scan results") }
    if (start < 0) return emptyList()
    val bssid = Regex("^\\s*([0-9a-f]{2}(:[0-9a-f]{2}){5})\\s+(\\d+)\\s+(-?\\d+)\\s+(\\S+)\\s+(.*)$", RegexOption.IGNORE_CASE)
    return lines.drop(start + 1)
        .takeWhile { it.isBlank() || bssid.matches(it) || it.trimStart().startsWith("BSSID") }
        .mapNotNull { line ->
            val m = bssid.find(line) ?: return@mapNotNull null
            val rest = m.groupValues[6]
            val flagsAt = rest.indexOf('[').let { if (it < 0) rest.length else it }
            val ssid = rest.substring(0, flagsAt).trim()
            Triple(ssid, m.groupValues[4].toInt(), rest.substring(flagsAt))
        }
}
