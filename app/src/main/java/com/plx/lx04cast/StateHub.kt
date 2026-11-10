package com.plx.lx04cast

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/** Everything the settings/player screens and the notification need, in one immutable value. */
data class PlayerUiState(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** NetEase song id, when the sender supplies it; gives the lyric lookup an exact key. */
    val neteaseId: String? = null,
    val cover: ByteArray? = null,
    val source: String = "",
    val playing: Boolean = false,
    val status: String = "等待投送",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val trackKey: String = ""
) {
    /** Position now, accounting for time passed since the last report. */
    fun positionNowMs(now: Long = SystemClock.elapsedRealtime()): Long {
        if (!playing || durationMs <= 0) return positionMs.coerceIn(0L, durationMs)
        return (positionMs + (now - updatedAtMs)).coerceIn(0L, durationMs)
    }
}

/** In-process state bus: the service publishes, the two screens listen. */
object StateHub {
    @Volatile
    var state = PlayerUiState()
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(PlayerUiState) -> Unit>()

    fun addListener(listener: (PlayerUiState) -> Unit) {
        listeners.add(listener)
        main.post { listener(state) }
    }

    fun removeListener(listener: (PlayerUiState) -> Unit) {
        listeners.remove(listener)
    }

    fun publish(next: PlayerUiState) {
        state = next
        main.post { listeners.toList().forEach { runCatching { it(next) } } }
    }
}
