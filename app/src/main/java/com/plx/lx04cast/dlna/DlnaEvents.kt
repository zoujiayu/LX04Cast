package com.plx.lx04cast.dlna

import com.plx.lx04cast.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * GENA eventing: control points SUBSCRIBE to a service with a callback URL, and are sent
 * NOTIFYs with its LastChange whenever the renderer's evented state changes (checked every
 * [CHECK_INTERVAL_MS]). Some apps rely on these instead of polling, e.g. to see a pause made
 * with the TV remote or the end of a video.
 */
class DlnaEvents(private val renderer: DlnaRenderer) {
    private class Subscription(
        val service: UpnpDescriptions.Service,
        val callbacks: List<String>,
        @Volatile var expiresAtMs: Long
    ) {
        @Volatile var seq = 0L
        @Volatile var lastSent: List<Pair<String, String>>? = null
    }

    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    @Volatile private var running = false

    fun start() {
        running = true
        Thread({
            while (running) {
                runCatching { notifyChanges() }.onFailure { Log.w(TAG, "DLNA event check failed", it) }
                try {
                    Thread.sleep(CHECK_INTERVAL_MS)
                } catch (interrupted: InterruptedException) {
                    break
                }
            }
        }, "DLNA-events").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        subscriptions.clear()
    }

    /**
     * A new subscription (with [callbackHeader]) or a renewal (with [sid]). Returns the SID and
     * granted seconds, or null for an unknown renewal (HTTP 412).
     */
    fun subscribe(service: UpnpDescriptions.Service, sid: String?, callbackHeader: String?, timeoutHeader: String?): Pair<String, Int>? {
        val seconds = Gena.timeoutSeconds(timeoutHeader)
        val expiresAt = System.currentTimeMillis() + seconds * 1000L
        if (sid != null) {
            val existing = subscriptions[sid] ?: return null
            existing.expiresAtMs = expiresAt
            return sid to seconds
        }
        val callbacks = Gena.callbacks(callbackHeader)
        if (callbacks.isEmpty()) return null
        val newSid = "uuid:${UUID.randomUUID()}"
        subscriptions[newSid] = Subscription(service, callbacks, expiresAt)
        // The initial event carries the full state; sent right after the SUBSCRIBE response.
        Thread({
            Thread.sleep(INITIAL_EVENT_DELAY_MS)
            subscriptions[newSid]?.let { send(newSid, it, renderer.eventValues(service)) }
        }, "DLNA-event-initial").apply { isDaemon = true }.start()
        return newSid to seconds
    }

    fun unsubscribe(sid: String?) {
        sid?.let { subscriptions.remove(it) }
    }

    private fun notifyChanges() {
        val now = System.currentTimeMillis()
        subscriptions.entries.removeAll { it.value.expiresAtMs < now }
        val values = UpnpDescriptions.SERVICES.associateWith { renderer.eventValues(it) }
        subscriptions.forEach { (sid, subscription) ->
            val current = values[subscription.service].orEmpty()
            val previous = subscription.lastSent ?: return@forEach  // initial event still pending
            if (current != previous) send(sid, subscription, current)
        }
    }

    private fun send(sid: String, subscription: Subscription, values: List<Pair<String, String>>) {
        subscription.lastSent = values
        if (values.isEmpty()) return
        val body = Gena.propertySet(subscription.service, values).toByteArray(Charsets.UTF_8)
        val seq = subscription.seq++
        // Callbacks are tried in order until one accepts, as UPnP asks.
        for (callback in subscription.callbacks) {
            val delivered = runCatching { post(callback, sid, seq, body) }.getOrElse { error ->
                Log.d(TAG, "Event to $callback failed: ${error.message}")
                false
            }
            if (delivered) return
        }
    }

    /** HttpURLConnection can't send the NOTIFY method, so this writes the request itself. */
    private fun post(callback: String, sid: String, seq: Long, body: ByteArray): Boolean {
        val url = URI(callback)
        val host = url.host ?: return false
        val port = if (url.port > 0) url.port else 80
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = CONNECT_TIMEOUT_MS
            val path = url.rawPath.orEmpty().ifEmpty { "/" } + (url.rawQuery?.let { "?$it" } ?: "")
            val head = "NOTIFY $path HTTP/1.1\r\nHOST: $host:$port\r\n" +
                "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\n" +
                "SID: $sid\r\nSEQ: $seq\r\nCONTENT-LENGTH: ${body.size}\r\nCONNECTION: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.ISO_8859_1))
                write(body)
                flush()
            }
            val status = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine().orEmpty()
            return status.split(' ').getOrNull(1)?.startsWith("2") == true
        }
    }

    private companion object {
        const val TAG = "CastBayDlna"
        const val CHECK_INTERVAL_MS = 1000L
        const val INITIAL_EVENT_DELAY_MS = 200L
        const val CONNECT_TIMEOUT_MS = 3000
    }
}

/** GENA message pieces, kept separate so they can be unit tested. */
object Gena {
    const val DEFAULT_TIMEOUT_SEC = 1800

    /** The URLs in a CALLBACK header, e.g. "<http://10.0.0.5:4000/cb><http://...>". */
    fun callbacks(header: String?): List<String> =
        Regex("<([^>]+)>").findAll(header.orEmpty()).map { it.groupValues[1].trim() }
            .filter { it.startsWith("http://") }.toList()

    /** Seconds from a TIMEOUT header ("Second-1800", "Second-infinite"), capped to the default. */
    fun timeoutSeconds(header: String?): Int {
        val value = header?.trim()?.substringAfter("Second-", "")?.toIntOrNull() ?: return DEFAULT_TIMEOUT_SEC
        return value.coerceIn(60, DEFAULT_TIMEOUT_SEC)
    }

    /** A NOTIFY body with [values] as the service's LastChange. */
    fun propertySet(service: UpnpDescriptions.Service, values: List<Pair<String, String>>): String {
        val namespace = if (service == UpnpDescriptions.RENDERING_CONTROL) {
            "urn:schemas-upnp-org:metadata-1-0/RCS/"
        } else {
            "urn:schemas-upnp-org:metadata-1-0/AVT/"
        }
        val channel = if (service == UpnpDescriptions.RENDERING_CONTROL) " channel=\"Master\"" else ""
        val lastChange = buildString {
            append("<Event xmlns=\"$namespace\"><InstanceID val=\"0\">")
            values.forEach { (name, value) -> append("<$name val=\"${Soap.escape(value)}\"$channel/>") }
            append("</InstanceID></Event>")
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\"><e:property>" +
            "<LastChange>${Soap.escape(lastChange)}</LastChange></e:property></e:propertyset>"
    }
}
