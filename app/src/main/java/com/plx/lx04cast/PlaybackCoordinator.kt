package com.plx.lx04cast

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import com.plx.lx04cast.dlna.DlnaMedia
import com.plx.lx04cast.dlna.DlnaState
import com.plx.lx04cast.player.DacpClient
import com.plx.lx04cast.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * One source of truth for both receivers. It owns the standard MediaSession used by
 * XiaoAi's voice controls: play/pause/stop are forwarded to DACP for AirPlay (the phone
 * owns the playback) or to the local MediaPlayer for DLNA.
 */
class PlaybackCoordinator(private val context: Context) {

    interface DlnaTransport {
        fun play()
        fun pause()
        fun stop()
        fun seekToMs(ms: Long)
    }

    var airPlayControl: ((DacpClient.Command) -> Unit)? = null
    var airPlaySkip: ((DacpClient.Command) -> Boolean)? = null
    var airPlayLocalPause: ((Boolean) -> Unit)? = null
    /** Arms/disarms sender-resume detection for a local pause (not for a full stop). */
    var airPlaySenderResumeAllowed: ((Boolean) -> Unit)? = null
    var dlnaTransport: DlnaTransport? = null
    var onCastStarted: (() -> Unit)? = null
    var onCastDisconnected: (() -> Unit)? = null
    var onChanged: ((PlayerUiState) -> Unit)? = null

    /** For the MediaStyle notification, so system media controls can find this session. */
    val sessionToken: MediaSession.Token
        get() = session.sessionToken

    private val main = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val session = MediaSession(context, "LX04Cast")

    @Volatile
    private var source = ""

    @Volatile
    private var playing = false

    @Volatile
    private var lastPcmAt = 0L

    /** Pause requested here, even if the sender has no reachable DACP endpoint. */
    @Volatile
    private var userPaused = false

    private var title: String? = null
    private var artist: String? = null
    private var album: String? = null
    private var neteaseId: String? = null
    private var cover: ByteArray? = null
    private var coverUrl: String? = null
    private var status = "等待投送"
    private var positionMs = 0L
    private var durationMs = 0L
    private var positionAtMs = SystemClock.elapsedRealtime()
    private var metadataDirty = true
    private var hasFocus = false
    private var tickerRunning = false

