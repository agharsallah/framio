package dev.framio.meadow.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dev.framio.meadow.AppState
import dev.framio.meadow.core.TAG
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The parent dashboard: a small HTTP server on port [port] serving a one-page web app (assets/web)
 * and its JSON API ([Api]), advertised on the LAN as "Little Meadow" (mDNS, _http._tcp).
 * One accept thread, four workers, one request per connection; see [Http] for the limits.
 */
class RemoteServer(private val app: AppState) {
    val port = 8077

    private val auth = Auth({ app.settings.value.pin })
    private val api by lazy { Api(app, auth, ::urls) }

    @Volatile private var running = false
    @Volatile private var socket: ServerSocket? = null
    private var pool: ThreadPoolExecutor? = null
    private var mdns: NsdManager.RegistrationListener? = null

    @Synchronized fun start() {
        if (running) return
        running = true
        val n = AtomicInteger()
        pool = ThreadPoolExecutor(WORKERS, WORKERS, 30, TimeUnit.SECONDS, ArrayBlockingQueue(QUEUE)) { r ->
            Thread(r, "meadow-http-${n.incrementAndGet()}").apply { isDaemon = true }
        }
        thread(name = "meadow-http", isDaemon = true) { acceptLoop() }
    }

    @Synchronized fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        pool?.shutdownNow()
        pool = null
        unregisterMdns()
    }

    /** "http://<ipv4>:8077" for each Wi-Fi (or, failing that, other LAN) address of the frame. */
    fun urls(): List<String> = try {
        val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("p2p") && !it.name.startsWith("dummy") }
        val wifi = ifaces.filter { it.name.startsWith("wlan") || it.name.startsWith("eth") }.ifEmpty { ifaces }
        wifi.flatMap { i ->
            i.inetAddresses.toList().filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .mapNotNull { a -> a.hostAddress?.let { "http://$it:$port" } }
        }.distinct()
    } catch (e: Exception) {
        emptyList()
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port), 50)
                socket = ss
                Log.i(TAG, "dashboard listening on port $port: ${urls()}")
                registerMdns()
                while (running) {
                    val s = ss.accept()
                    try {
                        pool?.execute { serve(s) } ?: s.close()
                    } catch (_: RejectedExecutionException) {
                        s.close() // more than 4 + QUEUE requests at once: the browser retries
                    }
                }
            } catch (e: IOException) {
                socket?.let { try { it.close() } catch (_: IOException) {} }
                socket = null
                if (!running) break
                Log.w(TAG, "dashboard server: ${e.message}; retrying in 5 s")
                try {
                    Thread.sleep(5000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun serve(s: Socket) {
        try {
            s.use {
                s.soTimeout = READ_TIMEOUT_MS
                s.tcpNoDelay = true
                val input = BufferedInputStream(s.getInputStream(), 8 * 1024)
                val out = BufferedOutputStream(s.getOutputStream(), 32 * 1024)
                val req = try {
                    Http.parse(input, Api.MAX_UPLOAD, System.currentTimeMillis() + HEADER_DEADLINE_MS, onContinue = { Http.writeContinue(out) })
                } catch (e: HttpException) {
                    Http.write(out, Response.error(e.status, e.message ?: "Bad request"))
                    finish(s, input)
                    return
                } ?: return
                req.remote = s.inetAddress?.hostAddress.orEmpty()
                Http.write(out, api.handle(req), head = req.method == "HEAD")
                finish(s, input)
            }
        } catch (_: SocketTimeoutException) {
        } catch (e: IOException) {
            Log.d(TAG, "dashboard connection: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "dashboard connection failed", e)
        }
    }

    /**
     * Half-close and read what the client still sends (an upload we refused), briefly: closing a socket
     * with unread data resets the connection and the browser might never see our answer.
     */
    private fun finish(s: Socket, input: BufferedInputStream) {
        try {
            s.shutdownOutput()
            s.soTimeout = 1000
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (total < Api.MAX_UPLOAD) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
            }
        } catch (_: IOException) {
        }
    }

    // ---- mDNS: shows up as "Little Meadow" in browsers and apps that list _http._tcp services

    private fun registerMdns() {
        if (mdns != null) return
        val nsd = app.context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val info = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = "_http._tcp"
            port = this@RemoteServer.port
            setAttribute("path", "/")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "mDNS registered: '${info.serviceName}' _http._tcp port $port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed: $errorCode")
                mdns = null
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        mdns = listener
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS unavailable: ${e.message}")
            mdns = null
        }
    }

    private fun unregisterMdns() {
        val l = mdns ?: return
        mdns = null
        try {
            (app.context.getSystemService(Context.NSD_SERVICE) as? NsdManager)?.unregisterService(l)
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val WORKERS = 4
        const val QUEUE = 48
        const val READ_TIMEOUT_MS = 15_000
        const val HEADER_DEADLINE_MS = 15_000L
        const val SERVICE_NAME = "Little Meadow"
    }
}
