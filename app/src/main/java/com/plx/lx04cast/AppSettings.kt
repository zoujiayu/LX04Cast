package com.plx.lx04cast

import android.content.Context
import android.content.SharedPreferences

/** The three switches on the settings screen. All default to on. */
object AppSettings {
    private const val FILE = "lx04cast"
    private const val KEY_AIRPLAY = "airplay_enabled"
    private const val KEY_DLNA = "dlna_enabled"
    private const val KEY_BOOT = "boot_autostart"
    private const val KEY_NAME = "device_name"

    const val DEFAULT_NAME = "小爱触屏音箱"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    var airPlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_AIRPLAY, true)
        set(value) = prefs.edit().putBoolean(KEY_AIRPLAY, value).apply()

    var dlnaEnabled: Boolean
        get() = prefs.getBoolean(KEY_DLNA, true)
        set(value) = prefs.edit().putBoolean(KEY_DLNA, value).apply()

    var bootAutostart: Boolean
        get() = prefs.getBoolean(KEY_BOOT, true)
        set(value) = prefs.edit().putBoolean(KEY_BOOT, value).apply()

    /** Name shown in AirPlay and DLNA device lists; editable on the settings screen. */
    var deviceName: String
        get() = prefs.getString(KEY_NAME, DEFAULT_NAME)
            ?.trim()
            ?.take(60)
            ?.ifEmpty { DEFAULT_NAME }
            ?: DEFAULT_NAME
        set(value) = prefs.edit()
            .putString(KEY_NAME, value.trim().take(60).ifEmpty { DEFAULT_NAME })
            .apply()
}
