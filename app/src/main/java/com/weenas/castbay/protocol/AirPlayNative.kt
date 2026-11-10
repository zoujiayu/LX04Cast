package com.weenas.castbay.protocol

interface VideoSink {
    /** One Annex B access unit; [isH265] tells H.264 and H.265 mirroring apart. */
    fun onVideoData(data: ByteArray, presentationTimeUs: Long, isH265: Boolean)
    fun onSessionEnd()
}

interface AudioSink {
    /** A compressed AAC-ELD frame (screen mirroring). */
    fun onAudioData(data: ByteArray, presentationTimeUs: Long)
    /**
     * Decoded interleaved S16 stereo PCM at 44.1 kHz (ALAC audio streaming); [compressedBytes]
     * is the size of the ALAC frame it was decoded from. [playAtUs] is when the sender means it
     * to be heard, in [System.currentTimeMillis] time (µs); 0 before the first clock sync.
     */
    fun onPcmData(data: ByteArray, playAtUs: Long, compressedBytes: Int)
    /** The sender flushed (pause, seek, next track): drop audio not yet played. */
    fun onAudioFlush()
    /** The sender's volume slider in AirPlay dB: -30 to 0, or -144 for mute. */
    fun onVolume(db: Float)
    /** Now-playing info for audio streaming, as a DMAP "mlit" listing item. */
    fun onMetadata(dmap: ByteArray)
    /** Cover art (usually JPEG); empty when the sender clears it. */
    fun onCoverArt(image: ByteArray)
    fun onProgress(positionSec: Double, durationSec: Double)
}

/**
 * AirPlay video (HLS) commands from the sender. Called on protocol threads.
 * [url] is a local http://localhost URL served by the protocol core.
 */
interface VideoPlaybackListener {
    fun onPlay(url: String, startPositionSec: Float)
    fun onSeek(positionSec: Float)
    /** 0 pauses, 1 plays. */
    fun onRate(rate: Float)
    fun onStop()
    /**
     * [durationSec, positionSec, rate, state, bufferEmpty (0/1), bufferFull (0/1)], where state is
     * [AirPlayNative.PLAYBACK_NOT_STARTED], [AirPlayNative.PLAYBACK_ACTIVE] or
     * [AirPlayNative.PLAYBACK_FINISHED]. Finished ends the sender's session, so it is only for
     * a video that played to its end or failed.
     */
    fun playbackInfo(): DoubleArray
}

object AirPlayNative {
    init {
        System.loadLibrary("castbay_protocol")
    }

    var connectionListener: (() -> Unit)? = null
    var videoPlaybackListener: VideoPlaybackListener? = null
    /** (dacpId, activeRemote) of a sender that accepts remote-control commands. */
    var remoteControlListener: ((String, String) -> Unit)? = null
    /**
     * (deviceId, name, model) of a sender setting up a session, e.g. ("AA:BB:...", "eason的iPhone",
     * "iPhone15,2"); returns whether to admit it. Called on the protocol's only thread, so it
     * must answer at once.
     */
    var clientListener: ((String, String, String) -> Boolean)? = null
    /** The sender's heartbeat, every two seconds while it is connected. */
    var feedbackListener: (() -> Unit)? = null
    /**
     * A mirroring sender's once-a-second report: values in the order jni_bridge's
     * dispatchSenderReport reads them (-1 when not reported).
     */
    var senderReportListener: ((DoubleArray) -> Unit)? = null
    /** A PIN for a new sender to enter (PIN pairing), to show on the TV. */
    var pinListener: ((String) -> Unit)? = null
    /** (publicKey, deviceId, name) of a sender that has just paired with a PIN; to keep. */
    var pairedListener: ((String, String, String) -> Unit)? = null

    /**
     * Starts the protocol server and returns its port, or 0 on failure. [keyFile] stores the
     * pairing key (created on first use) so senders see the same identity after restarts.
     * [language] (BCP 47, e.g. "zh-CN") picks audio and subtitle tracks in AirPlay video.
     * The display size and [maxFps] are what senders mirror to; a non-empty [password]
     * (at least 4 characters) must be entered by every sender. Otherwise with [usePin], a new
     * sender enters a PIN shown on the TV ([pinListener]) once, and is then remembered: the
     * app keeps the [pairedKeys] of senders that did ([pairedListener]). With [allowTakeover], a new
     * sender replaces a connected one; otherwise it is refused (409). [enableH265] offers
     * H.265 mirroring (only when the TV decodes HEVC in hardware).
     */
    fun start(
        deviceName: String,
        hardwareAddress: ByteArray,
        keyFile: String,
        language: String,
        displayWidth: Int,
        displayHeight: Int,
        maxFps: Int,
        password: String,
        usePin: Boolean,
        pairedKeys: Array<String>,
        allowTakeover: Boolean,
        enableH265: Boolean,
        /** Tried first (0 = any); another free port is used when it is taken. */
        preferredPort: Int = 0
    ): Int {
        require(hardwareAddress.size == 6) { "AirPlay hardware address must contain six bytes" }
        require(password.isEmpty() || password.length >= 4) { "AirPlay passwords need at least 4 characters" }
        return nativeStart(
            deviceName, hardwareAddress, keyFile, language, displayWidth, displayHeight, maxFps, password,
            usePin, pairedKeys, allowTakeover, enableH265, preferredPort
        )
    }

