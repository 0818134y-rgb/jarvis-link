package com.zen.jarvis.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.zen.jarvis.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 連携できる端末の一覧。mDNS(NSD) で自動発見 + 手動ピア(Tailscale等)。 */
class DeviceDirectory(private val prefs: Prefs, private val client: LinkClient) {

    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val peers: StateFlow<Map<String, Peer>> = _peers.asStateFlow()

    private var nsd: NsdManager? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registered: NsdManager.RegistrationListener? = null
    private var discovering: NsdManager.DiscoveryListener? = null
    private var ownServiceName: String? = null
    private val serviceToPeer = HashMap<String, String>()

    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun list(): List<Peer> = _peers.value.values.toList()

    // ---------- NSD ----------

    fun startDiscovery(context: Context) {
        if (!prefs.discovery || nsd != null) return
        val app = context.applicationContext
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("jarvis-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
        val manager = app.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = manager

        val info = NsdServiceInfo().apply {
            serviceName = prefs.deviceName
            serviceType = Protocol.SERVICE_TYPE
            port = prefs.port
            setAttribute("name", prefs.deviceName)
            setAttribute("platform", Protocol.PLATFORM)
            setAttribute("v", Protocol.VERSION.toString())
        }
        val reg = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                ownServiceName = serviceInfo.serviceName
                Log.i(TAG, "registered as ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "registration failed $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registered = reg
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg)

        val disc = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery failed $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName == ownServiceName) return
                enqueueResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val key = synchronized(serviceToPeer) { serviceToPeer.remove(serviceInfo.serviceName) } ?: return
                _peers.update { m -> if (m[key]?.source == "mdns") m - key else m }
            }
        }
        discovering = disc
        manager.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, disc)
    }

    /** 古いAndroidは同時に1件しか解決できないので順番に処理する。 */
    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(resolveQueue) {
            resolveQueue.addLast(info)
            if (resolving) return
            resolving = true
        }
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next = synchronized(resolveQueue) {
            val n = resolveQueue.removeFirstOrNull()
            if (n == null) resolving = false
            n
        } ?: return
        val manager = nsd ?: return
        manager.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "resolve failed ${serviceInfo.serviceName}: $errorCode")
                resolveNext()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val attrs = serviceInfo.attributes
                val name = attrs["name"]?.let { String(it, Charsets.UTF_8) } ?: serviceInfo.serviceName
                val platform = attrs["platform"]?.let { String(it, Charsets.UTF_8) } ?: "unknown"
                val host = serviceInfo.host?.hostAddress
                if (host != null && !(name == prefs.deviceName && platform == Protocol.PLATFORM)) {
                    val peer = Peer(name, platform, host, serviceInfo.port, "mdns")
                    synchronized(serviceToPeer) { serviceToPeer[serviceInfo.serviceName] = name.lowercase() }
                    _peers.update { it + (name.lowercase() to peer) }
                    Log.i(TAG, "found $name ($platform) at $host:${serviceInfo.port}")
                }
                resolveNext()
            }
        })
    }

    fun stop() {
        val m = nsd ?: return
        try { registered?.let { m.unregisterService(it) } } catch (_: Exception) {}
        try { discovering?.let { m.stopServiceDiscovery(it) } } catch (_: Exception) {}
        multicastLock?.release()
        nsd = null
        registered = null
        discovering = null
    }

    // ---------- 手動ピア ----------

    suspend fun refreshManual() {
        for (entry in prefs.peerList) {
            val idx = entry.lastIndexOf(':')
            val (host, port) = if (idx > 0 && entry.substring(idx + 1).all { it.isDigit() })
                entry.substring(0, idx) to entry.substring(idx + 1).toInt()
            else entry to prefs.port
            try {
                val ch = client.probe(host, port)
                val dev = ch.optJSONObject("device") ?: continue
                val name = dev.optString("name", host)
                _peers.update { it + (name.lowercase() to Peer(name, dev.optString("platform", "unknown"), host, port, "manual")) }
                Log.i(TAG, "manual peer $name at $host:$port")
            } catch (e: Exception) {
                Log.w(TAG, "manual peer $entry unreachable: ${e.message}")
            }
        }
    }

    // ---------- 名前解決 ----------

    fun resolve(target: String): Peer? {
        val t = target.trim().lowercase()
        val all = list()
        all.firstOrNull { it.name.lowercase() == t }?.let { return it }
        for ((platform, words) in Protocol.PLATFORM_ALIASES) {
            if (words.any { t.contains(it.lowercase()) }) {
                all.filter { it.platform == platform }.maxByOrNull { it.seen }?.let { return it }
            }
        }
        return all.firstOrNull { t.contains(it.name.lowercase()) || it.name.lowercase().contains(t) }
    }

    suspend fun resolveOrRefresh(target: String): Peer? {
        resolve(target)?.let { return it }
        if (prefs.peerList.isNotEmpty()) refreshManual()
        return resolve(target)
    }

    companion object {
        private const val TAG = "JarvisDirectory"
    }
}
