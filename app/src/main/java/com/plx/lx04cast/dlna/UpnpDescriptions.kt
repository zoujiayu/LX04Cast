package com.plx.lx04cast.dlna

/**
 * The XML documents a control point reads after discovery: the device description (name,
 * services, their URLs) and each service's SCPD (actions and state variables).
 */
object UpnpDescriptions {
    const val DESCRIPTION_PATH = "/description.xml"

    /** Per service: SCPD, control and event URLs. */
    data class Service(val type: String, val id: String, val name: String) {
        val scpdPath get() = "/$name/scpd.xml"
        val controlPath get() = "/$name/control"
        val eventPath get() = "/$name/event"
    }

    val AV_TRANSPORT = Service(Ssdp.AV_TRANSPORT, "urn:upnp-org:serviceId:AVTransport", "AVTransport")
    val RENDERING_CONTROL = Service(Ssdp.RENDERING_CONTROL, "urn:upnp-org:serviceId:RenderingControl", "RenderingControl")
    val CONNECTION_MANAGER = Service(Ssdp.CONNECTION_MANAGER, "urn:upnp-org:serviceId:ConnectionManager", "ConnectionManager")
    val SERVICES = listOf(AV_TRANSPORT, RENDERING_CONTROL, CONNECTION_MANAGER)

    fun device(friendlyName: String, uuid: String): String = """<?xml version="1.0" encoding="UTF-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>${Ssdp.MEDIA_RENDERER}</deviceType>
    <friendlyName>${Soap.escape(friendlyName)}</friendlyName>
    <manufacturer>CastBay</manufacturer>
    <manufacturerURL>https://castbay.weenas.com</manufacturerURL>
    <modelDescription>AirPlay and DLNA receiver for Android TVs, car displays and tablets</modelDescription>
    <modelName>CastBay</modelName>
    <modelNumber>1</modelNumber>
    <UDN>uuid:$uuid</UDN>
    <dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>
    <serviceList>
${SERVICES.joinToString("\n") { service ->
        """      <service>
        <serviceType>${service.type}</serviceType>
        <serviceId>${service.id}</serviceId>
        <SCPDURL>${service.scpdPath}</SCPDURL>
        <controlURL>${service.controlPath}</controlURL>
        <eventSubURL>${service.eventPath}</eventSubURL>
      </service>"""
    }}
    </serviceList>
  </device>
</root>
"""

    /** [name] with its direction and state variable, e.g. ("InstanceID", IN, "A_ARG_TYPE_InstanceID"). */
    private data class Arg(val name: String, val out: Boolean, val variable: String)
    private data class Variable(val name: String, val type: String, val events: Boolean = false, val allowed: List<String> = emptyList())

    private fun inArg(name: String, variable: String = "A_ARG_TYPE_$name") = Arg(name, false, variable)
    private fun outArg(name: String, variable: String = name) = Arg(name, true, variable)
    private val instance = inArg("InstanceID")

