package com.plx.lx04cast

import com.plx.lx04cast.util.Log

/**
 * The LX04's ROM keeps roughly 8-10 MB of HWUI/EGL window buffers alive after the last
 * Activity is destroyed. Foreground receiver services are exempt from normal background
 * memory trims, so ask the framework's renderer to drop its caches explicitly when no UI
 * remains. This is only invoked after the process has no started Activity.
 */
object UiMemory {
    private const val TAG = "LX04UiMemory"

    fun trimAfterUiGone() {
        try {
            val renderer = Class.forName("android.view.ThreadedRenderer")
            val trim = renderer.getDeclaredMethod("trimMemory", Int::class.javaPrimitiveType)
            trim.isAccessible = true
            trim.invoke(null, 80) // ComponentCallbacks2.TRIM_MEMORY_COMPLETE
            Log.i(TAG, "Requested HWUI cache trim after UI closed")
        } catch (error: Throwable) {
            Log.w(TAG, "HWUI trim unavailable: ${error.javaClass.simpleName}")
        }
    }
}
