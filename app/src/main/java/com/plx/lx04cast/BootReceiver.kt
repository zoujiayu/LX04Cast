package com.plx.lx04cast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppSettings.init(context)
        if (!AppSettings.bootAutostart) return
        if (!AppSettings.airPlayEnabled && !AppSettings.dlnaEnabled) return
        CastService.start(context)
    }
}