    fun scpd(service: Service): String = when (service) {
        AV_TRANSPORT -> scpd(
            mapOf(
                "SetAVTransportURI" to listOf(instance, inArg("CurrentURI", "AVTransportURI"), inArg("CurrentURIMetaData", "AVTransportURIMetaData")),
                "GetMediaInfo" to listOf(instance, outArg("NrTracks", "NumberOfTracks"), outArg("MediaDuration", "CurrentMediaDuration"),
                    outArg("CurrentURI", "AVTransportURI"), outArg("CurrentURIMetaData", "AVTransportURIMetaData"),
                    outArg("NextURI", "NextAVTransportURI"), outArg("NextURIMetaData", "NextAVTransportURIMetaData"),
                    outArg("PlayMedium", "PlaybackStorageMedium"), outArg("RecordMedium", "RecordStorageMedium"),
                    outArg("WriteStatus", "RecordMediumWriteStatus")),
                "GetTransportInfo" to listOf(instance, outArg("CurrentTransportState", "TransportState"),
                    outArg("CurrentTransportStatus", "TransportStatus"), outArg("CurrentSpeed", "TransportPlaySpeed")),
                "GetPositionInfo" to listOf(instance, outArg("Track", "CurrentTrack"), outArg("TrackDuration", "CurrentTrackDuration"),
                    outArg("TrackMetaData", "CurrentTrackMetaData"), outArg("TrackURI", "CurrentTrackURI"),
                    outArg("RelTime", "RelativeTimePosition"), outArg("AbsTime", "AbsoluteTimePosition"),
                    outArg("RelCount", "RelativeCounterPosition"), outArg("AbsCount", "AbsoluteCounterPosition")),
                "GetDeviceCapabilities" to listOf(instance, outArg("PlayMedia", "PossiblePlaybackStorageMedia"),
                    outArg("RecMedia", "PossibleRecordStorageMedia"), outArg("RecQualityModes", "PossibleRecordQualityModes")),
                "GetTransportSettings" to listOf(instance, outArg("PlayMode", "CurrentPlayMode"), outArg("RecQualityMode", "CurrentRecordQualityMode")),
                "GetCurrentTransportActions" to listOf(instance, outArg("Actions", "CurrentTransportActions")),
                "Stop" to listOf(instance),
                "Play" to listOf(instance, inArg("Speed", "TransportPlaySpeed")),
                "Pause" to listOf(instance),
                "Seek" to listOf(instance, inArg("Unit", "A_ARG_TYPE_SeekMode"), inArg("Target", "A_ARG_TYPE_SeekTarget")),
                "Next" to listOf(instance),
                "Previous" to listOf(instance)
            ),
            listOf(
                Variable("TransportState", "string", allowed = DlnaState.TRANSPORT_STATES),
                Variable("TransportStatus", "string", allowed = listOf("OK", "ERROR_OCCURRED")),
                Variable("TransportPlaySpeed", "string", allowed = listOf("1")),
                Variable("NumberOfTracks", "ui4"), Variable("CurrentTrack", "ui4"),
                Variable("CurrentMediaDuration", "string"), Variable("CurrentTrackDuration", "string"),
                Variable("CurrentTrackMetaData", "string"), Variable("CurrentTrackURI", "string"),
                Variable("AVTransportURI", "string"), Variable("AVTransportURIMetaData", "string"),
                Variable("NextAVTransportURI", "string"), Variable("NextAVTransportURIMetaData", "string"),
                Variable("RelativeTimePosition", "string"), Variable("AbsoluteTimePosition", "string"),
                Variable("RelativeCounterPosition", "i4"), Variable("AbsoluteCounterPosition", "i4"),
                Variable("PlaybackStorageMedium", "string"), Variable("RecordStorageMedium", "string"),
                Variable("RecordMediumWriteStatus", "string"), Variable("PossiblePlaybackStorageMedia", "string"),
                Variable("PossibleRecordStorageMedia", "string"), Variable("PossibleRecordQualityModes", "string"),
                Variable("CurrentPlayMode", "string", allowed = listOf("NORMAL")),
                Variable("CurrentRecordQualityMode", "string"), Variable("CurrentTransportActions", "string"),
                Variable("LastChange", "string", events = true),
                Variable("A_ARG_TYPE_InstanceID", "ui4"),
                Variable("A_ARG_TYPE_SeekMode", "string", allowed = listOf("REL_TIME", "ABS_TIME", "TRACK_NR")),
                Variable("A_ARG_TYPE_SeekTarget", "string")
            )
        )
        RENDERING_CONTROL -> scpd(
            mapOf(
                "ListPresets" to listOf(instance, outArg("CurrentPresetNameList", "PresetNameList")),
                "SelectPreset" to listOf(instance, inArg("PresetName", "A_ARG_TYPE_PresetName")),
                "GetMute" to listOf(instance, inArg("Channel"), outArg("CurrentMute", "Mute")),
                "SetMute" to listOf(instance, inArg("Channel"), inArg("DesiredMute", "Mute")),
                "GetVolume" to listOf(instance, inArg("Channel"), outArg("CurrentVolume", "Volume")),
                "SetVolume" to listOf(instance, inArg("Channel"), inArg("DesiredVolume", "Volume"))
            ),
            listOf(
                Variable("PresetNameList", "string"), Variable("Mute", "boolean"), Variable("Volume", "ui2"),
                Variable("LastChange", "string", events = true),
                Variable("A_ARG_TYPE_InstanceID", "ui4"),
                Variable("A_ARG_TYPE_Channel", "string", allowed = listOf("Master")),
                Variable("A_ARG_TYPE_PresetName", "string", allowed = listOf("FactoryDefaults"))
            )
        )
        else -> scpd(
            mapOf(
                "GetProtocolInfo" to listOf(outArg("Source", "SourceProtocolInfo"), outArg("Sink", "SinkProtocolInfo")),
                "GetCurrentConnectionIDs" to listOf(outArg("ConnectionIDs", "CurrentConnectionIDs")),
                "GetCurrentConnectionInfo" to listOf(inArg("ConnectionID"), outArg("RcsID", "A_ARG_TYPE_RcsID"),
                    outArg("AVTransportID", "A_ARG_TYPE_AVTransportID"), outArg("ProtocolInfo", "A_ARG_TYPE_ProtocolInfo"),
                    outArg("PeerConnectionManager", "A_ARG_TYPE_ConnectionManager"), outArg("PeerConnectionID", "A_ARG_TYPE_ConnectionID"),
                    outArg("Direction", "A_ARG_TYPE_Direction"), outArg("Status", "A_ARG_TYPE_ConnectionStatus"))
            ),
            listOf(
                Variable("SourceProtocolInfo", "string", events = true), Variable("SinkProtocolInfo", "string", events = true),
                Variable("CurrentConnectionIDs", "string", events = true),
                Variable("A_ARG_TYPE_ConnectionStatus", "string", allowed = listOf("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown")),
                Variable("A_ARG_TYPE_ConnectionManager", "string"),
                Variable("A_ARG_TYPE_Direction", "string", allowed = listOf("Input", "Output")),
                Variable("A_ARG_TYPE_ProtocolInfo", "string"), Variable("A_ARG_TYPE_ConnectionID", "i4"),
                Variable("A_ARG_TYPE_AVTransportID", "i4"), Variable("A_ARG_TYPE_RcsID", "i4")
            )
        )
    }

    private fun scpd(actions: Map<String, List<Arg>>, variables: List<Variable>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
""")
        actions.forEach { (name, args) ->
            append("    <action><name>$name</name><argumentList>\n")
            args.forEach { arg ->
                append("      <argument><name>${arg.name}</name><direction>${if (arg.out) "out" else "in"}</direction>")
                append("<relatedStateVariable>${arg.variable}</relatedStateVariable></argument>\n")
            }
            append("    </argumentList></action>\n")
        }
        append("  </actionList>\n  <serviceStateTable>\n")
        variables.forEach { variable ->
            append("    <stateVariable sendEvents=\"${if (variable.events) "yes" else "no"}\"><name>${variable.name}</name>")
            append("<dataType>${variable.type}</dataType>")
            if (variable.allowed.isNotEmpty()) {
                append("<allowedValueList>")
                variable.allowed.forEach { append("<allowedValue>$it</allowedValue>") }
                append("</allowedValueList>")
            }
            append("</stateVariable>\n")
        }
        append("  </serviceStateTable>\n</scpd>\n")
    }
}
