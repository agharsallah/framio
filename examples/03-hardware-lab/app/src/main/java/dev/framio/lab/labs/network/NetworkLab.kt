package dev.framio.lab.labs.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.framio.lab.core.Check
import dev.framio.lab.core.CheckList
import dev.framio.lab.core.Events
import dev.framio.lab.core.Format
import dev.framio.lab.core.Lab
import dev.framio.lab.core.LabHost
import dev.framio.lab.core.LocalHost
import dev.framio.lab.core.Status
import dev.framio.lab.core.ui.ActionButton
import dev.framio.lab.core.ui.Actions
import dev.framio.lab.core.ui.InkDim
import dev.framio.lab.core.ui.KeyValues
import dev.framio.lab.core.ui.LabPage
import dev.framio.lab.core.ui.Mono
import dev.framio.lab.core.ui.Note
import dev.framio.lab.core.ui.Section
import dev.framio.lab.core.ui.SelfTestSection
import dev.framio.lab.core.ui.Sky
import dev.framio.lab.core.ui.StatGrid
import dev.framio.lab.core.ui.Verdict
import dev.framio.lab.core.ui.rememberPolled
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

/** RK915 Wi-Fi (2.4 GHz): link, scan, latency, TLS compatibility, speed, mDNS, Wi-Fi Direct, a local web server. */
object NetworkLab {

    val lab = Lab(
        id = "network",
        title = "Network",
        summary = "Wi-Fi link and scan, latency, HTTPS/TLS compatibility, speed, mDNS, local web server",
        accent = Sky,
        selfTest = ::selfTest,
        content = { Content() },
    )

    const val SERVER_PORT = 8080

    /** Kept across visits to the lab, so the server can stay up while you test from a phone. */
    private val server = LocalServer(SERVER_PORT) {
        LocalServer.uptimeJson(mapOf("device" to android.os.Build.MODEL, "android" to android.os.Build.VERSION.RELEASE))
    }

    @Composable
    private fun Content() {
        val host = LocalHost.current
        LabPage("Network", "Rockchip RK915 on SDIO: 802.11b/g/n, 2.4 GHz only, Wi-Fi Direct capable. No Ethernet, no Bluetooth.") {
            LinkSection(host.context)
            ScanSection(host)
            ProbeSection()
            NsdSection(host.context)
            ServerSection(host.context)
            P2pSection(host)
            SelfTestSection(lab.id, note = "Checks the link, DNS, TCP, TLS against 6 hosts, a 10 MB download, mDNS (5 s) and the local server.")
        }
    }

    @Composable
    private fun LinkSection(context: Context) {
        val state by rememberPolled(2000) { NetProbes.wifi(context) to NetProbes.link(context) }
        Section("Link", note = "WifiManager.getConnectionInfo + ConnectivityManager.getLinkProperties, refreshed every 2 s.") {
            val (w, l) = state ?: return@Section
            if (w == null) {
                Verdict(Status.FAIL, "Wi-Fi not connected")
                return@Section
            }
            StatGrid(
                listOf(
                    "SSID" to w.ssid,
                    "Signal" to "${w.rssi} dBm (${WifiBands.bars(w.rssi)}/4)",
                    "Band / channel" to "${WifiBands.band(w.mhz)} · ch ${WifiBands.channel(w.mhz)} (${w.mhz} MHz)",
                    "Link speed" to "${w.linkMbps} Mbps",
                    "IP" to w.ip,
                    "Gateway" to (l?.gateway ?: "?"),
                    "DNS" to (l?.dns?.joinToString() ?: "?"),
                    "Internet validated" to (l?.validated?.toString() ?: "?"),
                    "5 GHz supported" to w.fiveGhz.toString(),
                    "Wi-Fi Direct" to w.p2p.toString(),
                    "MAC (as apps see it)" to (w.mac ?: "?"),
                    "Interface" to (l?.iface ?: "?"),
                ),
            )
        }
    }