    /**
     * XiaoAi does not send a MediaSession play command to third-party audio apps when it
     * handles "继续播放" itself. It does take audio focus while listening, though. If a
     * transient focus loss happens while this cast is already paused, treat the following
     * focus gain as a resume request; if it happened while playing, it was the pause/voice
     * wake itself, so stay paused when the assistant lets go of focus.
     */
    private var resumeAfterVoiceFocus = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.i(TAG, "Audio focus change=$change playing=$playing source=$source")
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> main.post {
                resumeAfterVoiceFocus = false
                if (playing) commandPause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> main.post {
                if (playing) {
                    resumeAfterVoiceFocus = false
                    commandPause()
                } else if (source.isNotEmpty()) {
                    resumeAfterVoiceFocus = true
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> main.post {
                val resume = resumeAfterVoiceFocus
                resumeAfterVoiceFocus = false
                if (resume && !playing && source.isNotEmpty()) {
                    Log.i(TAG, "Audio focus returned while paused; resuming for XiaoAi")
                    commandPlay()
                }
            }
        }
    }

    /** Close the now-playing UI after a sender has been gone for this long. */
    private val disconnectClose = Runnable {
        Log.i(TAG, "Sender disconnected; closing the player UI")
        onCastDisconnected?.invoke()
    }

    private fun noteAirPlayActivity() {
        if (source != SOURCE_AIRPLAY) return
        main.removeCallbacks(disconnectClose)
        main.postDelayed(disconnectClose, DISCONNECT_CLOSE_MS)
    }

    private fun cancelDisconnectClose() {
        main.removeCallbacks(disconnectClose)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (source != SOURCE_AIRPLAY || !playing) {
                tickerRunning = false
                return
            }
            var changed = false
            if (lastPcmAt > 0 && now - lastPcmAt > STALL_MS) {
                playing = false
                status = "已暂停"
                changed = true
            }
            if (playing) {
                val elapsed = now - positionAtMs
                positionMs = if (durationMs > 0) {
                    (positionMs + elapsed).coerceAtMost(durationMs)
                } else {
                    positionMs + elapsed
                }
                positionAtMs = now
                changed = true
            }
            if (changed) publish()
            if (playing) {
                main.postDelayed(this, 1000)
            } else {
                tickerRunning = false
            }
        }
    }

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        main.postDelayed(ticker, 1000)
    }

    private fun stopTicker() {
        tickerRunning = false
        main.removeCallbacks(ticker)
    }

    init {
        session.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        session.setPlaybackToLocal(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                Log.i(TAG, "MediaSession onPlay")
                commandPlay()
            }

            override fun onPause() {
                Log.i(TAG, "MediaSession onPause")
                commandPause()
            }

            override fun onStop() {
                Log.i(TAG, "MediaSession onStop")
                stopPlayback()
            }

            override fun onSkipToPrevious() {
                Log.i(TAG, "MediaSession onSkipToPrevious handled=${skipPrevious()}")
            }

            override fun onSkipToNext() {
                Log.i(TAG, "MediaSession onSkipToNext handled=${skipNext()}")
            }

            // Some voice assistants resume a stopped cast through these entry points
            // instead of onPlay(). Treat all of them as an explicit play request.
            override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) {
                Log.i(TAG, "MediaSession onPlayFromMediaId: $mediaId")
                commandPlay()
            }

            override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                Log.i(TAG, "MediaSession onPlayFromSearch: $query")
                commandPlay()
            }

            override fun onCommand(command: String, extras: Bundle?, cb: ResultReceiver?) {
                Log.i(TAG, "MediaSession onCommand: $command")
                when {
                    command.equals("play", ignoreCase = true) ||
                        command.equals("resume", ignoreCase = true) -> commandPlay()
                    command.equals("pause", ignoreCase = true) -> commandPause()
                    command.equals("stop", ignoreCase = true) -> stopPlayback()
                    else -> super.onCommand(command, extras, cb)
                }
            }

            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                Log.i(TAG, "MediaSession media button event: ${mediaButtonIntent.action}")
                return super.onMediaButtonEvent(mediaButtonIntent)
            }
        }, main)
        val sessionActivity = PendingIntent.getActivity(
            context, 1,
            Intent(context, PlayerActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        session.setSessionActivity(sessionActivity)
        val mediaButton = PendingIntent.getBroadcast(
            context, 3,
            Intent(context, MediaButtonReceiver::class.java).setAction(Intent.ACTION_MEDIA_BUTTON),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        session.setMediaButtonReceiver(mediaButton)
        session.isActive = false
        StateHub.publish(PlayerUiState())
    }

    // ---- AirPlay callbacks (audio/protocol threads) ----

    fun onAirPlayPcm() {
        lastPcmAt = SystemClock.elapsedRealtime()
        if (source == SOURCE_AIRPLAY && (playing || userPaused)) return
        main.post {
            if (source == SOURCE_DLNA) dlnaTransport?.stop()
            val newSource = source != SOURCE_AIRPLAY
            source = SOURCE_AIRPLAY
            playing = true
            userPaused = false
            status = "AirPlay 正在播放"
            if (newSource) metadataDirty = true
            requestFocus()
            if (newSource) onCastStarted?.invoke()
            noteAirPlayActivity()
            startTicker()
            publish()
        }
    }

    /**
     * The sender started sending audio again after a locally paused stream. This is how
     * pressing play on the iPhone is observed when its DACP endpoint is unreachable.
     */
    fun onAirPlaySenderResumed() {
        lastPcmAt = SystemClock.elapsedRealtime()
        main.post {
            if (source != SOURCE_AIRPLAY) return@post
            userPaused = false
            playing = true
            status = "AirPlay 正在播放"
            requestFocus()
            noteAirPlayActivity()
            startTicker()
            publish()
        }
    }

    /** A new sender connection must not inherit a local pause from an old session. */
    fun onNewAirPlayClient() {
        main.post {
            userPaused = false
            noteAirPlayActivity()
        }
    }

    /** UxPlay's two-second heartbeat is the connection-liveness signal on AirPlay. */
    fun onAirPlayHeartbeat() {
        main.post { noteAirPlayActivity() }
    }

    fun onAirPlayFlush() {
        if (source != SOURCE_AIRPLAY) return
        main.post {
            playing = false
            status = "已暂停"
            noteAirPlayActivity()
            stopTicker()
            publish()
        }
    }

    fun onAirPlayMetadata(trackTitle: String?, trackArtist: String?, trackAlbum: String?) {
        main.post {
            if (source == SOURCE_DLNA) dlnaTransport?.stop()
            val newSource = source != SOURCE_AIRPLAY
            source = SOURCE_AIRPLAY
            title = trackTitle
            artist = trackArtist
            album = trackAlbum
            neteaseId = null
            coverUrl = null
            playing = !userPaused
            status = if (userPaused) "已暂停" else "AirPlay 正在播放"
            metadataDirty = true
            noteAirPlayActivity()
            if (!userPaused) {
                requestFocus()
                startTicker()
            }
            if (newSource) onCastStarted?.invoke()
            publish()
        }
    }

    fun onAirPlayCover(image: ByteArray) {
        if (image.size > MAX_COVER_BYTES) {
            Log.w(TAG, "Ignoring oversized AirPlay cover (${image.size} bytes)")
            return
        }
        main.post {
            if (source != SOURCE_AIRPLAY) return@post
            cover = image
            metadataDirty = true
            noteAirPlayActivity()
            publish()
        }
    }

    fun onAirPlayProgress(positionSec: Double, durationSec: Double) {
        main.post {
            if (source != SOURCE_AIRPLAY) return@post
            positionMs = (positionSec * 1000).toLong().coerceAtLeast(0)
            val newDuration = (durationSec * 1000).toLong().coerceAtLeast(0)
            // Metadata (including album art) must not be rewritten on every progress tick:
            // that churns binders, graphics memory and GC. Only the first duration is new.
            if (durationMs == 0L && newDuration > 0L) metadataDirty = true
            durationMs = newDuration
            positionAtMs = SystemClock.elapsedRealtime()
            noteAirPlayActivity()
            publish()
        }
    }

    // ---- DLNA callbacks (HTTP server threads) ----

    fun onDlnaOpened(media: DlnaMedia) {
        main.post {
            if (source == SOURCE_AIRPLAY) {
                userPaused = true
                airPlayLocalPause?.invoke(true)
                airPlayControl?.invoke(DacpClient.Command.PAUSE)
            }
            cancelDisconnectClose()
            stopTicker()
            source = SOURCE_DLNA
            title = media.title
            artist = media.artist
            album = media.album
            neteaseId = media.neteaseId
            cover = null
            coverUrl = media.albumArtUrl
            playing = false
            status = "DLNA 正在连接"
            positionMs = 0
            durationMs = 0
            positionAtMs = SystemClock.elapsedRealtime()
            metadataDirty = true
            onCastStarted?.invoke()
            fetchCover(media.albumArtUrl)
            publish()
        }
    }

    fun onDlnaState(state: String, positionSec: Double, durationSec: Double) {
        main.post {
            if (source != SOURCE_DLNA) return@post
            playing = state == DlnaState.PLAYING
            status = when (state) {
                DlnaState.PLAYING -> "DLNA 正在播放"
                DlnaState.PAUSED -> "已暂停"
                DlnaState.TRANSITIONING -> "正在缓冲"
                DlnaState.NO_MEDIA -> "等待投送"
                else -> "已停止"
            }
            positionMs = (positionSec * 1000).toLong().coerceAtLeast(0)
            val newDuration = (durationSec * 1000).toLong().coerceAtLeast(0)
            if (durationMs == 0L && newDuration > 0L) metadataDirty = true
            durationMs = newDuration
            positionAtMs = SystemClock.elapsedRealtime()
            if (playing) requestFocus()
            if (state == DlnaState.STOPPED || state == DlnaState.NO_MEDIA) {
                main.removeCallbacks(disconnectClose)
                main.postDelayed(disconnectClose, DISCONNECT_CLOSE_MS)
            } else {
                cancelDisconnectClose()
            }
            publish()
        }
    }

    fun onDlnaProgress(position: Long, duration: Long) {
        main.post {
            if (source != SOURCE_DLNA) return@post
            positionMs = position.coerceAtLeast(0)
            val newDuration = duration.coerceAtLeast(0)
            if (durationMs == 0L && newDuration > 0L) metadataDirty = true
            durationMs = newDuration
            positionAtMs = SystemClock.elapsedRealtime()
            publish()
        }
    }

    fun onDlnaCover(image: ByteArray) {
        main.post {
            if (source != SOURCE_DLNA) return@post
            cover = image
            metadataDirty = true
            publish()
        }
    }

    // ---- Controls (screen buttons and MediaSession callbacks) ----

    fun togglePlayPause() {
        if (playing) commandPause() else commandPlay()
    }

    /** Previous/next can be forwarded to an AirPlay sender over DACP; DLNA has no queue. */
    fun skipPrevious(): Boolean =
        source == SOURCE_AIRPLAY && airPlaySkip?.invoke(DacpClient.Command.PREVIOUS) == true

    fun skipNext(): Boolean =
        source == SOURCE_AIRPLAY && airPlaySkip?.invoke(DacpClient.Command.NEXT) == true

    /** Entry points for the fallback media-button receiver. */
    fun play() = commandPlay()

    fun pause() = commandPause()

    private fun commandPlay() {
        when (source) {
            SOURCE_AIRPLAY -> {
                userPaused = false
                airPlaySenderResumeAllowed?.invoke(false)
                airPlayLocalPause?.invoke(false)
                airPlayControl?.invoke(DacpClient.Command.PLAY)
                playing = true
                status = "AirPlay 正在播放"
                startTicker()
            }
            SOURCE_DLNA -> {
                dlnaTransport?.play()
                playing = true
                status = "DLNA 正在播放"
            }
            else -> return
        }
        requestFocus()
        publish()
    }

    private fun commandPause() {
        when (source) {
            SOURCE_AIRPLAY -> {
                userPaused = true
                airPlayLocalPause?.invoke(true)
                // If the sender honours DACP it stops PCM and a later gap is a phone-side
                // resume; if it ignores DACP, continuous PCM must not undo the pause.
                airPlaySenderResumeAllowed?.invoke(true)
                airPlayControl?.invoke(DacpClient.Command.PAUSE)
            }
            SOURCE_DLNA -> dlnaTransport?.pause()
            else -> return
        }
        playing = false
        status = "已暂停"
        if (source == SOURCE_AIRPLAY) stopTicker()
        publish()
    }

    fun stopPlayback() {
        when (source) {
            SOURCE_AIRPLAY -> {
                userPaused = true
                airPlaySenderResumeAllowed?.invoke(false)
                airPlayLocalPause?.invoke(true)
                airPlayControl?.invoke(DacpClient.Command.PAUSE)
            }
            SOURCE_DLNA -> dlnaTransport?.stop()
            else -> return
        }
        playing = false
        status = "已停止"
        positionMs = 0
        positionAtMs = SystemClock.elapsedRealtime()
        stopTicker()
        abandonFocus()
        publish()
    }

    fun release() {
        stopTicker()
        cancelDisconnectClose()
        main.removeCallbacksAndMessages(null)
        session.isActive = false
        session.release()
        abandonFocus()
        StateHub.publish(PlayerUiState())
    }

    // ---- State, metadata and notification ----

    private fun publish() {
        val trackKey = listOfNotNull(title, artist, album, neteaseId).joinToString("|")
        val next = PlayerUiState(
            title = title,
            artist = artist,
            album = album,
            neteaseId = neteaseId,
            cover = cover,
            source = sourceLabel(),
            playing = playing,
            status = status,
            positionMs = positionMs,
            durationMs = durationMs,
            updatedAtMs = positionAtMs,
            trackKey = trackKey
        )
        StateHub.publish(next)
        updateSession()
        onChanged?.invoke(next)
    }

    private fun sourceLabel(): String = when (source) {
        SOURCE_AIRPLAY -> "AirPlay"
        SOURCE_DLNA -> "DLNA"
        else -> ""
    }

    private fun updateSession() {
        if (source.isEmpty()) {
            session.isActive = false
            return
        }
        session.isActive = true
        if (metadataDirty) {
            session.setMetadata(buildMetadata())
            metadataDirty = false
        }
        session.setPlaybackState(buildPlaybackState())
    }

    private fun buildMetadata(): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title ?: "LX04 投送")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
        artist?.let { builder.putString(MediaMetadata.METADATA_KEY_ARTIST, it) }
        album?.let { builder.putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
        // Album art is deliberately not put into the MediaSession. XiaoAi only needs the
        // title/artist and transport state; keeping bitmaps here made the system hold a
        // second copy for the whole background lifetime.
        return builder.build()
    }

    private fun buildPlaybackState(): PlaybackState {
        val state = when {
            playing -> PlaybackState.STATE_PLAYING
            status == "正在缓冲" || status == "DLNA 正在连接" -> PlaybackState.STATE_BUFFERING
            // A stopped cast is kept resumable: voice assistants may only offer "继续播放"
            // for a PAUSED session. With no source there is nothing to resume.
            source.isEmpty() || status == "等待投送" -> PlaybackState.STATE_STOPPED
            else -> PlaybackState.STATE_PAUSED
        }
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_PLAY_FROM_SEARCH or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
        if (source == SOURCE_AIRPLAY) {
            actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SKIP_TO_NEXT
        }
        return PlaybackState.Builder()
            .setActions(actions)
            .setState(state, positionMs, if (playing) 1f else 0f, positionAtMs)
            .build()
    }

    private fun fetchCover(url: String?) {
        if (url.isNullOrBlank()) return
        val requestUrl = optimizedCoverUrl(url)
        Thread({
            val connection = runCatching {
                (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "LX04Cast/0.1")
                    setRequestProperty("Accept", "image/*")
                }
            }.getOrNull() ?: return@Thread
            try {
                if (connection.responseCode !in 200..299) return@Thread
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                connection.inputStream.use { input ->
                    var total = 0
                    while (total < MAX_COVER_BYTES) {
                        val read = input.read(buffer, 0, minOf(buffer.size, MAX_COVER_BYTES - total))
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        total += read
                    }
                }
                val bytes = output.toByteArray()
                if (bytes.isEmpty()) return@Thread
                Log.i(TAG, "DLNA cover loaded: ${bytes.size} bytes")
                main.post {
                    // A new track can start while this download is in flight.
                    if (source == SOURCE_DLNA && coverUrl == url) {
                        cover = bytes
                        metadataDirty = true
                        publish()
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Could not load the DLNA cover", error)
            } finally {
                connection.disconnect()
            }
        }, "LX04-cover").apply { isDaemon = true }.start()
    }

    /**
     * NetEase's albumArtURI points at a print-resolution JPEG (often 3000×3000, ~1 MB),
     * which is slow to fetch on the LX04's Wi-Fi and unnecessary for a 184 dp cover. Its
     * CDN accepts a resize parameter; request the 320 px thumbnail instead.
     */
    private fun optimizedCoverUrl(url: String): String {
        val host = runCatching { URL(url).host }.getOrNull() ?: return url
        if (!host.endsWith("music.126.net", ignoreCase = true)) return url
        if (Regex("[?&]param=").containsMatchIn(url)) return url
        return url + (if ('?' in url) '&' else '?') + "param=320y320"
    }

    private fun requestFocus() {
        if (hasFocus) return
        val result = audioManager.requestAudioFocus(
            focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
        )
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        audioManager.abandonAudioFocus(focusListener)
        hasFocus = false
    }

    private companion object {
        const val TAG = "LX04Coordinator"
        const val SOURCE_AIRPLAY = "AIRPLAY"
        const val SOURCE_DLNA = "DLNA"
        const val STALL_MS = 2500L
        const val MAX_COVER_BYTES = 2_000_000
        const val DISCONNECT_CLOSE_MS = 30_000L
    }
}
