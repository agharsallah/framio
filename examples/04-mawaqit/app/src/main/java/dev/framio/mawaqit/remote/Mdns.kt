package dev.framio.mawaqit.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/** Announces the panel as "Mawaqit Frame" (_http._tcp): it shows up in network browsers and Home Assistant's discovery. */
class Mdns(context: Context) {

    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun advertise(port: Int) {
        stop()
        val info = NsdServiceInfo().apply {
            serviceName = "Mawaqit Frame"
            serviceType = "_http._tcp"
            setPort(port)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "advertised as ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "mDNS registration failed: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        try {
            nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            listener = l
        } catch (e: RuntimeException) {
            Log.w(TAG, "mDNS unavailable", e)
        }
    }

    fun stop() {
        listener?.let { runCatching { nsd?.unregisterService(it) } }
        listener = null
    }

    private companion object {
        const val TAG = "Mdns"
    }
}
