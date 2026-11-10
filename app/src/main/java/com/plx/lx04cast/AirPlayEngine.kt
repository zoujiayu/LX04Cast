package com.plx.lx04cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.plx.lx04cast.player.DacpClient
import com.plx.lx04cast.player.MusicPlayer
import com.plx.lx04cast.util.Log
import com.weenas.castbay.protocol.AirPlayNative
import com.weenas.castbay.protocol.AudioSink
import java.io.File
import java.util.Locale
import kotlin.math.pow

/** AirPlay audio receive: UxPlay's native protocol core plus a small AudioTrack player. */
class AirPlayEngine(
    private val context: Context,
    private val coordinator: PlaybackCoordinator
) {
    private val main = Handler(Looper.getMainLooper())
    private val player = MusicPlayer { volume }
    private val dacp = DacpClient(context)
    private val advertiser = AirPlayAdvertiser(context)

    @Volatile
    private var running = false

    @Volatile
    private var volume = 1f

    private var lastPcmAtMs = 0L

    /**
     * Set when the pause came from our UI/voice and may be undone by the sender resuming.
     * Stop sets it false, so a stopped cast stays silent even if the sender keeps sending.
     */
    @Volatile
    private var allowSenderResume = false
    private var wasPaused = false

    private var sink: AudioSink? = null
    private var advertiserRetries = 0

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        val deviceName = AppSettings.deviceName
        coordinator.airPlayControl = { command -> dacp.send(command) }
        coordinator.airPlaySkip = { command -> dacp.send(command) }
        coordinator.airPlayLocalPause = { paused ->
            if (paused) player.pauseOutput() else player.resumeOutput()
        }
        coordinator.airPlaySenderResumeAllowed = { allowed -> allowSenderResume = allowed }
        val hardwareAddress = advertiser.hardwareAddress()
        val keyDir = File(context.noBackupFilesDir, "airplay").apply { mkdirs() }
        val keyFile = File(keyDir, "airplay_pairing_key.pem").absolutePath
        val newSink = object : AudioSink {
            override fun onAudioData(data: ByteArray, presentationTimeUs: Long) {
                // Screen-mirroring audio is not part of audio-only receiving.
            }

            override fun onPcmData(data: ByteArray, playAtUs: Long, compressedBytes: Int) {
                val now = SystemClock.elapsedRealtime()
                if (player.outputPaused) {
                    val gap = if (lastPcmAtMs == 0L) Long.MAX_VALUE else now - lastPcmAtMs
                    if (!allowSenderResume || gap < SENDER_RESUME_GAP_MS) {
                        // A sender that ignores DACP can keep streaming while the local output
                        // is paused; remember its liveness so we don't mistake that for a resume.
                        if (!wasPaused) {
                            Log.i(TAG, "PCM while paused (gap=${gap}ms, allowResume=$allowSenderResume)")
                            wasPaused = true
                        }
                        lastPcmAtMs = now
                        return
                    }
                    Log.i(TAG, "Sender resumed after ${gap}ms; restoring local audio")
                    wasPaused = false
                    allowSenderResume = false
                    player.resumeOutput()
                    coordinator.onAirPlaySenderResumed()
                }
                wasPaused = false
                lastPcmAtMs = now
                player.queue(data, playAtUs)
                if (!player.outputPaused) coordinator.onAirPlayPcm()
            }

            override fun onAudioFlush() {
                player.flush()
                coordinator.onAirPlayFlush()
            }

            override fun onVolume(db: Float) {
                val gain = if (db <= -100f) 0f else 10.0.pow(db / 20.0).toFloat().coerceIn(0f, 1f)
                volume = gain
                player.setVolume(gain)
            }

            override fun onMetadata(dmap: ByteArray) {
                val track = DmapMetadata.parse(dmap)
                Log.i(
                    "LX04Meta",
                    "AirPlay DMAP ${dmap.size}B: title=${track?.title} artist=${track?.artist} album=${track?.album}"
                )
                if (track == null) {
                    Log.w(
                        "LX04Meta",
                        "DMAP without metadata tags: " +
                            dmap.take(48).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                    )
                }
                track?.let {
                    coordinator.onAirPlayMetadata(it.title, it.artist, it.album)
                }
            }

            override fun onCoverArt(image: ByteArray) {
                coordinator.onAirPlayCover(image)
            }

            override fun onProgress(positionSec: Double, durationSec: Double) {
                coordinator.onAirPlayProgress(positionSec, durationSec)
            }
        }
        sink = newSink
        AirPlayNative.setAudioSink(newSink)
        AirPlayNative.remoteControlListener = { dacpId, activeRemote -> dacp.setSender(dacpId, activeRemote) }
        AirPlayNative.feedbackListener = { coordinator.onAirPlayHeartbeat() }
        AirPlayNative.clientListener = { _, _, _ ->
            // A new AirPlay session must not inherit a local pause from an old sender.
            allowSenderResume = false
            player.resumeOutput()
            coordinator.onNewAirPlayClient()
            true
        }

        val port = AirPlayNative.start(
            deviceName,
            hardwareAddress,
            keyFile,
            Locale.getDefault().toLanguageTag(),
            640,
            480,
            30,
            "",
            false,
            emptyArray(),
            true,
            false,
            7000
        )
        if (port <= 0) {
            Log.e(TAG, "AirPlay native core failed to start")
            releaseNative()
            return false
        }
        running = true
        main.post { advertise(port) }
        Log.i(TAG, "AirPlay audio listening on $port as $deviceName")
        return true
    }

    private fun advertise(port: Int) {
        if (!running) return
        if (advertiser.start(AppSettings.deviceName, port)) return
        if (advertiserRetries++ < 3) {
            main.postDelayed({ advertise(port) }, 3000)
        } else {
            Log.w(TAG, "Giving up AirPlay mDNS advertising")
        }
    }

    @Synchronized
    fun stop() {
        running = false
        releaseNative()
        main.post { advertiser.stop() }
        dacp.clear()
        player.stop()
        allowSenderResume = false
        coordinator.airPlayControl = null
        coordinator.airPlaySkip = null
        coordinator.airPlayLocalPause = null
        coordinator.airPlaySenderResumeAllowed = null
    }

    private fun releaseNative() {
        runCatching { AirPlayNative.setAudioSink(null) }
        runCatching { AirPlayNative.remoteControlListener = null }
        runCatching { AirPlayNative.feedbackListener = null }
        runCatching { AirPlayNative.clientListener = null }
        runCatching { AirPlayNative.stop() }
        sink = null
    }

    companion object {
        const val TAG = "LX04AirPlay"
        /** A sender that honours pause stops sending; a gap this long means it resumed. */
        const val SENDER_RESUME_GAP_MS = 800L
    }
}
