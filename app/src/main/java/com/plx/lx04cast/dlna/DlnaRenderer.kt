package com.plx.lx04cast.dlna

/**
 * A DLNA media renderer's control logic: turns AVTransport, RenderingControl and
 * ConnectionManager actions into calls on a [Target] (the player) and answers from its state.
 * Thread-safe; actions arrive on the HTTP server's threads.
 */
class DlnaRenderer(private val target: Target) {
    /** What the renderer drives; implemented by the app's player. */
    interface Target {
        /**
         * Before an action that changes what plays (casting, playback, volume) from the device
         * at [address] ([sender] is its app): throws [Soap.Fault] to refuse it, e.g. for a
         * blocked device, or another while one is casting. [opening] for SetAVTransportURI.
         */
        fun authorize(address: String, sender: String, opening: Boolean) {}
        /** A new media URL from SetAVTransportURI, once [authorize]d. */
        fun open(url: String, media: DlnaMedia)
        fun play()
        fun pause()
        fun stop()
        fun seek(positionSec: Double)
        fun setVolume(percent: Int)
        fun setMuted(muted: Boolean)
        fun status(): Status
    }

    /** [state] is one of [DlnaState.TRANSPORT_STATES]; [volume] 0-100. */
    data class Status(
        val state: String,
        val positionSec: Double = 0.0,
        val durationSec: Double = 0.0,
        val volume: Int = 100,
        val muted: Boolean = false
    )

    private var uri = ""
    private var metadata = ""

    /**
     * The out arguments of [action] on [service], in SCPD order; throws [Soap.Fault]. [sender]
     * is the requesting app ([DlnaSender]), kept with the media it casts; [address] the device
     * it runs on, which the [Target] authorizes for actions that change what plays.
     */
    @Synchronized
    fun handle(
        service: UpnpDescriptions.Service,
        action: Soap.Action,
        sender: String = "",
        address: String = ""
    ): List<Pair<String, String>> {
        if (action.name in CHANGING_ACTIONS) {
            val app = sender.ifEmpty { DlnaSender.fromUrl(action.args["CurrentURI"]) }
            target.authorize(address, app, opening = action.name == "SetAVTransportURI")
        }
        return when (service) {
            UpnpDescriptions.AV_TRANSPORT -> avTransport(action, sender)
            UpnpDescriptions.RENDERING_CONTROL -> renderingControl(action)
            else -> connectionManager(action)
        }
    }

    /**
     * The evented state of [service] (GENA LastChange): what subscribers are told whenever it
     * changes. Positions aren't evented; control points poll GetPositionInfo for them.
     */
    @Synchronized
    fun eventValues(service: UpnpDescriptions.Service): List<Pair<String, String>> {
        val status = target.status()
        return when (service) {
            UpnpDescriptions.AV_TRANSPORT -> listOf(
                "TransportState" to transportState(status),
                "TransportStatus" to "OK",
                "CurrentTransportActions" to transportActions(status),
                "AVTransportURI" to uri,
                "CurrentTrackURI" to uri,
                "CurrentMediaDuration" to DlnaState.formatTime(status.durationSec),
                "CurrentTrackDuration" to DlnaState.formatTime(status.durationSec)
            )
            UpnpDescriptions.RENDERING_CONTROL -> listOf(
                "Volume" to status.volume.toString(),
                "Mute" to if (status.muted) "1" else "0"
            )
            else -> emptyList()
        }
    }

    private fun transportState(status: Status) = if (uri.isEmpty()) DlnaState.NO_MEDIA else status.state

    private fun transportActions(status: Status) = when (status.state) {
        DlnaState.PLAYING -> "Pause,Stop,Seek"
        DlnaState.PAUSED -> "Play,Stop,Seek"
        else -> if (uri.isEmpty()) "" else "Play"
    }

