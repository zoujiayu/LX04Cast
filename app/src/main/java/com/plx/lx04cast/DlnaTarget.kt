package com.plx.lx04cast

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import com.plx.lx04cast.dlna.DlnaMedia
import com.plx.lx04cast.dlna.DlnaRenderer
import com.plx.lx04cast.dlna.DlnaState
import com.plx.lx04cast.dlna.Soap
import com.plx.lx04cast.util.Log

/**
 * The DLNA renderer's playback target. Audio-only by design: the app advertises only audio
 * MIME types and refuses video with a UPnP fault. Uses the platform MediaPlayer, keeping the
 * app free of Media3/ExoPlayer.
 */
class DlnaTarget(
    context: Context,
    private val coordinator: PlaybackCoordinator
) : DlnaRenderer.Target, PlaybackCoordinator.DlnaTransport {

    private val thread = HandlerThread("LX04DlnaPlayer").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile
    private var state = DlnaState.STOPPED

    @Volatile
    private var positionMs = 0L

    @Volatile
    private var durationMs = 0L

    @Volatile
    private var volume = 100

    @Volatile
    private var muted = false

    private var player: MediaPlayer? = null
    private var prepared = false
    private var playRequested = true
    private var currentUrl: String? = null

    private val progressTicker = object : Runnable {
        override fun run() {
            val mediaPlayer = player
            if (mediaPlayer != null && prepared) {
                positionMs = runCatching { mediaPlayer.currentPosition.toLong() }.getOrDefault(positionMs)
                durationMs = runCatching { mediaPlayer.duration.toLong() }.getOrDefault(durationMs)
                coordinator.onDlnaProgress(positionMs, durationMs)
            }
            if (state == DlnaState.PLAYING) {
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun authorize(address: String, sender: String, opening: Boolean) = Unit

    override fun open(url: String, media: DlnaMedia) {
        if (!media.isAudio) throw Soap.Fault(714, "Only audio is supported")
        currentUrl = url
        state = DlnaState.TRANSITIONING
        positionMs = 0
        durationMs = 0
        playRequested = true
        coordinator.onDlnaOpened(media)
        handler.post { prepare() }
    }

    private fun prepare() {
        releasePlayer()
        state = DlnaState.TRANSITIONING
        prepared = false
        val url = currentUrl ?: return
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        try {
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mediaPlayer.setVolume(outputVolume(), outputVolume())
            mediaPlayer.setOnPreparedListener {
                prepared = true
                durationMs = it.duration.toLong().coerceAtLeast(0)
                if (playRequested) {
                    it.start()
                    state = DlnaState.PLAYING
                } else {
                    state = DlnaState.PAUSED
                }
                coordinator.onDlnaState(state, positionMs / 1000.0, durationMs / 1000.0)
                handler.removeCallbacks(progressTicker)
                handler.postDelayed(progressTicker, 1000)
            }
            mediaPlayer.setOnCompletionListener {
                state = DlnaState.STOPPED
                coordinator.onDlnaState(state, durationMs / 1000.0, durationMs / 1000.0)
                releasePlayer()
            }
            mediaPlayer.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                state = DlnaState.STOPPED
                coordinator.onDlnaState(state, 0.0, durationMs / 1000.0)
                releasePlayer()
                true
            }
            mediaPlayer.setDataSource(url)
            mediaPlayer.prepareAsync()
        } catch (error: Exception) {
            Log.e(TAG, "Could not open DLNA media", error)
            state = DlnaState.STOPPED
            coordinator.onDlnaState(state, 0.0, 0.0)
            releasePlayer()
        }
    }

    override fun play() {
        handler.post {
            playRequested = true
            val mediaPlayer = player
            if (mediaPlayer != null && prepared) {
                runCatching { mediaPlayer.start() }
                state = DlnaState.PLAYING
                coordinator.onDlnaState(state, positionMs / 1000.0, durationMs / 1000.0)
                handler.removeCallbacks(progressTicker)
                handler.postDelayed(progressTicker, 1000)
            } else if (currentUrl != null) {
                prepare()
            }
        }
    }

    override fun pause() {
        handler.post {
            playRequested = false
            handler.removeCallbacks(progressTicker)
            if (prepared) {
                runCatching { player?.pause() }
                state = DlnaState.PAUSED
                coordinator.onDlnaState(state, positionMs / 1000.0, durationMs / 1000.0)
            }
        }
    }

    override fun stop() {
        handler.post {
            playRequested = false
            releasePlayer()
            state = DlnaState.STOPPED
            positionMs = 0
            coordinator.onDlnaState(state, 0.0, durationMs / 1000.0)
        }
    }

    override fun seek(positionSec: Double) {
        seekToMs((positionSec * 1000).toLong())
    }

    override fun seekToMs(ms: Long) {
        handler.post {
            if (prepared) {
                runCatching { player?.seekTo(ms.toInt()) }
                positionMs = ms.coerceAtLeast(0)
                coordinator.onDlnaState(state, positionMs / 1000.0, durationMs / 1000.0)
            }
        }
    }

    override fun setVolume(percent: Int) {
        volume = percent.coerceIn(0, 100)
        handler.post { player?.setVolume(outputVolume(), outputVolume()) }
    }

    override fun setMuted(mute: Boolean) {
        muted = mute
        handler.post { player?.setVolume(outputVolume(), outputVolume()) }
    }

    override fun status(): DlnaRenderer.Status = DlnaRenderer.Status(
        state = state,
        positionSec = positionMs / 1000.0,
        durationSec = durationMs / 1000.0,
        volume = volume,
        muted = muted
    )

    fun shutdown() {
        handler.post { releasePlayer() }
        thread.quitSafely()
    }

    private fun outputVolume(): Float = if (muted) 0f else (volume / 100f).coerceIn(0f, 1f)

    private fun releasePlayer() {
        handler.removeCallbacks(progressTicker)
        player?.let {
            runCatching { it.reset() }
            runCatching { it.release() }
        }
        player = null
        prepared = false
    }

    private companion object {
        const val TAG = "LX04DlnaPlayer"
    }
}
