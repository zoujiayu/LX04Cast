package com.plx.lx04cast

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.SwitchPreference
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/**
 * Plain platform settings screen (no custom layout/drawables): device name, the two
 * receivers and boot autostart. The native preference list is lighter than the old
 * custom view and does not listen to playback state.
 */
class SettingsActivity : PreferenceActivity() {

    companion object {
        @Volatile
        var instance: SettingsActivity? = null
            private set

        private const val KEY_NAME = "device_name"
        private const val KEY_AIRPLAY = "airplay_enabled"
        private const val KEY_DLNA = "dlna_enabled"
        private const val KEY_BOOT = "boot_autostart"
    }

    private lateinit var namePreference: Preference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        AppSettings.init(this)
        setTitle(R.string.settings_label)
        addPreferencesFromResource(R.xml.preferences)
        // The native list just barely overflows the short panel; its scrollbar would sit
        // over the first column for the whole time. Scrolling still works without it.
        listView.isVerticalScrollBarEnabled = false
        listView.overScrollMode = android.view.View.OVER_SCROLL_NEVER

        namePreference = findPreference(KEY_NAME) as Preference
        namePreference.summary = AppSettings.deviceName
        namePreference.setOnPreferenceClickListener {
            showNameDialog()
            true
        }

        val airPlay = findPreference(KEY_AIRPLAY) as SwitchPreference
        airPlay.isChecked = AppSettings.airPlayEnabled
        airPlay.setOnPreferenceChangeListener { _, newValue ->
            AppSettings.airPlayEnabled = newValue as Boolean
            CastService.applySettings(this)
            true
        }

        val dlna = findPreference(KEY_DLNA) as SwitchPreference
        dlna.isChecked = AppSettings.dlnaEnabled
        dlna.setOnPreferenceChangeListener { _, newValue ->
            AppSettings.dlnaEnabled = newValue as Boolean
            CastService.applySettings(this)
            true
        }

        val boot = findPreference(KEY_BOOT) as SwitchPreference
        boot.isChecked = AppSettings.bootAutostart
        boot.setOnPreferenceChangeListener { _, newValue ->
            AppSettings.bootAutostart = newValue as Boolean
            true
        }

        CastService.start(this)
    }

    private fun showNameDialog() {
        val input = EditText(this).apply {
            setText(AppSettings.deviceName)
            setSelection(text.length)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.settings_name_label)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_name_label)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                applyDeviceName(input.text?.toString().orEmpty())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val keyboard = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        dialog.show()
    }

    private fun applyDeviceName(raw: String) {
        val name = raw.trim().take(60).ifEmpty { AppSettings.DEFAULT_NAME }
        AppSettings.deviceName = name
        namePreference.summary = name
        CastService.applySettings(this)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}
