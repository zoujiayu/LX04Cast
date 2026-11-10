package com.plx.lx04cast

/**
 * Parses the DMAP listing AirPlay audio senders send (title/artist/album).
 * Some senders wrap the "mlit" item differently; a tag scan is used as a fallback.
 */
object DmapMetadata {
    data class Track(val title: String?, val artist: String?, val album: String?)

    fun parse(data: ByteArray): Track? {
        val parsed = parseMlit(data)
        if (parsed != null && (parsed.title != null || parsed.artist != null || parsed.album != null)) {
            return parsed
        }
        // Fallback: scan for the three DMAP tags anywhere in the frame. The tags are
        // 4-byte names followed by a 4-byte big-endian length and a UTF-8 value.
        val title = findValue(data, "minm")
        val artist = findValue(data, "asar")
        val album = findValue(data, "asal")
        return if (title != null || artist != null || album != null) Track(title, artist, album) else null
    }

    private fun parseMlit(data: ByteArray): Track? {
        val (tag, length) = header(data, 0) ?: return null
        if (tag != "mlit" || 8 + length > data.size) return null
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var offset = 8
        val end = 8 + length
        while (offset + 8 <= end) {
            val (itemTag, itemLength) = header(data, offset) ?: return null
            val start = offset + 8
            if (start + itemLength > end) return null
            val value by lazy { clean(String(data, start, itemLength, Charsets.UTF_8)) }
            when (itemTag) {
                "minm" -> title = value
                "asar" -> artist = value
                "asal" -> album = value
            }
            offset = start + itemLength
        }
        return Track(title, artist, album)
    }

    private fun findValue(data: ByteArray, tag: String): String? {
        val needle = tag.toByteArray(Charsets.US_ASCII)
        var offset = 0
        while (offset <= data.size - 8) {
            if (data[offset] == needle[0] && data[offset + 1] == needle[1] &&
                data[offset + 2] == needle[2] && data[offset + 3] == needle[3]
            ) {
                val length = readLength(data, offset + 4)
                if (length != null && length in 0..(data.size - offset - 8)) {
                    clean(String(data, offset + 8, length, Charsets.UTF_8))?.let { return it }
                }
            }
            offset++
        }
        return null
    }

    private fun header(data: ByteArray, offset: Int): Pair<String, Int>? {
        if (offset + 8 > data.size) return null
        val tag = String(data, offset, 4, Charsets.US_ASCII)
        val length = readLength(data, offset + 4) ?: return null
        return tag to length
    }

    private fun readLength(data: ByteArray, offset: Int): Int? {
        if (offset + 4 > data.size) return null
        val length = (data[offset].toInt() and 0xff shl 24) or
            (data[offset + 1].toInt() and 0xff shl 16) or
            (data[offset + 2].toInt() and 0xff shl 8) or
            (data[offset + 3].toInt() and 0xff)
        return if (length < 0) null else length
    }

    private fun clean(value: String): String? =
        value.trim('\u0000', ' ', '\t', '\r', '\n').takeIf { it.isNotBlank() }
}