    @Composable
    private fun ScanSection(host: LabHost) {
        val context = host.context
        host.permissionVersion
        val allowed = host.granted(Manifest.permission.ACCESS_FINE_LOCATION)
        var results by remember { mutableStateOf<List<ScanResult>?>(null) }
        var scanning by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val locationMode = Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, 0)
        Section(
            "Scan",
            note = "WifiManager.startScan + SCAN_RESULTS_AVAILABLE. Android 6 needs location permission to return results. Location mode: $locationMode.",
            trailing = {
                if (!allowed) ActionButton("Allow location", { host.request(Manifest.permission.ACCESS_FINE_LOCATION) })
                else ActionButton(if (scanning) "Scanning…" else "Scan", {
                    scanning = true
                    scope.launch { results = scan(context); scanning = false }
                }, enabled = !scanning)
            },
        ) {
            val r = results ?: return@Section
            Text("${r.size} networks · ${r.groupBy { WifiBands.band(it.frequency) }.entries.joinToString { "${it.value.size} on ${it.key}" }}", style = MaterialTheme.typography.bodyMedium)
            r.sortedByDescending { it.level }.forEach { s ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${s.level} dBm", Modifier.width(90.dp), style = Mono)
                    Text("ch ${WifiBands.channel(s.frequency)}", Modifier.width(70.dp), style = Mono)
                    Text(s.SSID.ifEmpty { "(hidden)" }, Modifier.width(320.dp), style = MaterialTheme.typography.bodyMedium)
                    Text(s.capabilities, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(color = InkDim))
                }
            }
        }
    }

    /** Starts a scan and waits up to 10 s for the results broadcast. Callers check location permission first. */
    @SuppressLint("MissingPermission")
    private suspend fun scan(context: Context): List<ScanResult> {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        val done = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { done.complete(Unit) }
        }
        context.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        try {
            @Suppress("DEPRECATION") wm.startScan()
            withTimeoutOrNull(10_000) { done.await() }
        } finally {
            context.unregisterReceiver(receiver)
        }
        return wm.scanResults.orEmpty()
    }

    @Composable
    private fun ProbeSection() {
        val scope = rememberCoroutineScope()
        var lines by remember { mutableStateOf(listOf<Pair<Status, String>>()) }
        var busy by remember { mutableStateOf(false) }
        fun run(block: suspend () -> List<Pair<Status, String>>) {
            busy = true
            scope.launch {
                lines = withContext(Dispatchers.IO) { block() }
                busy = false
            }
        }
        Section("Internet", note = "Latency (DNS, TCP connect), TLS handshakes with the system trust store, download speed.") {
            Actions {
                ActionButton("Latency", { run { latency() } }, enabled = !busy)
                ActionButton("TLS check", { run { NetProbes.tlsHosts.map(NetProbes::tls).map(::tlsLine) } }, enabled = !busy, primary = false)
                ActionButton("Speed test (10 MB)", {
                    run {
                        listOf(runCatching { NetProbes.download() }.fold(
                            { Status.PASS to "${Format.f1(it.mbps)} Mbps · ${Format.bytes(it.bytes)} in ${it.ms} ms from ${it.url}" },
                            { Status.FAIL to "download failed: ${it.message}" },
                        ))
                    }
                }, enabled = !busy, primary = false)
            }
            if (busy) Note("Working…")
            lines.forEach { (s, t) -> Verdict(s, t) }
            Note("TLS on Android 6: TLS 1.2 at most, and the 2016 CA store lacks newer roots. HTTPS failures here will fail in the WebView too.")
        }
    }

    private fun latency(): List<Pair<Status, String>> = buildList {
        for (h in listOf("google.com", "cloudflare.com", "pool.ntp.org")) {
            add(runCatching { NetProbes.dns(h) }.fold({ Status.PASS to "DNS $h → ${it.second} in ${it.first} ms" }, { Status.FAIL to "DNS $h: ${it.message}" }))
        }
        for ((h, p) in listOf("1.1.1.1" to 443, "8.8.8.8" to 53)) {
            add(runCatching { NetProbes.tcpConnect(h, p) }.fold({ Status.PASS to "TCP $h:$p connect $it ms" }, { Status.FAIL to "TCP $h:$p: ${it.message}" }))
        }
    }

    private fun tlsLine(t: NetProbes.Tls): Pair<Status, String> =
        if (t.ok) Status.PASS to "${t.host}: ${t.protocol} · ${t.cipher} · ${t.issuer} · ${t.ms} ms"
        else Status.FAIL to "${t.host}: ${t.error}"

    @Composable
    private fun NsdSection(context: Context) {
        val scope = rememberCoroutineScope()
        var found by remember { mutableStateOf<List<Nsd.Found>?>(null) }
        var busy by remember { mutableStateOf(false) }
        Section(
            "mDNS / DNS-SD",
            note = "NsdManager browses ${Nsd.types.size} service types for 6 s: ${Nsd.types.joinToString(" ")}",
            trailing = {
                ActionButton(if (busy) "Browsing…" else "Browse", {
                    busy = true
                    scope.launch { found = Nsd.browse(context); busy = false }
                }, enabled = !busy)
            },
        ) {
            found?.let { f ->
                if (f.isEmpty()) Note("Nothing answered.")
                f.groupBy { it.type }.forEach { (type, list) -> Text("$type · ${list.joinToString { it.name }}", style = Mono) }
            }
        }
    }

    @Composable
    private fun ServerSection(context: Context) {
        var running by remember { mutableStateOf(server.running) }
        val ip = NetProbes.wifi(context)?.ip
        val hits by rememberPolled(1000) { server.requests.get() }
        Section(
            "Local web server",
            note = "java.net.ServerSocket on port $SERVER_PORT. Open it from a phone on the same Wi-Fi.",
            trailing = {
                ActionButton(if (running) "Stop" else "Start", {
                    if (running) server.stop() else runCatching { server.start() }
                    running = server.running
                    Events.log(lab.id, "local server ${if (running) "started on http://$ip:$SERVER_PORT" else "stopped"}")
                })
            },
        ) {
            if (running) {
                KeyValues(listOf("URL" to "http://$ip:$SERVER_PORT/", "JSON" to "http://$ip:$SERVER_PORT/status", "Requests served" to "${hits ?: 0}"))
            } else {
                Note("Stopped.")
            }
        }
    }

    // NEARBY_WIFI_DEVICES only exists from API 33; on Android 6 Wi-Fi Direct needs nothing beyond CHANGE_WIFI_STATE
    @SuppressLint("MissingPermission")
    @Composable
    private fun P2pSection(host: LabHost) {
        val context = host.context
        var peers by remember { mutableStateOf<List<String>?>(null) }
        var state by remember { mutableStateOf("") }
        val manager = remember { context.getSystemService(WifiP2pManager::class.java) }
        val channel = remember { manager?.initialize(context, Looper.getMainLooper(), null) }
        DisposableEffect(channel) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    manager?.requestPeers(channel) { list -> peers = list.deviceList.map { "${it.deviceName} (${it.deviceAddress})" } }
                }
            }
            context.registerReceiver(receiver, IntentFilter(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION))
            onDispose {
                context.unregisterReceiver(receiver)
                manager?.stopPeerDiscovery(channel, null)
            }
        }
        Section(
            "Wi-Fi Direct",
            note = "WifiP2pManager.discoverPeers: phones/TVs with Wi-Fi Direct open show up (on the phone: Wi-Fi → Wi-Fi Direct).",
            trailing = {
                ActionButton("Discover peers", {
                    state = "discovering…"
                    manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { state = "discovery running (stops after ~2 min)" }
                        override fun onFailure(reason: Int) { state = "discoverPeers failed: reason $reason" }
                    })
                }, enabled = manager != null)
            },
        ) {
            if (state.isNotEmpty()) Text(state, style = Mono)
            peers?.let { p -> Text(if (p.isEmpty()) "No peers yet." else p.joinToString("\n"), style = Mono) }
        }
    }

    private suspend fun selfTest(context: Context): List<Check> = CheckList(lab.id).run {
        val w = NetProbes.wifi(context)
        val l = NetProbes.link(context)
        if (w == null || l == null) {
            fail("Wi-Fi", "not connected")
            return@run items
        }
        pass("Wi-Fi", "${w.ssid} · ${w.rssi} dBm · ${WifiBands.band(w.mhz)} ch ${WifiBands.channel(w.mhz)} · ${w.linkMbps} Mbps · ${w.ip}")
        if (!w.fiveGhz) warn("5 GHz", "not supported by the RK915: 2.4 GHz networks only")
        info("Link", "gateway ${l.gateway}, DNS ${l.dns.joinToString()}, validated=${l.validated}")

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            guard("Scan") {
                val r = scan(context)
                if (r.isEmpty()) warn("Scan", "0 results (location mode ${Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE, 0)})")
                else pass("Scan", "${r.size} networks: ${r.groupBy { WifiBands.band(it.frequency) }.entries.joinToString { "${it.value.size} on ${it.key}" }}")
            }
        } else {
            manual("Scan", "grant location permission to scan (Android 6 rule)")
        }

        withContext(Dispatchers.IO) {
            guard("DNS") {
                val (ms, ip) = NetProbes.dns("google.com")
                pass("DNS", "google.com → $ip in $ms ms")
            }
            guard("TCP") {
                pass("TCP", "1.1.1.1:443 connect in ${NetProbes.tcpConnect("1.1.1.1", 443)} ms")
            }
            val tls = NetProbes.tlsHosts.map(NetProbes::tls)
            tls.forEach { t ->
                if (t.ok) pass("TLS ${t.host}", "${t.protocol}, ${t.issuer}")
                else warn("TLS ${t.host}", "${t.error}")
            }
            guard("Download") {
                val d = NetProbes.download(maxBytes = 5_000_000, maxMs = 10_000)
                pass("Download", "${Format.f1(d.mbps)} Mbps (${Format.bytes(d.bytes)} in ${d.ms} ms) from ${d.url}")
            }
        }

        guard("mDNS") {
            val f = Nsd.browse(context, 5000)
            info("mDNS", if (f.isEmpty()) "no services answered" else "${f.size} services: ${f.joinToString { "${it.name} (${it.type})" }}")
        }

        guard("Local server") {
            withContext(Dispatchers.IO) {
                val s = LocalServer(0) { "{\"ok\":true}" }
                s.start()
                try {
                    val c = URL("http://${w.ip}:${s.boundPort}/status").openConnection() as HttpURLConnection
                    val body = c.inputStream.bufferedReader().readText()
                    if (body.contains("\"ok\":true")) pass("Local server", "served /status on ${w.ip}:${s.boundPort} (ServerSocket)")
                    else fail("Local server", "unexpected body $body")
                } finally {
                    s.stop()
                }
            }
        }
        info("Wi-Fi Direct", "supported=${w.p2p}")
        items
    }
}
