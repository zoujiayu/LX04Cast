package com.plx.lx04cast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.plx.lx04cast.util.Log

/**
 * Fallback entry point for media buttons when the system routes them to a session's
 * mediaButtonReceiver instead of (or in addition to) the MediaSession callback. This is
 * also what keeps our session a candidate when the cast is paused and Mico's own video
 * session would otherwise own the global media-button slot.
 */
class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        Log.i(TAG, "Media button key=${event.keyCode} action=${event.action}")
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> CastService.play(context)
            KeyEvent.KEYCODE_MEDIA_PAUSE -> CastService.pause(context)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> CastService.toggle(context)
            KeyEvent.KEYCODE_MEDIA_STOP -> CastService.stopPlayback(context)
            KeyEvent.KEYCODE_MEDIA_NEXT -> CastService.next(context)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> CastService.previous(context)
        }
    }

    private companion object {
        const val TAG = "LX04MediaButton"
    }
}
