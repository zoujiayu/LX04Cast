package com.plx.lx04cast.util

import android.content.Context

/** Stub for the CastBay diagnostics module; only logging is kept. */
object Diagnostics {
    fun init(context: Context) = Unit
    fun record(area: String, text: String) = Log.d("LX04Cast", "$area: $text")
    fun receiverRunning(running: Boolean) = Unit
}