    private fun avTransport(action: Soap.Action, sender: String): List<Pair<String, String>> {
        val status = target.status()
        return when (action.name) {
            "SetAVTransportURI" -> {
                val url = action.args["CurrentURI"]?.trim().orEmpty()
                if (url.isEmpty()) throw Soap.Fault(714, "Illegal MIME-type")
                val newMetadata = action.args["CurrentURIMetaData"].orEmpty()
                val app = sender.ifEmpty { DlnaSender.fromUrl(url) }
                val media = DlnaMedia.parse(newMetadata, url).copy(sender = app)
                // Pictures aren't shown (nor offered in GetProtocolInfo): refused, not played as a video that ends at once.
                if (media.isImage) throw Soap.Fault(714, "Illegal MIME-type")
                target.open(url, media)
                uri = url
                metadata = newMetadata
                emptyList()
            }
            "Play" -> {
                if (uri.isEmpty()) throw Soap.Fault(701, "Transition not available")
                target.play()
                emptyList()
            }
            "Pause" -> { target.pause(); emptyList() }
            "Stop" -> { target.stop(); emptyList() }
            "Seek" -> {
                val unit = action.args["Unit"].orEmpty()
                if (unit != "REL_TIME" && unit != "ABS_TIME") throw Soap.Fault(710, "Seek mode not supported")
                val position = DlnaState.parseTime(action.args["Target"]) ?: throw Soap.Fault(711, "Illegal seek target")
                target.seek(position)
                emptyList()
            }
            "Next", "Previous" -> throw Soap.Fault(701, "Transition not available")
            "GetTransportInfo" -> listOf(
                "CurrentTransportState" to transportState(status),
                "CurrentTransportStatus" to "OK",
                "CurrentSpeed" to "1"
            )
            "GetPositionInfo" -> {
                val position = DlnaState.formatTime(status.positionSec)
                listOf(
                    "Track" to (if (uri.isEmpty()) "0" else "1"),
                    "TrackDuration" to DlnaState.formatTime(status.durationSec),
                    "TrackMetaData" to metadata,
                    "TrackURI" to uri,
                    "RelTime" to position,
                    "AbsTime" to position,
                    "RelCount" to "2147483647",
                    "AbsCount" to "2147483647"
                )
            }
            "GetMediaInfo" -> listOf(
                "NrTracks" to (if (uri.isEmpty()) "0" else "1"),
                "MediaDuration" to DlnaState.formatTime(status.durationSec),
                "CurrentURI" to uri,
                "CurrentURIMetaData" to metadata,
                "NextURI" to "",
                "NextURIMetaData" to "",
                "PlayMedium" to "NETWORK",
                "RecordMedium" to "NOT_IMPLEMENTED",
                "WriteStatus" to "NOT_IMPLEMENTED"
            )
            "GetDeviceCapabilities" -> listOf(
                "PlayMedia" to "NETWORK",
                "RecMedia" to "NOT_IMPLEMENTED",
                "RecQualityModes" to "NOT_IMPLEMENTED"
            )
            "GetTransportSettings" -> listOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
            "GetCurrentTransportActions" -> listOf("Actions" to transportActions(status))
            else -> throw Soap.Fault(401, "Invalid Action")
        }
    }

    private fun renderingControl(action: Soap.Action): List<Pair<String, String>> {
        val status = target.status()
        return when (action.name) {
            "GetVolume" -> listOf("CurrentVolume" to status.volume.toString())
            "SetVolume" -> {
                val volume = action.args["DesiredVolume"]?.trim()?.toIntOrNull()?.takeIf { it in 0..100 }
                    ?: throw Soap.Fault(402, "Invalid Args")
                target.setVolume(volume)
                emptyList()
            }
            "GetMute" -> listOf("CurrentMute" to if (status.muted) "1" else "0")
            "SetMute" -> {
                target.setMuted(action.args["DesiredMute"]?.trim().let { it == "1" || it.equals("true", ignoreCase = true) })
                emptyList()
            }
            "ListPresets" -> listOf("CurrentPresetNameList" to "FactoryDefaults")
            "SelectPreset" -> emptyList()
            else -> throw Soap.Fault(401, "Invalid Action")
        }
    }

    private fun connectionManager(action: Soap.Action): List<Pair<String, String>> = when (action.name) {
        "GetProtocolInfo" -> listOf("Source" to "", "Sink" to DlnaState.sinkProtocolInfo(android.os.Build.VERSION.SDK_INT))
        "GetCurrentConnectionIDs" -> listOf("ConnectionIDs" to "0")
        "GetCurrentConnectionInfo" -> listOf(
            "RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "", "PeerConnectionManager" to "",
            "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK"
        )
        else -> throw Soap.Fault(401, "Invalid Action")
    }

    private companion object {
        /** Actions that change what plays; queries (GetPositionInfo, …) are answered to anyone. */
        val CHANGING_ACTIONS = setOf(
            "SetAVTransportURI", "SetNextAVTransportURI", "Play", "Pause", "Stop", "Seek", "Next", "Previous",
            "SetVolume", "SetMute", "SelectPreset"
        )
    }
}
