package com.plx.lx04cast.player

import com.plx.lx04cast.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Raw mDNS fallback for DACP discovery. Android's NsdManager on this ROM often never reports
 * the sender's `_dacp._tcp` service even though the sender supplied a DACP-ID during AirPlay
 * setup; AirReceiverLite solves this with a native mDNSResponder. This is the same protocol
 * implemented in a small, dependency-free way: a QU (unicast-response) PTR query first, then
 * the usual multicast/5353 query. SRV and A/AAAA records are parsed from the response.
 */
internal class MdnsDacpResolver(
    private val dacpId: String,
    private val onResolved: (InetAddress, Int, String) -> Unit
) {
    private val cancelled = AtomicBoolean(false)
    @Volatile
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    private val srvRecords = HashMap<String, SrvRecord>()
    private val addresses = HashMap<String, InetAddress>()
    private val ptrTargets = LinkedHashSet<String>()
    private val extraQueries = LinkedHashSet<DnsQuery>()

    fun start() {
        if (thread != null) return
        thread = Thread(::run, "LX04-dacp-mdns").apply {
            isDaemon = true
            start()
        }
    }

    fun cancel() {
        cancelled.set(true)
        runCatching { socket?.close() }
        thread?.interrupt()
    }

    private fun run() {
        Log.i(TAG, "Raw mDNS DACP lookup for $dacpId")
        if (resolveKnown()) return
        if (probe(unicast = true, durationMs = 3_200)) return
        if (cancelled.get()) return
        if (resolveKnown()) return
        probe(unicast = false, durationMs = 4_000)
        if (!cancelled.get()) Log.i(TAG, "Raw mDNS DACP lookup ended without an endpoint")
    }

    private fun probe(unicast: Boolean, durationMs: Long): Boolean {
        val destination = InetAddress.getByName(MDNS_GROUP)
        val socket = try {
            if (unicast) {
                DatagramSocket().apply { soTimeout = 450 }
            } else {
                multicastSocket().also { it.soTimeout = 450 }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not open ${if (unicast) "unicast" else "multicast"} mDNS socket", error)
            return false
        }
        this.socket = socket
        try {
            val end = System.currentTimeMillis() + durationMs
            var nextSend = 0L
            while (!cancelled.get() && System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= nextSend) {
                    send(socket, destination, query(SERVICE, TYPE_PTR, unicast))
                    for (extra in extraQueries.toList()) {
                        send(socket, destination, query(extra.name, extra.type, unicast))
                    }
                    nextSend = System.currentTimeMillis() + 900L
                }
                val packet = DatagramPacket(ByteArray(MAX_PACKET), MAX_PACKET)
                try {
                    socket.receive(packet)
                    if (consume(packet.data, packet.length)) return true
                } catch (_: java.net.SocketTimeoutException) {
                    // Keep the loop alive until the stage deadline.
                } catch (error: Exception) {
                    if (cancelled.get()) return false
                    Log.w(TAG, "mDNS receive failed", error)
                    break
                }
            }
        } catch (error: Exception) {
            if (!cancelled.get()) Log.w(TAG, "mDNS probe failed", error)
        } finally {
            if (this.socket === socket) this.socket = null
            runCatching { socket.close() }
        }
        return false
    }

    private fun multicastSocket(): MulticastSocket {
        val socket = MulticastSocket(null)
        socket.reuseAddress = true
        // Explicit IPv4 wildcard, as with the SSDP server; an unbound MulticastSocket can end
        // up IPv6-only on this ROM.
        socket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), MDNS_PORT))
        val group = InetSocketAddress(InetAddress.getByName(MDNS_GROUP), MDNS_PORT)
        runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() }.getOrDefault(false) }
            .forEach { network ->
                runCatching { socket.joinGroup(group, network) }
                    .onFailure { Log.w(TAG, "Could not join mDNS group on ${network.name}", it) }
            }
        return socket
    }

    private fun send(socket: DatagramSocket, destination: InetAddress, data: ByteArray) {
        runCatching { socket.send(DatagramPacket(data, data.size, destination, MDNS_PORT)) }
    }

    private fun consume(data: ByteArray, length: Int): Boolean {
        if (length < DNS_HEADER) return false
        return try {
            parse(data, length)
            resolveKnown()
        } catch (error: Exception) {
            Log.w(TAG, "Malformed mDNS packet", error)
            false
        }
    }

    private fun resolveKnown(): Boolean {
        for (instance in ptrTargets) {
            if (!matchesDacp(instance)) continue
            if (srvRecords[normalize(instance)] == null) {
                extraQueries += DnsQuery(normalize(instance), TYPE_SRV)
            }
        }
        for ((instance, srv) in srvRecords.toMap()) {
            if (!matchesDacp(instance)) continue
            val target = normalize(srv.target)
            val address = addresses[target]
            if (address != null) {
                Log.i(TAG, "DACP endpoint found via raw mDNS: $instance -> $address:${srv.port}")
                cancelled.set(true)
                onResolved(address, srv.port, instance)
                return true
            }
            extraQueries += DnsQuery(target, TYPE_A)
            extraQueries += DnsQuery(target, TYPE_AAAA)
        }
        return false
    }

    private fun matchesDacp(instance: String): Boolean {
        val label = instance.substringBefore('.')
        return DacpClient.isServiceFor(label, dacpId)
    }

    private fun parse(data: ByteArray, length: Int) {
        val buffer = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
        val questions = buffer.getShort(4).toInt() and 0xFFFF
        val answers = buffer.getShort(6).toInt() and 0xFFFF
        val authority = buffer.getShort(8).toInt() and 0xFFFF
        val additional = buffer.getShort(10).toInt() and 0xFFFF
        var offset = DNS_HEADER
        repeat(questions.coerceAtMost(32)) {
            val (_, next) = readName(buffer, offset, length)
            offset = next + 4
        }
        val records = (answers + authority + additional).coerceAtMost(512)
        repeat(records) {
            if (offset + 10 > length) return
            val (recordName, afterName) = readName(buffer, offset, length)
            if (afterName + 10 > length) return
            val type = buffer.getShort(afterName).toInt() and 0xFFFF
            val dataLength = buffer.getShort(afterName + 8).toInt() and 0xFFFF
            val rdata = afterName + 10
            if (rdata + dataLength > length) return
            val key = normalize(recordName)
            when (type) {
                TYPE_PTR -> {
                    val (target, _) = readName(buffer, rdata, length)
                    if (target.isNotEmpty()) ptrTargets += target
                }
                TYPE_SRV -> {
                    if (dataLength >= 7) {
                        val port = buffer.getShort(rdata + 4).toInt() and 0xFFFF
                        val (target, _) = readName(buffer, rdata + 6, length)
                        if (target.isNotEmpty()) {
                            srvRecords[key] = SrvRecord(normalize(target), port)
                            Log.i(TAG, "mDNS SRV $recordName -> $target:$port")
                        }
                    }
                }
                TYPE_A -> {
                    if (dataLength == 4) {
                        val address = InetAddress.getByAddress(data.copyOfRange(rdata, rdata + 4))
                        putAddress(key, address)
                    }
                }
                TYPE_AAAA -> {
                    if (dataLength == 16) {
                        val address = InetAddress.getByAddress(data.copyOfRange(rdata, rdata + 16))
                        putAddress(key, address)
                    }
                }
            }
            offset = rdata + dataLength
        }
    }

    private fun putAddress(name: String, address: InetAddress) {
        val existing = addresses[name]
        if (existing == null || address is Inet4Address) {
            addresses[name] = address
            Log.i(TAG, "mDNS address $name -> ${address.hostAddress}")
        }
    }

    /** Returns the name and the offset immediately following it (following compression pointers). */
    private fun readName(buffer: ByteBuffer, start: Int, limit: Int): Pair<String, Int> {
        val labels = ArrayList<String>()
        var cursor = start
        var next = -1
        var jumps = 0
        while (cursor in 0 until limit) {
            val length = buffer.get(cursor).toInt() and 0xFF
            if (length == 0) {
                if (next < 0) next = cursor + 1
                break
            }
            if (length and 0xC0 == 0xC0) {
                if (cursor + 1 >= limit) break
                if (next < 0) next = cursor + 2
                val pointer = ((length and 0x3F) shl 8) or (buffer.get(cursor + 1).toInt() and 0xFF)
                if (pointer >= limit || ++jumps > 20) break
                cursor = pointer
                continue
            }
            if (length > 63 || cursor + 1 + length > limit) break
            labels += String(buffer.array(), cursor + 1, length, Charsets.UTF_8)
            cursor += 1 + length
        }
        return labels.joinToString(".") to (if (next < 0) start else next)
    }

    private fun query(name: String, type: Int, unicast: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeShort(0)
        data.writeShort(0)
        data.writeShort(1)
        data.writeShort(0)
        data.writeShort(0)
        data.writeShort(0)
        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            data.writeByte(bytes.size)
            data.write(bytes)
        }
        data.writeByte(0)
        data.writeShort(type)
        data.writeShort(if (unicast) 0x8001 else 0x0001)
        data.flush()
        return out.toByteArray()
    }

    private fun normalize(name: String): String =
        name.trimEnd('.').lowercase(Locale.ROOT)

    private data class SrvRecord(val target: String, val port: Int)
    private data class DnsQuery(val name: String, val type: Int)

    private companion object {
        const val TAG = "LX04MdnsDacp"
        const val MDNS_GROUP = "224.0.0.251"
        const val MDNS_PORT = 5353
        const val SERVICE = "_dacp._tcp.local"
        const val DNS_HEADER = 12
        const val MAX_PACKET = 9000
        const val TYPE_A = 1
        const val TYPE_PTR = 12
        const val TYPE_AAAA = 28
        const val TYPE_SRV = 33
    }
}
