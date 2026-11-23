package dev.azula.selfgrant

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executor

private const val TAG = "SelfGrantMdns"

/**
 * Wireless debugging's mDNS services: `_adb-tls-pairing._tcp` while the "pair
 * device with pairing code" dialog is open, `_adb-tls-connect._tcp` while
 * Wireless debugging is on. Emits the port of the service that belongs to THIS
 * phone (other devices on the Wi-Fi advertise the same types), or null when none
 * is up. Runs while collected.
 */
internal fun discoverAdbService(context: Context, type: String): Flow<Int?> = callbackFlow {
    val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    val direct = Executor { it.run() }
    val ports = HashMap<String, Int>() // service name -> port (ours only)
    val callbacks = HashMap<String, NsdManager.ServiceInfoCallback>()
    val lock = Any()

    fun publish() = synchronized(lock) { trySend(ports.values.firstOrNull()) }

    val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) { Log.d(TAG, "discovery started $serviceType") }
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "start failed $errorCode") }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

        override fun onServiceFound(info: NsdServiceInfo) {
            Log.d(TAG, "found ${info.serviceName}")
            if (Build.VERSION.SDK_INT < 34) return
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) { Log.w(TAG, "register cb failed $errorCode") }
                override fun onServiceUpdated(resolved: NsdServiceInfo) {
                    val ours = resolved.hostAddresses.any(::isThisDevice)
                    Log.d(TAG, "resolved ${resolved.serviceName} ${resolved.hostAddresses} port=${resolved.port} ours=$ours")
                    synchronized(lock) { if (ours) ports[resolved.serviceName] = resolved.port else ports.remove(resolved.serviceName) }
                    publish()
                }
                override fun onServiceLost() {
                    synchronized(lock) { ports.remove(info.serviceName) }
                    publish()
                }
                override fun onServiceInfoCallbackUnregistered() {}
            }
            synchronized(lock) { callbacks.put(info.serviceName, cb) }?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
            nsd.registerServiceInfoCallback(info, direct, cb)
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            Log.d(TAG, "lost ${info.serviceName}")
            synchronized(lock) { ports.remove(info.serviceName); callbacks.remove(info.serviceName) }
                ?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
            publish()
        }
    }
    trySend(null)
    nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery)
    awaitClose {
        runCatching { nsd.stopServiceDiscovery(discovery) }
        synchronized(lock) { callbacks.values.toList().also { callbacks.clear() } }
            .forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
    }
}

/** True for loopback and any address configured on one of this device's interfaces. */
internal fun isThisDevice(address: InetAddress): Boolean {
    if (address.isLoopbackAddress || address.isAnyLocalAddress) return true
    return runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .any { it == address }
    }.getOrDefault(false)
}
