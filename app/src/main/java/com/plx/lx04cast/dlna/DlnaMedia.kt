package com.plx.lx04cast.dlna

/**
 * What a DLNA sender is casting, from its DIDL-Lite metadata: music apps (NetEase Cloud Music,
 * QQ Music, ...) send the song's title, artist, album and cover, which the music screen shows.
 */
data class DlnaMedia(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** An http(s) URL of the cover image. */
    val albumArtUrl: String? = null,
    /** Sound only (a song), shown on the music screen rather than as video. */
    val isAudio: Boolean = false,
    /** A picture (a photo), which CastBay doesn't show. */
    val isImage: Boolean = false,
    /** The resource's MIME type from its protocolInfo, if the sender gave one. */
    val mimeType: String? = null,
    /** Subtitle files the sender names for the video. */
    val subtitles: List<DlnaSubtitle> = emptyList(),
    /** NetEase Cloud Music's song id (the vendor extension in its DIDL metadata). */
    val neteaseId: String? = null,
    /** The casting app, from [DlnaSender.fromUserAgent]; empty when unknown. */
    val sender: String = ""
) {
    companion object {
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "ape")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")

        /**
         * Parses DIDL-Lite loosely (senders' metadata is often not well-formed) and tells audio,
         * video and pictures apart by the item class, the resource's MIME type, or the URL's
         * extension.
         */
        fun parse(metadata: String?, url: String): DlnaMedia {
            val didl = metadata.orEmpty()
            val itemClass = tag(didl, "upnp:class").orEmpty()
            // The media's own resource: a subtitle file may be listed as a <res> too.
            val mime = Regex("""protocolInfo="[^:"]*:[^:"]*:([^:"]+)""").findAll(didl).map { it.groupValues[1] }
                .firstOrNull { subtitleFormat(it, null) == null }.orEmpty()
            val extension = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
            val isImage = when {
                "imageItem" in itemClass || mime.startsWith("image/") -> true
                "audioItem" in itemClass || "videoItem" in itemClass || mime.isNotEmpty() -> false
                else -> extension in IMAGE_EXTENSIONS
            }
            val isAudio = when {
                "audioItem" in itemClass -> true
                "videoItem" in itemClass || mime.startsWith("video/") -> false
                mime.startsWith("audio/") -> true
                else -> extension in AUDIO_EXTENSIONS
            }
            return DlnaMedia(
                title = tag(didl, "dc:title"),
                artist = tag(didl, "upnp:artist") ?: tag(didl, "dc:creator"),
                album = tag(didl, "upnp:album"),
                albumArtUrl = tag(didl, "upnp:albumArtURI")?.takeIf { it.startsWith("http") },
                isImage = isImage,
                mimeType = mime.ifEmpty { null },
                subtitles = subtitles(didl),
                neteaseId = tag(didl, "netease:musicId")?.filter { it.isDigit() },
                isAudio = isAudio
            )
        }

        /**
         * The subtitle files [didl] names, as senders do it: Samsung's sec:CaptionInfo(Ex), a
         * <res> whose MIME type is a subtitle format, or a <res>'s pv:subtitleFileUri.
         */
        internal fun subtitles(didl: String): List<DlnaSubtitle> {
            val found = mutableListOf<DlnaSubtitle>()
            fun add(url: String, hint: String?) {
                val link = unescape(url).trim()
                if (!link.startsWith("http://") && !link.startsWith("https://")) return
                subtitleFormat(hint, link)?.let { found += DlnaSubtitle(link, it) }
            }
            CAPTION_INFO.findAll(didl).forEach { add(it.groupValues[2], attribute(it.groupValues[1], "sec:type")) }
            RES.findAll(didl).forEach { res ->
                val attributes = res.groupValues[1]
                val mime = attribute(attributes, "protocolInfo")?.split(':')?.getOrNull(2)
                if (mime != null && subtitleFormat(mime, null) != null) add(res.groupValues[2], mime)
                attribute(attributes, "pv:subtitleFileUri")?.let { add(it, attribute(attributes, "pv:subtitleFileType")) }
            }
            return found.distinctBy { it.url }
        }

        /**
         * "srt", "vtt", "ass" or "ttml": from a type or MIME type [hint] ("SRT",
         * "application/x-subrip", "text/vtt"), else from the [url]'s extension; null otherwise.
         */
        internal fun subtitleFormat(hint: String?, url: String?): String? {
            val type = hint?.trim()?.lowercase().orEmpty().substringAfterLast('/').removePrefix("x-")
            SUBTITLE_TYPES[type]?.let { return it }
            val extension = url?.substringBefore('?')?.substringAfterLast('/')?.substringAfterLast('.', "")?.lowercase()
            return SUBTITLE_TYPES[extension]
        }

        private val SUBTITLE_TYPES = mapOf(
            "srt" to "srt", "subrip" to "srt", "vtt" to "vtt", "webvtt" to "vtt",
            "ass" to "ass", "ssa" to "ass", "ttml" to "ttml", "ttml+xml" to "ttml"
        )
        private val CAPTION_INFO = Regex("""<sec:CaptionInfo(?:Ex)?(\s[^>]*)?>(.*?)</sec:CaptionInfo(?:Ex)?>""", RegexOption.DOT_MATCHES_ALL)
        private val RES = Regex("""<res(\s[^>]*)?>(.*?)</res>""", RegexOption.DOT_MATCHES_ALL)

        /** The value of attribute [name] in an element's [attributes] text, entities decoded. */
        private fun attribute(attributes: String, name: String): String? =
            Regex("""(?:^|\s)${Regex.escape(name)}\s*=\s*"([^"]*)"""").find(attributes)?.groupValues?.get(1)?.let(::unescape)

        /** The text of the first <[name]> element, entities decoded, or null if absent or blank. */
        private fun tag(didl: String, name: String): String? {
            val match = Regex("<$name(?:\\s[^>]*)?>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(didl) ?: return null
            return unescape(match.groupValues[1]).trim().takeIf { it.isNotEmpty() }
        }

        private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|lt|gt|quot|apos|amp);")

        /**
         * Decodes XML entities, numeric ones too: NetEase Cloud Music sends its titles as
         * character references ("&#21508;&#33258;..."), escaped once more than the rest of the
         * DIDL, so what is still an entity after one pass is decoded again.
         */
        internal fun unescape(text: String): String {
            var decoded = text
            repeat(2) {
                if ('&' !in decoded) return decoded
                decoded = ENTITY.replace(decoded) { entity ->
                    when (val name = entity.groupValues[1]) {
                        "lt" -> "<"
                        "gt" -> ">"
                        "quot" -> "\""
                        "apos" -> "'"
                        "amp" -> "&"
                        else -> {
                            val code = if (name[1] == 'x' || name[1] == 'X') name.substring(2).toIntOrNull(16)
                            else name.substring(1).toIntOrNull()
                            code?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: entity.value
                        }
                    }
                }
            }
            return decoded
        }
    }
}

/** A subtitle file a DLNA sender names for its video; [format] is "srt", "vtt", "ass" or "ttml". */
data class DlnaSubtitle(val url: String, val format: String)
