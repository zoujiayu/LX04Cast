package com.plx.lx04cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import com.plx.lx04cast.dlna.DlnaReceiver
import com.plx.lx04cast.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

/** Foreground receiver service: owns the AirPlay engine, the DLNA renderer and the session. */
class CastService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LX04Cast-worker").apply { isDaemon = true }
    }

    private lateinit var coordinator: PlaybackCoordinator
    private lateinit var airPlay: AirPlayEngine
    private lateinit var dlna: DlnaTarget
    private lateinit var dlnaReceiver: DlnaReceiver
    private var airPlayRunning = false
    private var dlnaRunning = false
    private var appliedName = ""
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopping = false
    private var lastNotificationSignature = ""

    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        createChannel()
        // The MediaStyle notification needs the session token, so create the session
        // before the first startForeground() call.
        coordinator = PlaybackCoordinator(this)
        startForegroundCompat(buildNotification(StateHub.state))
        lastNotificationSignature = notificationSignature(StateHub.state)
        acquireLocks()
        airPlay = AirPlayEngine(this, coordinator)
        dlna = DlnaTarget(this, coordinator)
        coordinator.dlnaTransport = dlna
        dlnaReceiver = DlnaReceiver(this)
        coordinator.onCastStarted = { maybeOpenPlayer() }
        coordinator.onCastDisconnected = { PlayerActivity.closeIfOpen() }
        coordinator.onChanged = { state -> updateNotification(state) }
        worker.execute { applySettingsInternal() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> coordinator.togglePlayPause()
            ACTION_PLAY -> coordinator.play()
            ACTION_PAUSE -> coordinator.pause()
            ACTION_PREVIOUS -> if (!coordinator.skipPrevious()) showSkipUnavailable()
            ACTION_NEXT -> if (!coordinator.skipNext()) showSkipUnavailable()
            ACTION_STOP_PLAYBACK -> coordinator.stopPlayback()
            ACTION_UDP_SELF_TEST -> runUdpSelfTest()
            ACTION_APPLY_SETTINGS, ACTION_START, null -> worker.execute { applySettingsInternal() }
        }
        return START_STICKY
    }

    private fun applySettingsInternal() {
        if (stopping) return
        val wantAirPlay = AppSettings.airPlayEnabled
        val wantDlna = AppSettings.dlnaEnabled
        val name = AppSettings.deviceName
        if (name != appliedName) {
            if (airPlayRunning || dlnaRunning) stopEngines()
            appliedName = name
        }
        if (!wantAirPlay && !wantDlna) {
            stopEngines()
            main.post {
                coordinator.stopPlayback()
                updateNotification(StateHub.state)
                stopSelf()
            }
            return
        }
        if (wantAirPlay && !airPlayRunning) {
            airPlayRunning = airPlay.start()
            if (!airPlayRunning) Log.e(TAG, "AirPlay engine did not start")
        } else if (!wantAirPlay && airPlayRunning) {
            airPlay.stop()
            airPlayRunning = false
        }
        if (wantDlna && !dlnaRunning) {
            dlnaReceiver.start(name, dlna)
            dlnaRunning = true
        } else if (!wantDlna && dlnaRunning) {
            dlnaReceiver.stop()
            dlnaRunning = false
        }
        main.post { updateNotification(StateHub.state) }
    }

    private fun stopEngines() {
        if (airPlayRunning) {
            runCatching { airPlay.stop() }
            airPlayRunning = false
        }
        if (dlnaRunning) {
            runCatching { dlnaReceiver.stop() }
            dlnaRunning = false
        }
    }

    /** Diagnostics for DLNA discovery: sends an M-SEARCH from the device and reads the answer. */
    private fun runUdpSelfTest() {
        Thread({
            runCatching {
                val message = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\nMX: 1\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
                val bytes = message.toByteArray(Charsets.UTF_8)
                DatagramSocket(1901).use { socket ->
                    socket.soTimeout = 2000
                    for (host in listOf("127.0.0.1", "192.168.1.19", "239.255.255.250")) {
                        socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(host), 1900))
                        Log.i(TAG, "Self-test M-SEARCH sent to $host")
                    }
                    val buffer = ByteArray(4096)
                    val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                    while (android.os.SystemClock.elapsedRealtime() < deadline) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            socket.receive(packet)
                            val text = String(packet.data, 0, packet.length)
                            Log.i(TAG, "Self-test SSDP response from ${packet.address.hostAddress}:${packet.port}: ${text.take(160)}")
                        } catch (timeout: java.net.SocketTimeoutException) {
                            break
                        }
                    }
                }
            }.onFailure { Log.e(TAG, "Local M-SEARCH test failed", it) }
        }, "LX04-udp-test").start()
    }

    private fun showSkipUnavailable() {
        val message = if (StateHub.state.source == "DLNA") {
            R.string.dlna_skip_unavailable
        } else {
            R.string.skip_unavailable
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun maybeOpenPlayer() {
        if (PlayerActivity.isVisible) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isInteractive) return
        val intent = Intent(this, PlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { startActivity(intent) }
    }

    private fun updateNotification(state: PlayerUiState) {
        // Progress ticks arrive every second while playing. The notification only carries
        // transport/title text, so rebuilding and posting it on each tick is wasted binder
        // and system work; post only when the visible content actually changes.
        val signature = notificationSignature(state)
        if (signature == lastNotificationSignature) return
        lastNotificationSignature = signature
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun notificationSignature(state: PlayerUiState): String = buildString {
        append(AppSettings.deviceName)
        append('|').append(AppSettings.airPlayEnabled)
        append('|').append(AppSettings.dlnaEnabled)
        append('|').append(state.source)
        append('|').append(state.title.orEmpty())
        append('|').append(state.playing)
        append('|').append(state.status)
    }

    private fun buildNotification(state: PlayerUiState): Notification {
        val air = AppSettings.airPlayEnabled
        val dl = AppSettings.dlnaEnabled
        val text = when {
            !air && !dl -> getString(R.string.receiver_off)
            state.source.isNotEmpty() -> buildString {
                append(state.source)
                append(" · ")
                append(state.status)
                state.title?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            }
            else -> getString(R.string.waiting)
        }
        val target = if (state.source.isNotEmpty()) PlayerActivity::class.java else SettingsActivity::class.java
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, target),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val style = Notification.MediaStyle().setMediaSession(coordinator.sessionToken)
        if (state.source.isNotEmpty()) {
            // A standard media notification action also gives voice assistants and the
            // system media panel a concrete play/pause target when they resume a cast.
            val actionIntent = PendingIntent.getService(
                this, 2,
                Intent(this, CastService::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(
                        this,
                        if (state.playing) R.drawable.ic_pause else R.drawable.ic_play
                    ),
                    getString(R.string.play_pause),
                    actionIntent
                ).build()
            )
            style.setShowActionsInCompactView(0)
        }
        builder.setStyle(style)
        return builder
            .setContentTitle(AppSettings.deviceName)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            channel.setSound(null, null)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("LX04Cast-ssdp").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LX04Cast").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseLocks() {
        multicastLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        multicastLock = null
        wakeLock = null
    }

    override fun onDestroy() {
        stopping = true
        runCatching { dlna.shutdown() }
        worker.execute { stopEngines() }
        coordinator.release()
        releaseLocks()
        stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "LX04CastService"
        private const val CHANNEL_ID = "lx04cast"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.plx.lx04cast.START"
        const val ACTION_APPLY_SETTINGS = "com.plx.lx04cast.APPLY_SETTINGS"
        const val ACTION_TOGGLE = "com.plx.lx04cast.TOGGLE"
        const val ACTION_PLAY = "com.plx.lx04cast.PLAY"
        const val ACTION_PAUSE = "com.plx.lx04cast.PAUSE"
        const val ACTION_PREVIOUS = "com.plx.lx04cast.PREVIOUS"
        const val ACTION_NEXT = "com.plx.lx04cast.NEXT"
        const val ACTION_STOP_PLAYBACK = "com.plx.lx04cast.STOP_PLAYBACK"
        const val ACTION_UDP_SELF_TEST = "com.plx.lx04cast.UDP_SELF_TEST"

        fun start(context: Context) = dispatch(context, ACTION_START)
        fun applySettings(context: Context) = dispatch(context, ACTION_APPLY_SETTINGS)
        fun toggle(context: Context) = dispatch(context, ACTION_TOGGLE)
        fun play(context: Context) = dispatch(context, ACTION_PLAY)
        fun pause(context: Context) = dispatch(context, ACTION_PAUSE)
        fun previous(context: Context) = dispatch(context, ACTION_PREVIOUS)
        fun next(context: Context) = dispatch(context, ACTION_NEXT)
        fun stopPlayback(context: Context) = dispatch(context, ACTION_STOP_PLAYBACK)

        private fun dispatch(context: Context, action: String) {
            val intent = Intent(context, CastService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
