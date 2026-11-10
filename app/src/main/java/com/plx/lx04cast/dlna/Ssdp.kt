package com.plx.lx04cast.dlna

/**
 * SSDP (UPnP discovery) messages for a DLNA media renderer: answers to control points'
 * M-SEARCH requests, and the NOTIFY announcements sent when the renderer appears or leaves.
 */
object Ssdp {
    const val ADDRESS = "239.255.255.250"
    const val PORT = 1900
    /** How long control points may cache an announcement; it is repeated well before then. */
    const val MAX_AGE_SEC = 1800
    const val SERVER = "Android/1 UPnP/1.0 CastBay/1"

    const val ROOT_DEVICE = "upnp:rootdevice"
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"

    /** A control point's search; [searchTarget] is its ST header, e.g. "ssdp:all". */
    data class Search(val searchTarget: String, val maxWaitSec: Int)

    /** The M-SEARCH in [message], or null for anything else (NOTIFYs, responses, garbage). */
    fun parseSearch(message: String): Search? {
        val lines = message.split("\r\n", "\n")
        if (!lines.first().trim().startsWith("M-SEARCH", ignoreCase = true)) return null
        val headers = headers(lines.drop(1))
        if (headers["man"]?.trim('"')?.equals("ssdp:discover", ignoreCase = true) != true) return null
        val target = headers["st"] ?: return null
        return Search(target, headers["mx"]?.toIntOrNull()?.coerceIn(0, 5) ?: 1)
    }

    /** The (notification type, unique service name) pairs this renderer announces. */
    fun targets(uuid: String): List<Pair<String, String>> {
        val device = "uuid:$uuid"
        return listOf(
            ROOT_DEVICE to "$device::$ROOT_DEVICE",
            device to device,
            MEDIA_RENDERER to "$device::$MEDIA_RENDERER",
            AV_TRANSPORT to "$device::$AV_TRANSPORT",
            RENDERING_CONTROL to "$device::$RENDERING_CONTROL",
            CONNECTION_MANAGER to "$device::$CONNECTION_MANAGER"
        )
    }

    /** The targets matching a search's ST ("ssdp:all" matches every one). */
    fun matches(search: Search, uuid: String): List<Pair<String, String>> {
        val all = targets(uuid)
        if (search.searchTarget.equals("ssdp:all", ignoreCase = true)) return all
        return all.filter { it.first.equals(search.searchTarget, ignoreCase = true) }
    }

    fun searchResponse(target: Pair<String, String>, location: String): String = message(
        "HTTP/1.1 200 OK",
        "CACHE-CONTROL" to "max-age=$MAX_AGE_SEC",
        "EXT" to "",
        "LOCATION" to location,
        "SERVER" to SERVER,
        "ST" to target.first,
        "USN" to target.second
    )

    fun alive(target: Pair<String, String>, location: String): String = message(
        "NOTIFY * HTTP/1.1",
        "HOST" to "$ADDRESS:$PORT",
        "CACHE-CONTROL" to "max-age=$MAX_AGE_SEC",
        "LOCATION" to location,
        "NT" to target.first,
        "NTS" to "ssdp:alive",
        "SERVER" to SERVER,
        "USN" to target.second
    )

    fun byeBye(target: Pair<String, String>): String = message(
        "NOTIFY * HTTP/1.1",
        "HOST" to "$ADDRESS:$PORT",
        "NT" to target.first,
        "NTS" to "ssdp:byebye",
        "USN" to target.second
    )

    /** Header names lower-cased; later duplicates win. */
    fun headers(lines: List<String>): Map<String, String> = lines.mapNotNull { line ->
        val colon = line.indexOf(':')
        if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
    }.toMap()

    private fun message(startLine: String, vararg headers: Pair<String, String>): String =
        buildString {
            append(startLine).append("\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
}
