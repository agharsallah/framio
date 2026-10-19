package dev.framio.lab.labs.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.delay

/** mDNS / DNS-SD discovery with NsdManager: find printers, Chromecasts, Home Assistant… on the LAN. */
object Nsd {

    /** Service types worth looking for from a home dashboard. */
    val types = listOf(
        "_http._tcp", "_home-assistant._tcp", "_googlecast._tcp", "_airplay._tcp", "_raop._tcp",
        "_spotify-connect._tcp", "_hap._tcp", "_ipp._tcp", "_printer._tcp", "_smb._tcp", "_workstation._tcp", "_mqtt._tcp",
    )

    data class Found(val name: String, val type: String)

    /** Browses all [types] at once for [millis] ms. */
    suspend fun browse(context: Context, millis: Long = 6000): List<Found> {
        val nsd = context.getSystemService(NsdManager::class.java)
        val found = LinkedHashSet<Found>()
        val listeners = types.map { type ->
            object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    synchronized(found) { found += Found(info.serviceName, info.serviceType.trimEnd('.')) }
                }

                override fun onServiceLost(info: NsdServiceInfo) = Unit
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }.also { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, it) }
        }
        try {
            delay(millis)
        } finally {
            listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        }
        return synchronized(found) { found.toList() }
    }
}
