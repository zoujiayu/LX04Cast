package com.plx.lx04cast.dlna

/** Formats and parsing shared by the renderer's AVTransport and RenderingControl answers. */
object DlnaState {
    const val STOPPED = "STOPPED"
    const val PLAYING = "PLAYING"
    const val PAUSED = "PAUSED_PLAYBACK"
    const val TRANSITIONING = "TRANSITIONING"
    const val NO_MEDIA = "NO_MEDIA_PRESENT"
    val TRANSPORT_STATES = listOf(STOPPED, PLAYING, PAUSED, TRANSITIONING, NO_MEDIA)

    /**
     * Media types we can play on Android [sdkInt]: what ExoPlayer handles, as control points
     * check before casting. No pictures: CastBay doesn't show them, so photo apps shouldn't
     * offer it. FLAC only from Android 8.1, the first with a FLAC decoder.
     */
    fun sinkProtocolInfo(sdkInt: Int): String = (listOf(
        "audio/mpeg", "audio/mp4", "audio/aac", "audio/wav", "audio/x-wav", "audio/ogg"
    ) + if (sdkInt >= FLAC_DECODER_SDK) listOf("audio/flac", "audio/x-flac") else emptyList())
        .joinToString(",") { "http-get:*:$it:*" }

    private const val FLAC_DECODER_SDK = 27

    /** UPnP durations are H+:MM:SS (fractions allowed on input). */
    fun formatTime(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0)
        return "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
    }

    /** Seconds in "H:MM:SS", "HH:MM:SS.mmm" or plain seconds, or null. */
    fun parseTime(value: String?): Double? {
        val parts = value?.trim()?.split(':') ?: return null
        if (parts.isEmpty() || parts.size > 3) return null
        val numbers = parts.map { it.toDoubleOrNull() ?: return null }
        return numbers.fold(0.0) { total, part -> total * 60 + part }
    }

    /** The title in DIDL-Lite [metadata], or null. Parsed loosely: senders' DIDL is often not well-formed. */
    fun title(metadata: String?): String? {
        val text = metadata ?: return null
        val match = Regex("<dc:title>(.*?)</dc:title>", RegexOption.DOT_MATCHES_ALL).find(text) ?: return null
        return match.groupValues[1].replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").trim().takeIf { it.isNotEmpty() }
    }
}
