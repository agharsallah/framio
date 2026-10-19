package dev.framio.lab.labs.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Blocking network probes. Call from a background thread. */
object NetProbes {

    data class Link(
        val transport: String, val iface: String?, val addresses: List<String>,
        val gateway: String?, val dns: List<String>, val validated: Boolean, val metered: Boolean,
    )

    fun link(context: Context): Link? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net)
        val lp = cm.getLinkProperties(net)
        val transport = when {
            caps == null -> "?"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            else -> "other"
        }
        return Link(
            transport = transport,
            iface = lp?.interfaceName,
            addresses = lp?.linkAddresses?.map { it.toString() }.orEmpty(),
            gateway = lp?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress,
            dns = lp?.dnsServers?.map { it.hostAddress ?: "?" }.orEmpty(),
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            metered = cm.isActiveNetworkMetered,
        )
    }

    data class Wifi(
        val ssid: String, val bssid: String?, val rssi: Int, val linkMbps: Int, val mhz: Int,
        val ip: String, val mac: String?, val fiveGhz: Boolean, val p2p: Boolean,
    )

    fun wifi(context: Context): Wifi? {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION") val info = wm.connectionInfo ?: return null
        if (info.networkId == -1) return null
        @Suppress("DEPRECATION")
        return Wifi(
            ssid = info.ssid.trim('"'),
            bssid = info.bssid,
            rssi = info.rssi,
            linkMbps = info.linkSpeed,
            mhz = info.frequency,
            ip = WifiBands.ipv4(info.ipAddress),
            mac = info.macAddress, // Android 6 returns 02:00:00:00:00:00 to apps on purpose
            fiveGhz = wm.is5GHzBandSupported,
            p2p = wm.isP2pSupported,
        )
    }

    /** DNS lookup time in ms and the first address. */
    fun dns(host: String): Pair<Long, String> {
        val t0 = System.nanoTime()
        val a = InetAddress.getByName(host)
        return (System.nanoTime() - t0) / 1_000_000 to (a.hostAddress ?: "?")
    }

    /** TCP connect time in ms (a stand-in for ping, which needs root for ICMP). */
    fun tcpConnect(host: String, port: Int, timeoutMs: Int = 3000): Long {
        Socket().use { s ->
            val t0 = System.nanoTime()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            return (System.nanoTime() - t0) / 1_000_000
        }
    }

    data class Tls(val host: String, val ok: Boolean, val protocol: String?, val cipher: String?, val issuer: String?, val ms: Long, val error: String?)

    /**
     * TLS handshake with the system trust store and hostname check.
     * Android 6 tops out at TLS 1.2 and has a 2016 CA list, so newer roots (e.g. ISRG Root X1 for Let's Encrypt) fail.
     */
    fun tls(host: String): Tls {
        val t0 = System.nanoTime()
        return try {
            // Connect plainly first (for the timeout), then layer TLS with the host name so SNI is sent
            val plain = Socket().apply { connect(InetSocketAddress(host, 443), 5000) }
            ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, 443, true) as SSLSocket).use { s ->
                s.soTimeout = 5000
                s.startHandshake()
                val session = s.session
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)) error("hostname mismatch")
                val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
                Tls(host, true, session.protocol, session.cipherSuite, leaf?.issuerX500Principal?.name?.let(::shortDn), (System.nanoTime() - t0) / 1_000_000, null)
            }
        } catch (e: Exception) {
            val cause = generateSequence(e as Throwable) { it.cause }.last()
            val message = (cause.message ?: e.message ?: "").lines().joinToString(" ").replace(Regex("\\s+"), " ")
            Tls(host, false, null, null, null, (System.nanoTime() - t0) / 1_000_000, "${e.javaClass.simpleName}: $message")
        }
    }

    private fun shortDn(dn: String) = dn.split(',').filter { it.startsWith("CN=") || it.startsWith("O=") }.joinToString(", ")

    /** Hosts a dashboard might talk to, plus a Let's Encrypt site to show the CA-store problem. */
    val tlsHosts = listOf(
        "www.google.com",
        "www.cloudflare.com",
        "github.com",
        "api.open-meteo.com",
        "www.home-assistant.io",
        "valid-isrgrootx1.letsencrypt.org",
    )

    data class Download(val url: String, val bytes: Long, val ms: Long) {
        val mbps get() = bytes * 8 / 1000.0 / ms
    }

    /** First URL that works wins. Plain HTTP mirrors keep the old CA store out of the speed test. */
    val downloadUrls = listOf(
        "http://speedtest.tele2.net/10MB.zip",
        "http://ipv4.download.thinkbroadband.com/10MB.zip",
        "https://speed.cloudflare.com/__down?bytes=10000000",
    )

    fun download(maxBytes: Long = 10_000_000, maxMs: Long = 15_000): Download {
        var last: Exception? = null
        for (u in downloadUrls) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 5000
                c.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    val t0 = System.nanoTime()
                    while (total < maxBytes) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if ((System.nanoTime() - t0) / 1_000_000 > maxMs) break
                    }
                    return Download(u, total, ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1))
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("no download URL")
    }
}
