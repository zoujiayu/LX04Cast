package com.plx.lx04cast.dlna

import com.plx.lx04cast.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The renderer's HTTP side: device and service descriptions, SOAP control and GENA
 * subscriptions. One short-lived connection per request, which is all control points need.
 */
class DlnaHttpServer(
    private val renderer: DlnaRenderer,
    private val events: DlnaEvents,
    private val description: () -> String
) {
    private var server: ServerSocket? = null
    private var workers: ExecutorService? = null

    val port: Int get() = server?.localPort ?: 0

    /** Listens on [preferredPort] if free (0 = any), else on any free port; returns the port. */
    fun start(preferredPort: Int = 0): Int {
        val socket = ServerSocket().apply {
            reuseAddress = true
            try {
                bind(java.net.InetSocketAddress(preferredPort))
            } catch (taken: java.io.IOException) {
                if (preferredPort == 0) throw taken
                Log.i(TAG, "DLNA port $preferredPort is taken, using another one")
                bind(java.net.InetSocketAddress(0))
            }
        }
        server = socket
        val pool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "DLNA-http").apply { isDaemon = true }
        }
        workers = pool
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (error: SocketException) {
                    break
                }
                pool.execute { serve(client) }
            }
        }, "DLNA-http-accept").apply { isDaemon = true }.start()
        return socket.localPort
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        workers?.shutdownNow()
        workers = null
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            socket.soTimeout = READ_TIMEOUT_MS
            val request = runCatching { readRequest(BufferedInputStream(socket.getInputStream())) }.getOrNull() ?: return
            val response = runCatching { respond(request, socket.inetAddress.hostAddress.orEmpty()) }.getOrElse { error ->
                Log.w(TAG, "DLNA request ${request.method} ${request.path} failed", error)
                Response(500, "Internal Server Error")
            }
            runCatching {
                val body = response.body.toByteArray(Charsets.UTF_8)
                val head = buildString {
                    append("HTTP/1.1 ${response.status} ${response.reason}\r\n")
                    append("Server: ${Ssdp.SERVER}\r\n")
                    append("Content-Length: ${body.size}\r\n")
                    if (body.isNotEmpty()) append("Content-Type: ${response.contentType}\r\n")
                    response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                    append("Connection: close\r\n\r\n")
                }
                socket.getOutputStream().apply {
                    write(head.toByteArray(Charsets.ISO_8859_1))
                    if (request.method != "HEAD") write(body)
                    flush()
                }
            }
        }
    }

    private fun respond(request: Request, from: String): Response {
        val path = request.path.substringBefore('?')
        if (path == UpnpDescriptions.DESCRIPTION_PATH && (request.method == "GET" || request.method == "HEAD")) {
            Log.i(TAG, "Description requested by $from (${request.headers["user-agent"].orEmpty()})")
            return Response(200, "OK", description())
        }
        val service = UpnpDescriptions.SERVICES.firstOrNull { path.startsWith("/${it.name}/") }
            ?: return Response(404, "Not Found")
        return when {
            path == service.scpdPath -> Response(200, "OK", UpnpDescriptions.scpd(service))
            path == service.controlPath && request.method == "POST" -> control(service, request, from)
            path == service.eventPath && request.method == "SUBSCRIBE" -> subscribe(service, request, from)
            path == service.eventPath && request.method == "UNSUBSCRIBE" -> {
                events.unsubscribe(request.headers["sid"])
                Response(200, "OK")
            }
            else -> Response(405, "Method Not Allowed")
        }
    }

    private fun control(service: UpnpDescriptions.Service, request: Request, from: String): Response {
        val action = Soap.parse(request.body, request.headers["soapaction"])
            ?: return Response(400, "Bad Request")
        // Position polling arrives every second; keep it out of the log.
        val userAgent = request.headers["user-agent"].orEmpty()
        if (action.name !in QUIET_ACTIONS) {
            Log.i(TAG, "$from ($userAgent) → ${service.name}.${action.name} ${action.args.filterKeys { it != "InstanceID" }.mapValues { (key, value) -> if (key in PERSONAL_ARGS) Log.personal(value) else value }}")
        }
        return try {
            val out = renderer.handle(service, action, DlnaSender.fromUserAgent(userAgent), from)
            Response(200, "OK", Soap.response(service.type, action.name, out))
        } catch (fault: Soap.Fault) {
            Log.i(TAG, "${service.name}.${action.name} refused: ${fault.code} ${fault.description}")
            Response(500, "Internal Server Error", Soap.fault(fault))
        }
    }

    private fun subscribe(service: UpnpDescriptions.Service, request: Request, from: String): Response {
        val renewal = request.headers["sid"]
        val (sid, seconds) = events.subscribe(service, renewal, request.headers["callback"], request.headers["timeout"])
            ?: return Response(412, "Precondition Failed")
        if (renewal == null) Log.i(TAG, "$from subscribed to ${service.name} events (${request.headers["callback"].orEmpty()})")
        return Response(200, "OK", headers = listOf("SID" to sid, "TIMEOUT" to "Second-$seconds"))
    }

    private data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private data class Response(
        val status: Int,
        val reason: String,
        val body: String = "",
        val headers: List<Pair<String, String>> = emptyList(),
        val contentType: String = "text/xml; charset=\"utf-8\""
    )

    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val headerLines = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }.toList()
        val headers = Ssdp.headers(headerLines)
        val body = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
            else -> readBytes(input, headers["content-length"]?.trim()?.toIntOrNull() ?: 0)
        }
        return Request(parts[0].uppercase(), parts[1], headers, String(body, Charsets.UTF_8))
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: break
            if (size == 0) break
            out.write(readBytes(input, size))
            readLine(input)
        }
        return out.toByteArray()
    }

    private fun readBytes(input: InputStream, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val bytes = ByteArray(minOf(length, MAX_BODY_BYTES))
        var read = 0
        while (read < bytes.size) {
            val n = input.read(bytes, read, bytes.size - read)
            if (n < 0) break
            read += n
        }
        return bytes.copyOf(read)
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.size() == 0) null else line.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) return line.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            if (line.size() < MAX_LINE_BYTES) line.write(b)
        }
    }

    private companion object {
        const val TAG = "CastBayDlna"
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_LINE_BYTES = 8 * 1024
        const val MAX_BODY_BYTES = 256 * 1024
        val QUIET_ACTIONS = setOf("GetPositionInfo", "GetTransportInfo", "GetVolume", "GetMute", "GetMediaInfo")
        // What was cast (its link, and title and artist in the metadata): left out of problem reports.
        val PERSONAL_ARGS = setOf("CurrentURI", "CurrentURIMetaData", "NextURI", "NextURIMetaData")
    }
}
