package com.plx.lx04cast.lyrics

import android.os.Handler
import android.os.Looper
import com.plx.lx04cast.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(val lines: List<LyricLine>, val synced: Boolean)

/**
 * A stable identity for one lyric lookup. AirPlay metadata from some apps puts a lyric
 * line in [title] and the real "artist/song (extra)" information in [artist]; using the
 * raw title would issue a new lookup for every lyric line. queryFor() extracts a stable
 * song identity when possible.
 */
data class LyricsQuery(
    val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val fallbackTitle: String? = null,
    val fallbackArtist: String? = null,
    /** NetEase Cloud Music's song id, when the DLNA sender supplied one. */
    val neteaseId: String? = null
)

private data class ParsedSong(val title: String, val artist: String)

/**
 * Fetches lyrics for the current track: lrclib.net first (synced when available),
 * api.lyrics.ovh as a plain-text fallback. Failures are logged and never block playback.
 */
object LyricsRepository {
    private const val TAG = "LX04Lyrics"
    private const val LRCLIB_SEARCH = "https://lrclib.net/api/search"
    private const val LYRICS_OVH = "https://api.lyrics.ovh/v1"
    private const val NETEASE_LYRIC = "https://music.163.com/api/song/lyric"
    private const val USER_AGENT = "LX04Cast/0.1 (Android; AirPlay/DLNA receiver)"
    private const val NETEASE_USER_AGENT = "Mozilla/5.0 (Linux; Android 8.1; LX04Cast)"
    private const val CACHE_MAX = 8
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "lyrics").apply { isDaemon = true } }
    /** Empty lyrics are cached too, so a track without lyrics is not looked up again. */
    private val cache = object : LinkedHashMap<String, Lyrics>(CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics>?): Boolean =
            size > CACHE_MAX
    }
    private val cacheLock = Any()
    /** All callbacks waiting for one in-flight lookup. Every waiter must eventually be called. */
    private val pending = HashMap<String, MutableList<(Lyrics?) -> Unit>>()
    private val pendingLock = Any()
    private val latestKey = AtomicReference("")
    private val EMPTY = Lyrics(emptyList(), synced = false)

    fun queryFor(
        title: String?,
        artist: String?,
        album: String?,
        neteaseId: String? = null
    ): LyricsQuery? {
        val rawTitle = title?.trim().orEmpty()
        val rawArtist = artist?.trim().orEmpty()
        val rawAlbum = album?.trim().orEmpty()
        val id = neteaseId?.trim()?.takeIf { it.isNotEmpty() }
        if (id != null) {
            // NetEase's DIDL id is an exact song identity, unlike title/artist metadata
            // that may include a lyric line or an English translation.
            return LyricsQuery(
                key = "netease|$id",
                title = rawTitle,
                artist = rawArtist,
                album = rawAlbum,
                fallbackTitle = rawTitle,
                fallbackArtist = rawArtist,
                neteaseId = id
            )
        }
        if (rawTitle.isEmpty()) return null
        val parsed = parseSongFromArtist(rawArtist)
        if (parsed != null) {
            val key = "parsed|${parsed.artist.lowercase(Locale.ROOT)}|" +
                "${parsed.title.lowercase(Locale.ROOT)}|${rawAlbum.lowercase(Locale.ROOT)}"
            return LyricsQuery(
                key = key,
                title = parsed.title,
                artist = parsed.artist,
                album = rawAlbum,
                fallbackTitle = rawTitle,
                fallbackArtist = rawArtist
            )
        }
        val key = "raw|${rawTitle.lowercase(Locale.ROOT)}|${rawArtist.lowercase(Locale.ROOT)}|" +
            rawAlbum.lowercase(Locale.ROOT)
        return LyricsQuery(key, rawTitle, rawArtist, rawAlbum)
    }

    fun fetch(query: LyricsQuery, callback: (Lyrics?) -> Unit) {
        latestKey.set(query.key)
        val cached = synchronized(cacheLock) { cache[query.key] }
        if (cached != null) {
            callback(cached)
            return
        }
        synchronized(pendingLock) {
            val waiting = pending[query.key]
            if (waiting != null) {
                waiting.add(callback)
                return
            }
            pending[query.key] = mutableListOf(callback)
        }
        Log.i(
            TAG,
            "Fetching lyrics: title='${query.title}' artist='${query.artist}' " +
                "album='${query.album}' neteaseId=${query.neteaseId}"
        )
        executor.execute {
            if (latestKey.get() != query.key) {
                deliver(query.key, null)
                return@execute
            }
            val result = try {
                request(query)
            } catch (error: Exception) {
                Log.w(TAG, "Lyrics lookup failed: ${error.javaClass.simpleName}", error)
                null
            }
            synchronized(cacheLock) { cache[query.key] = result ?: EMPTY }
            deliver(query.key, result)
        }
    }

    private fun deliver(key: String, result: Lyrics?) {
        val listeners = synchronized(pendingLock) { pending.remove(key) } ?: emptyList()
        main.post { listeners.forEach { callback -> callback(result) } }
    }

    /** "Song — Artist" or "Artist/Song (English title)" metadata layouts. */
    private fun parseSongFromArtist(rawArtist: String): ParsedSong? {
        if (rawArtist.isEmpty()) return null
        val dashParts = rawArtist.split(Regex("\\s+[—–]\\s+"), limit = 2)
        if (dashParts.size == 2) {
            val title = cleanCandidate(dashParts[0])
            val artist = dashParts[1].trim()
            if (title.isNotEmpty() && artist.isNotEmpty()) return ParsedSong(title, artist)
        }
        if ('/' in rawArtist) {
            val slash = rawArtist.split('/', limit = 2)
            if (slash.size == 2) {
                val artist = slash[0].trim()
                val title = cleanCandidate(slash[1])
                if (title.isNotEmpty() && artist.isNotEmpty()) return ParsedSong(title, artist)
            }
        }
        return null
    }

    private fun cleanCandidate(text: String): String =
        text.replace(Regex("\\s*[\\(（\\[【].*?[\\)）\\]】]"), "").trim()

    /** Released when the player UI goes away; the next open re-fetches (or uses network). */
    fun trimMemory() {
        synchronized(cacheLock) { cache.clear() }
    }

    private fun request(query: LyricsQuery): Lyrics? {
        // A NetEase song id gives an exact lyric lookup; only fall back to the title/
        // artist services when the song has no synced lyrics (or the request fails).
        query.neteaseId?.let { id ->
            neteaseLyrics(id)?.let {
                Log.i(TAG, "NetEase lyrics: ${it.lines.size} lines")
                return it
            }
            if (latestKey.get() != query.key) return null
        }

        fun tryLookup(title: String, artist: String): Lyrics? {
            searchLrclib(title, artist, query.album)?.let {
                Log.i(TAG, "lrclib lyrics: ${it.lines.size} lines, synced=${it.synced}")
                return it
            }
            if (latestKey.get() != query.key) return null
            val normalized = normalizeTitle(title)
            if (normalized.isNotEmpty() && normalized != title) {
                searchLrclib(normalized, artist, query.album)?.let {
                    Log.i(TAG, "lrclib lyrics (normalized '$normalized'): ${it.lines.size} lines")
                    return it
                }
            }
            if (latestKey.get() != query.key) return null
            return plainFromLyricsOvh(title, artist)?.also {
                Log.i(TAG, "lyrics.ovh plain lyrics: ${it.lines.size} lines")
            }
        }

        tryLookup(query.title, query.artist)?.let { return it }
        if (latestKey.get() != query.key) return null
        val fallbackTitle = query.fallbackTitle
        val fallbackArtist = query.fallbackArtist.orEmpty()
        if (!fallbackTitle.isNullOrBlank() &&
            (fallbackTitle != query.title || fallbackArtist != query.artist)
        ) {
            tryLookup(fallbackTitle, fallbackArtist)?.let { return it }
        }
        Log.w(TAG, "No lyrics found for '${query.title}' / '${query.artist}'")
        return null
    }

    private fun searchLrclib(title: String, artist: String, album: String): Lyrics? {
        val query = buildString {
            append(LRCLIB_SEARCH)
            append("?track_name=").append(encode(title))
            if (artist.isNotEmpty()) append("&artist_name=").append(encode(artist))
            if (album.isNotEmpty()) append("&album_name=").append(encode(album))
        }
        val body = httpGet(query, "application/json") ?: return null
        val array = try {
            JSONArray(body)
        } catch (error: Exception) {
            Log.w(TAG, "lrclib response was not a JSON array", error)
            return null
        }
        var plain: String? = null
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optBoolean("instrumental", false)) continue
            val synced = item.optString("syncedLyrics", "").trim()
            if (synced.isNotEmpty()) {
                val lines = parseLrc(synced)
                if (lines.isNotEmpty()) return Lyrics(lines, synced = true)
            }
            if (plain == null) {
                plain = item.optString("plainLyrics", "").trim().takeIf { it.isNotEmpty() }
            }
        }
        return plain?.let { text ->
            Lyrics(text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
                .map { LyricLine(0L, it) }.toList(), synced = false)
        }
    }

    private fun plainFromLyricsOvh(title: String, artist: String): Lyrics? {
        if (artist.isBlank()) return null
        val query = "$LYRICS_OVH/${encodePath(artist)}/${encodePath(title)}"
        val body = httpGet(query, "application/json") ?: return null
        return try {
            val text = JSONObject(body).optString("lyrics", "").trim()
            if (text.isEmpty()) return null
            val lines = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("[") }
                .map { LyricLine(0L, it) }
                .toList()
            if (lines.isEmpty()) null else Lyrics(lines, synced = false)
        } catch (error: Exception) {
            Log.w(TAG, "lyrics.ovh response was not JSON", error)
            null
        }
    }

    private fun neteaseLyrics(id: String): Lyrics? {
        val url = "$NETEASE_LYRIC?os=pc&id=$id&lv=-1&kv=-1&tv=-1"
        val body = httpGet(
            url,
            "application/json",
            referer = "https://music.163.com/",
            userAgent = NETEASE_USER_AGENT
        ) ?: return null
        return try {
            val root = JSONObject(body)
            if (root.optBoolean("nolyric", false) || root.optBoolean("uncollected", false)) {
                Log.i(TAG, "NetEase has no lyrics for id=$id")
                return null
            }
            val lrc = root.optJSONObject("lrc")?.optString("lyric").orEmpty().trim()
            if (lrc.isEmpty()) return null
            val lines = parseLrc(lrc)
            if (lines.isEmpty()) null else Lyrics(lines, synced = true)
        } catch (error: Exception) {
            Log.w(TAG, "NetEase lyric response was not JSON", error)
            null
        }
    }

    private fun httpGet(
        url: String,
        accept: String,
        referer: String? = null,
        userAgent: String = USER_AGENT
    ): String? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 4000
        connection.readTimeout = 5000
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", accept)
        if (referer != null) connection.setRequestProperty("Referer", referer)
        return try {
            val status = connection.responseCode
            if (status !in 200..299) {
                Log.i(TAG, "HTTP $status from ${connection.url.host}; no lyrics")
                return null
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseLrc(lrc: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        var offsetMs = 0L
        val timeTag = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
        val offsetTag = Regex("\\[offset:(-?\\d+)]")
        for (raw in lrc.lineSequence()) {
            val offsetMatch = offsetTag.find(raw)
            if (offsetMatch != null) {
                offsetMs = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                continue
            }
            val matches = timeTag.findAll(raw).toList()
            if (matches.isEmpty()) continue
            val text = raw.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (match in matches) {
                val minutes = match.groupValues[1].toLongOrNull() ?: continue
                val seconds = match.groupValues[2].toLongOrNull() ?: continue
                val fraction = match.groupValues[3]
                val ms = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100
                    2 -> fraction.toLong() * 10
                    else -> fraction.take(3).toLong()
                }
                out.add(LyricLine(minutes * 60_000 + seconds * 1_000 + ms + offsetMs, text))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** Removes "(Live)", "[...]", "（...）" and " - Remaster" suffixes for a second lookup. */
    private fun normalizeTitle(title: String): String =
        title.replace(Regex("[\\(（\\[【].*?[\\)）\\]】]"), "")
            .replace(Regex("\\s+-\\s+.*$"), "")
            .trim()

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun encodePath(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
