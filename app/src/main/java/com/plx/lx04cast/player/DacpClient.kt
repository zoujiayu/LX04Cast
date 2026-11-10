package com.plx.lx04cast.player

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.plx.lx04cast.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Sends remote-control commands (play/pause, next, ...) to the sender that is streaming
 * audio, over DACP: the sender advertises "iTunes_Ctrl_<DACP-ID>" as _dacp._tcp and accepts
 * `GET /ctrl-int/1/<command>` carrying the Active-Remote token it gave us.
 */
class DacpClient(context: Context) {
    enum class Command(val path: String) {
        PLAY("play"),
        PAUSE("pause"),
        PLAY_PAUSE("playpause"),
        NEXT("nextitem"),
        PREVIOUS("previtem")
    }

    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "CastBay-dacp") }
    private val lock = Any()

    private var dacpId: String? = null
    private var activeRemote: String? = null
    private var host: InetAddress? = null
    private var port = 0
    private var discovery: NsdManager.DiscoveryListener? = null
    private var resolving = false
    /** Android NSD is unreliable on this ROM; this is the raw mDNS/mDNSResponder equivalent. */
    private var rawResolver: MdnsDacpResolver? = null
    /** A command pressed before the sender's server was found, sent once it is (if soon). */
    private var pending: (() -> Unit)? = null
    private var pendingAtMs = 0L

    /** Called when a sender identifies itself; starts looking for its DACP server. */
    fun setSender(dacpId: String, activeRemote: String): Unit = synchronized(lock) {
        if (dacpId == this.dacpId && activeRemote == this.activeRemote) return
        stopAllDiscoveryLocked()
        this.dacpId = dacpId
        this.activeRemote = activeRemote
        host = null
        port = 0
        startDiscoveryLocked()
        startRawResolverLocked()
    }

    fun clear() = synchronized(lock) {
        stopAllDiscoveryLocked()
        pending = null
        dacpId = null
        activeRemote = null
        host = null
        port = 0
    }

    /** Whether commands can go to the sender now (its server has been found). */
    fun isReady(): Boolean = synchronized(lock) { host != null && port != 0 && activeRemote != null }

    /**
     * Returns true when the command was sent or queued for the sender's endpoint discovery.
     * A queued command waits for the raw mDNS/NSD fallback; the caller should not treat that
     * as an unsupported sender yet.
     */
    fun send(command: Command): Boolean {
        val endpoint = endpoint(command)
        if (endpoint == null) {
            val hasSender = synchronized(lock) { dacpId != null }
            later { send(command) }
            return hasSender
        }
        executor.execute { request(command, endpoint) }
        return true
    }

    /**
     * The sender's server isn't known yet: on the TCL, discovery sometimes found nothing for a
     * whole session and every button did nothing. Search afresh, and send [action] if the
     * server turns up within [PENDING_MS].
     */
    private fun later(action: () -> Unit): Unit = synchronized(lock) {
        if (dacpId == null) return
        pending = action
        pendingAtMs = android.os.SystemClock.elapsedRealtime()
        Log.i(TAG, "Sender remote control not found yet; searching again (NSD + raw mDNS)")
        stopNsdDiscoveryLocked()
        resolving = false
        startDiscoveryLocked()
        startRawResolverLocked()
    }

    private data class Endpoint(val host: InetAddress, val port: Int, val activeRemote: String)

    private fun endpoint(command: Command): Endpoint? = synchronized(lock) {
        val target = host
        val remote = activeRemote
        if (target == null || remote == null || port == 0) {
            Log.w(TAG, "No sender remote control available for ${command.path}")
            null
        } else {
            Endpoint(target, port, remote)
        }
    }

    private fun request(command: Command, endpoint: Endpoint) {
        try {
            // A raw socket rather than HttpURLConnection: the app only permits cleartext
            // HTTP to localhost, and this request goes to the phone's LAN address.
            Socket().use { socket ->
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), TIMEOUT_MS)
                socket.soTimeout = TIMEOUT_MS
                val request = buildRequest(command, endpoint.activeRemote, hostHeader(endpoint.host, endpoint.port))
                socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                val status = socket.getInputStream().bufferedReader().readLine()
                Log.i(TAG, "${command.path} -> $status")
            }
        } catch (error: Exception) {
            Log.w(TAG, "Remote control ${command.path} failed", error)
        }
    }

    private fun startDiscoveryLocked() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                val id = synchronized(lock) { dacpId } ?: return
                if (isServiceFor(info.serviceName, id)) resolve(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "DACP discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discovery = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun resolve(info: NsdServiceInfo) {
        synchronized(lock) {
            if (resolving || host != null) return
            resolving = true
        }
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                synchronized(lock) {
                    resolving = false
                    val id = dacpId ?: return
                    if (!isServiceFor(resolved.serviceName, id)) return
                    val address = resolved.host ?: return
                    onEndpointFoundLocked(address, resolved.port)
                }
            }

            override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                synchronized(lock) { resolving = false }
                Log.w(TAG, "Could not resolve ${failed.serviceName}: $errorCode")
            }
        })
    }

    private fun startRawResolverLocked() {
        val id = dacpId ?: return
        rawResolver?.cancel()
        rawResolver = MdnsDacpResolver(id) { address, port, _ ->
            synchronized(lock) {
                if (dacpId == id) onEndpointFoundLocked(address, port)
            }
        }.also { it.start() }
    }

    /** NSD or raw mDNS found the endpoint. All three control commands use this one path. */
    private fun onEndpointFoundLocked(endpointHost: InetAddress, endpointPort: Int) {
        if (host != null && port != 0) return
        host = endpointHost
        port = endpointPort
        Log.i(TAG, "Sender remote control at $endpointHost:$endpointPort")
        stopNsdDiscoveryLocked()
        rawResolver?.cancel()
        rawResolver = null
        val action = pending
        pending = null
        if (action != null &&
            android.os.SystemClock.elapsedRealtime() - pendingAtMs <= PENDING_MS
        ) {
            executor.execute(action)
        }
    }

    private fun stopNsdDiscoveryLocked() {
        discovery?.let {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (error: IllegalArgumentException) {
                // Already stopped.
            }
        }
        discovery = null
    }

    private fun stopAllDiscoveryLocked() {
        stopNsdDiscoveryLocked()
        rawResolver?.cancel()
        rawResolver = null
    }

    companion object {
        private const val TAG = "CastBayDacp"
        private const val SERVICE_TYPE = "_dacp._tcp"
        // Allow the raw mDNS stages (QU then multicast) time to find the endpoint before a
        // pending button command expires.
        private const val PENDING_MS = 12_000L
        private const val TIMEOUT_MS = 3000

        fun serviceNameFor(dacpId: String) = "iTunes_Ctrl_$dacpId"

        /**
         * Whether [serviceName] is the DACP server of the sender with [dacpId], compared as
         * numbers: iPhones send the ID without leading zeros ("299EB26EDEDD5A7") but advertise
         * it with them ("iTunes_Ctrl_0299EB26EDEDD5A7"), and a text match never found those.
         */
        fun isServiceFor(serviceName: String, dacpId: String): Boolean {
            val prefix = "iTunes_Ctrl_"
            if (!serviceName.startsWith(prefix, ignoreCase = true)) return false
            val advertised = serviceName.substring(prefix.length).toULongOrNull(16) ?: return false
            return advertised == dacpId.toULongOrNull(16)
        }

        /** HTTP Host value; IPv6 literals are bracketed and lose their zone. */
        fun hostHeader(address: InetAddress, port: Int): String {
            val literal = address.hostAddress.orEmpty().substringBefore('%')
            return if (':' in literal) "[$literal]:$port" else "$literal:$port"
        }

        fun buildRequest(command: Command, activeRemote: String, host: String) =
            "GET /ctrl-int/1/${command.path} HTTP/1.1\r\n" +
                "Host: $host\r\n" +
                "Active-Remote: $activeRemote\r\n" +
                "Connection: close\r\n\r\n"
    }
}