    fun stop() = nativeStop()

    /** Disconnects the current sender; the receiver keeps running for the next one. */
    fun disconnect() = nativeDisconnect()
    fun isRunning(): Boolean = nativeIsRunning()
    fun setVideoSink(sink: VideoSink?) = nativeSetVideoSink(sink)

    /** Also appends native and protocol logs to [path] (null stops); see file_log.h. */
    /** Native and protocol logs go to [path] too; past [maxBytes] it starts afresh, keeping "[path].old". */
    fun setLogFile(path: String?, maxBytes: Long) = nativeSetLogFile(path, maxBytes)

    /** The native and protocol code's latest log lines, oldest first (for problem reports). */
    fun recentLog(): List<String> = String(nativeRecentLog(), Charsets.UTF_8).lines().filter { it.isNotEmpty() }

    /** For tests (tools/sim jnithread): a native thread attaches to Java and exits, as UxPlay's do. */
    fun testThreadExit() = nativeTestThreadExit()
    fun setAudioSink(sink: AudioSink?) = nativeSetAudioSink(sink)

    /** `_airplay._tcp` TXT entries built by the running protocol core (empty when stopped). */
    fun airPlayTxtRecord(): Map<String, String> = parseTxt(nativeAirPlayTxtRecord())

    /** `_raop._tcp` TXT entries built by the running protocol core (empty when stopped). */
    fun raopTxtRecord(): Map<String, String> = parseTxt(nativeRaopTxtRecord())

    private fun parseTxt(entries: Array<String>): Map<String, String> =
        entries.associate { entry ->
            val separator = entry.indexOf('=')
            if (separator < 0) entry to "" else entry.substring(0, separator) to entry.substring(separator + 1)
        }

    @JvmStatic
    fun onConnectionStarted() {
        connectionListener?.invoke()
    }

    @JvmStatic
    fun onVideoPlay(url: String, startPositionSec: Float) {
        videoPlaybackListener?.onPlay(url, startPositionSec)
    }

    @JvmStatic
    fun onVideoScrub(positionSec: Float) {
        videoPlaybackListener?.onSeek(positionSec)
    }

    @JvmStatic
    fun onVideoRate(rate: Float) {
        videoPlaybackListener?.onRate(rate)
    }

    @JvmStatic
    fun onVideoStop() {
        videoPlaybackListener?.onStop()
    }

    @JvmStatic
    fun onRemoteControl(dacpId: String, activeRemote: String) {
        remoteControlListener?.invoke(dacpId, activeRemote)
    }

    @JvmStatic
    fun onClient(deviceId: String, name: ByteArray, model: ByteArray): Boolean =
        clientListener?.invoke(deviceId, String(name, Charsets.UTF_8), String(model, Charsets.UTF_8)) ?: true

    @JvmStatic
    fun onFeedback() {
        feedbackListener?.invoke()
    }

    @JvmStatic
    fun onSenderReport(values: DoubleArray) {
        senderReportListener?.invoke(values)
    }

    @JvmStatic
    fun onPin(pin: String) {
        pinListener?.invoke(pin)
    }

    @JvmStatic
    fun onPaired(publicKey: String, deviceId: String, name: ByteArray) {
        pairedListener?.invoke(publicKey, deviceId, String(name, Charsets.UTF_8))
    }

    @JvmStatic
    fun playbackInfo(): DoubleArray = videoPlaybackListener?.playbackInfo() ?: NOT_PLAYING

    const val PLAYBACK_NOT_STARTED = -1.0
    const val PLAYBACK_FINISHED = 0.0
    const val PLAYBACK_ACTIVE = 1.0

    private val NOT_PLAYING = doubleArrayOf(0.0, 0.0, 0.0, PLAYBACK_NOT_STARTED, 1.0, 0.0)

    @JvmStatic private external fun nativeStart(
        deviceName: String, hardwareAddress: ByteArray, keyFile: String, language: String,
        displayWidth: Int, displayHeight: Int, maxFps: Int, password: String, usePin: Boolean,
        pairedKeys: Array<String>, allowTakeover: Boolean, enableH265: Boolean, preferredPort: Int
    ): Int
    @JvmStatic private external fun nativeStop()
    @JvmStatic private external fun nativeDisconnect()
    @JvmStatic private external fun nativeIsRunning(): Boolean
    @JvmStatic private external fun nativeAirPlayTxtRecord(): Array<String>
    @JvmStatic private external fun nativeRaopTxtRecord(): Array<String>
    @JvmStatic private external fun nativeSetVideoSink(sink: VideoSink?)
    @JvmStatic private external fun nativeSetLogFile(path: String?, maxBytes: Long)
    @JvmStatic private external fun nativeRecentLog(): ByteArray
    @JvmStatic private external fun nativeTestThreadExit()
    @JvmStatic private external fun nativeSetAudioSink(sink: AudioSink?)
}

