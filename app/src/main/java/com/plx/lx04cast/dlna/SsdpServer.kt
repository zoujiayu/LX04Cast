package com.plx.lx04cast.dlna

import com.plx.lx04cast.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

/**
 * Makes the renderer discoverable: answers M-SEARCH requests on the SSDP multicast group and
 * announces it with NOTIFY ssdp:alive (repeated) and ssdp:byebye on stop.
 *
 * An explicit IPv4 DatagramChannel is used first. A plain MulticastSocket can end up as an
 * IPv6-only socket on some Android builds, which silently misses IPv4 SSDP searches.
 */
class SsdpServer(private val uuid: String, private val httpPort: () -> Int) {
    @Volatile private var running = false
    private var listener: MulticastSocket? = null
    private var channel: DatagramChannel? = null
    private var sender: MulticastSocket? = null

    /** Whether M-SEARCH requests can be received (port 1900 could be bound). */
    var answersSearches = false
        private set

    fun start() {
        running = true
        sender = MulticastSocket().apply { timeToLive = 4 }
        answersSearches = try {
            startMulticast()
        } catch (error: Exception) {
            Log.w(TAG, "MulticastSocket SSDP failed, trying IPv4 channel", error)
            try {
                startChannel()
            } catch (channelError: Exception) {
                Log.w(TAG, "SSDP port ${Ssdp.PORT} is unavailable; relying on announcements only", channelError)
                false
            }
        }
        Log.i(TAG, "SSDP started (answers searches: $answersSearches)")
        Thread({ announceLoop() }, "DLNA-ssdp-notify").apply { isDaemon = true }.start()
    }

    private fun startMulticast(): Boolean {
        val socket = MulticastSocket(null).apply {
            reuseAddress = true
            // Explicit IPv4 wildcard; InetSocketAddress(port) was opening an IPv6-only socket
            // on this ROM, which misses all IPv4 SSDP traffic.
            bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), Ssdp.PORT))
        }
        val group = InetSocketAddress(InetAddress.getByName(Ssdp.ADDRESS), Ssdp.PORT)
        interfaces().forEach { (network, _) ->
            runCatching { socket.joinGroup(group, network) }
                .onFailure { Log.w(TAG, "Could not join SSDP group on ${network.name}", it) }
        }
        listener = socket
        Thread({ listen(socket) }, "DLNA-ssdp").apply { isDaemon = true }.start()
        Log.i(TAG, "SSDP listening with IPv4 MulticastSocket")
        return true
    }

    private fun startChannel(): Boolean {
        val socket = DatagramChannel.open(StandardProtocolFamily.INET)
        try {
            socket.setOption(StandardSocketOptions.SO_REUSEADDR, true)
            socket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), Ssdp.PORT))
            val group = InetAddress.getByName(Ssdp.ADDRESS)
            var joined = false
            interfaces().forEach { (network, _) ->
                runCatching { socket.setOption(StandardSocketOptions.IP_MULTICAST_IF, network) }
                if (runCatching { socket.join(group, network) }.isSuccess) joined = true
            }
            if (!joined) {
                runCatching { socket.close() }
                return false
            }
            channel = socket
            Thread({ listenChannel(socket) }, "DLNA-ssdp").apply { isDaemon = true }.start()
            Log.i(TAG, "SSDP listening on IPv4 channel")
            return true
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { announce { target, _ -> Ssdp.byeBye(target) } }
        runCatching { channel?.close() }
        runCatching { listener?.close() }
        runCatching { sender?.close() }
        channel = null
        listener = null
        sender = null
    }

    private fun listenChannel(socket: DatagramChannel) {
        val buffer = ByteBuffer.allocate(4096)
        while (running) {
            val source = try {
                socket.receive(buffer)
            } catch (error: Exception) {
                Log.w(TAG, "SSDP channel receive stopped", error)
                break
            } ?: continue
            buffer.flip()
            val message = Charsets.UTF_8.decode(buffer).toString()
            buffer.clear()
            val from = source as? InetSocketAddress
            if (from != null) handleSearch(message, from)
        }
    }

    private fun listen(socket: MulticastSocket) {
        val buffer = ByteArray(4096)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (error: Exception) {
                break
            }
            handleSearch(
                String(packet.data, 0, packet.length, Charsets.UTF_8),
                InetSocketAddress(packet.address, packet.port)
            )
        }
    }

    private fun handleSearch(message: String, from: InetSocketAddress) {
        val search = Ssdp.parseSearch(message) ?: return
        val targets = Ssdp.matches(search, uuid)
        if (targets.isEmpty()) return
        Log.i(TAG, "M-SEARCH ${search.searchTarget} from ${from.address.hostAddress}")
        reply(from, targets)
    }

    private fun reply(to: InetSocketAddress, targets: List<Pair<String, String>>) {
        val local = localAddressFor(to.address) ?: return
        val location = location(local)
        runCatching {
            DatagramSocket().use { socket ->
                targets.forEach { target ->
                    val bytes = Ssdp.searchResponse(target, location).toByteArray(Charsets.UTF_8)
                    socket.send(DatagramPacket(bytes, bytes.size, to))
                }
            }
        }.onFailure { Log.w(TAG, "Could not answer M-SEARCH from ${to.address.hostAddress}", it) }
    }

    private fun announceLoop() {
        var sent = 0
        while (running) {
            runCatching { announce { target, location -> Ssdp.alive(target, location) } }
                .onFailure { Log.w(TAG, "SSDP announcement failed", it) }
            sent++
            Thread.sleep(if (sent < 3) 1000L else ANNOUNCE_INTERVAL_MS)
        }
    }

    private fun announce(message: (Pair<String, String>, String) -> String) {
        val socket = sender ?: return
        val group = InetSocketAddress(InetAddress.getByName(Ssdp.ADDRESS), Ssdp.PORT)
        interfaces().forEach { (network, address) ->
            socket.networkInterface = network
            Ssdp.targets(uuid).forEach { target ->
                val bytes = message(target, location(address)).toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(bytes, bytes.size, group))
            }
        }
    }

    private fun location(address: InetAddress) =
        "http://${address.hostAddress}:${httpPort()}${UpnpDescriptions.DESCRIPTION_PATH}"

    /** Our address on the network that reaches [remote]. */
    private fun localAddressFor(remote: InetAddress): InetAddress? = runCatching {
        DatagramSocket().use { socket ->
            socket.connect(remote, Ssdp.PORT)
            socket.localAddress.takeUnless { it.isAnyLocalAddress }
        }
    }.getOrNull()

    /** Up, multicast-capable interfaces with an IPv4 address (SSDP here is IPv4 only). */
    private fun interfaces(): List<Pair<NetworkInterface, InetAddress>> =
        runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() }.getOrDefault(false) }
            .mapNotNull { network ->
                network.inetAddresses.toList().firstOrNull { it is Inet4Address }?.let { network to it }
            }

    private companion object {
        const val TAG = "CastBayDlna"
        const val ANNOUNCE_INTERVAL_MS = 60_000L
    }
}
