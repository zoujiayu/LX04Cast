package com.plx.lx04cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.plx.lx04cast.util.Log
import com.weenas.castbay.protocol.AirPlayNative
import java.security.SecureRandom
import java.util.Locale

/**
 * Publishes the two DNS-SD services iOS/Mac need for AirPlay:
 * _airplay._tcp and _raop._tcp. The TXT records are built by UxPlay itself.
 */
class AirPlayAdvertiser(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("airplay_identity", Context.MODE_PRIVATE)
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val registrations = ArrayList<NsdManager.RegistrationListener>()

    @Synchronized
    fun start(name: String, port: Int): Boolean {
        stop()
        if (port <= 0) return false
        val safeName = name.trim().ifBlank { "LX04 投送" }.take(60)
        val id = deviceId()
        val airplay = linkedMapOf("deviceid" to id) + AirPlayNative.airPlayTxtRecord()
        val raop = AirPlayNative.raopTxtRecord()
        if (!register("_airplay._tcp", safeName, port, airplay)) return false
        if (!register("_raop._tcp", "${id.replace(":", "")}@$safeName", port, raop)) {
            stop()
            return false
        }
        Log.i(TAG, "AirPlay discovery registered as $safeName on $port")
        return true
    }

    private fun register(type: String, name: String, port: Int, attributes: Map<String, String>): Boolean {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = type
            this.port = port
            attributes.forEach { (key, value) ->
                runCatching { setAttribute(key, value) }
            }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Registered $type as ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Registering $type failed: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Unregistering $type failed: $errorCode")
            }
        }
        return try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registrations.add(listener)
            true
        } catch (error: Exception) {
            Log.e(TAG, "Could not register $type", error)
            false
        }
    }

    @Synchronized
    fun stop() {
        registrations.forEach { listener -> runCatching { nsd.unregisterService(listener) } }
        registrations.clear()
    }

    fun hardwareAddress(): ByteArray =
        deviceId().split(":").map { it.toInt(16).toByte() }.toByteArray()

    fun deviceId(): String {
        prefs.getString(KEY_ID, null)?.let { return it }
        val bytes = ByteArray(6).also(SecureRandom()::nextBytes)
        bytes[0] = ((bytes[0].toInt() and 0xFE) or 0x02).toByte()
        val id = bytes.joinToString(":") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }
        prefs.edit().putString(KEY_ID, id).apply()
        return id
    }

    private companion object {
        const val TAG = "LX04AirPlayMdns"
        const val KEY_ID = "device_id"
    }
}
