package com.plx.lx04cast.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import com.plx.lx04cast.util.Diagnostics
import com.plx.lx04cast.util.Log
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plays AirPlay music (PCM decoded from ALAC) when the sender means each frame to be heard, as
 * Apple's receivers do. Senders send music about two seconds ahead and time everything by that:
 * the progress they report, and where they resume after a pause or seek (what they reckon was
 * heard). Played as it arrived instead, music ran ahead of the lyrics and repeated a second or
 * two on every resume.
 *
 * Frames wait in a queue until due; one thread writes them to a small track, timed against the
 * track's own [AudioTimestamp]s: silence first, until the first frame is due, then small
 * corrections (a sample dropped or repeated) for the drift between the two clocks. When nothing
 * arrives the track is fed silence, so it stays timed through a short stall, and is closed after
 * [IDLE_CLOSE_MS].
 */
class MusicPlayer(private val volume: () -> Float) {
    private class Frame(val pcm: ByteArray, val dueUs: Long)

    private val queue = LinkedBlockingDeque<Frame>()
    /** Bumped by [flush] and [stop]: the writer then closes its track and starts afresh. */
    private val generation = AtomicInteger()
    @Volatile private var track: AudioTrack? = null
    /** When the next frame is due, when the sender's clock is unknown; 0 after a flush. */
    @Volatile private var nextFallbackDueUs = 0L

    /** Local pause is the fallback when the sender's DACP control endpoint is unavailable. */
    @Volatile var outputPaused = false
        private set

    /** How long music waits between arriving and being heard, per the sender; for the UI. */
    @Volatile var delayMs = FALLBACK_DELAY_US / 1000
        private set

    /** How late (positive) or early the last frame was heard, for tests and logs. */
    @Volatile var syncErrorUs = 0L
        private set

    init {
        Thread(::writeLoop, "CastBay-music").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** [playAtUs]: when to hear it, in [System.currentTimeMillis] time (µs); 0 when unknown. */
    @Synchronized
    fun queue(pcm: ByteArray, playAtUs: Long) {
        if (pcm.isEmpty() || outputPaused) return
        val now = nowUs()
        // Without the sender's clock, frames follow on from the last: a sender sends its first
        // two seconds in a burst, which timed by arrival would all be due at once.
        val due = if (playAtUs > 0) playAtUs else maxOf(now + FALLBACK_DELAY_US, nextFallbackDueUs)
        nextFallbackDueUs = due + pcm.size / BYTES_PER_FRAME * 1_000_000L / SAMPLE_RATE
        delayMs = ((due - now) / 1000).coerceAtLeast(0)
        if (queue.size >= MAX_QUEUED) {
            Log.w(TAG, "Music queue full; dropping a frame")
            return
        }
        queue.add(Frame(pcm, due))
    }

    /** Drops music not yet heard: the sender paused, sought or changed track, or left. */
    @Synchronized
    fun flush() {
        queue.clear()
        nextFallbackDueUs = 0
        generation.incrementAndGet()
    }

    fun stop() {
        outputPaused = false
        flush()
    }

    /**
     * Silences the local AudioTrack and drops arriving music. This lets the pause button work
     * even when an AirPlay sender does not expose DACP; the sender keeps streaming, so
     * [resumeOutput] flushes stale audio and the next frame is heard at the current position.
     */
    @Synchronized
    fun pauseOutput() {
        if (outputPaused) return
        outputPaused = true
        queue.clear()
        nextFallbackDueUs = 0
        track?.let { runCatching { it.setVolume(0f) } }
    }

    @Synchronized
    fun resumeOutput() {
        if (!outputPaused) return
        outputPaused = false
        flush()
        track?.let { runCatching { it.setVolume(volume()) } }
    }

    fun setVolume(gain: Float) {
        if (outputPaused) return
        track?.let { runCatching { it.setVolume(gain) } }
    }

    private fun writeLoop() {
        var seen = generation.get()
        var session: Session? = null
        // When music stopped arriving with no flush from the sender (a pause flushes): if it
        // comes back, the network held it up.
        var stalledSinceMs = 0L
        while (true) {
            val current = generation.get()
            if (current != seen) {
                seen = current
                session?.close()
                session = null
                stalledSinceMs = 0
            }
            val frame = queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
            if (frame == null) {
                if (session != null && !session.idle()) {
                    session.close()
                    session = null
                    stalledSinceMs = android.os.SystemClock.elapsedRealtime() - IDLE_CLOSE_MS
                }
                continue
            }
            if (session == null && stalledSinceMs != 0L) {
                val stalledMs = android.os.SystemClock.elapsedRealtime() - stalledSinceMs
                stalledSinceMs = 0
                if (stalledMs < STALL_MAX_MS) {
                    val text = "Music stopped arriving for ${stalledMs / 1000.0} s, then came back (held up on the network)"
                    Log.w(TAG, text)
                    Diagnostics.record("music", text)
                }
            }
            val active = session ?: Session(seen).also { session = it }
            if (!active.play(frame)) {
                active.close()
                session = null
            }
        }
    }

    /** One track, timed from its first frame until closed. Writer thread only. */
    private inner class Session(private val generation: Int) {
        private val out = createTrack().also { track = it }
        private val timestamp = AudioTimestamp()
        /** Frames written to [out]. */
        private var written = 0L
        private var timed = false
        private var idleSinceMs = 0L
        private var trimmedUs = 0L
        private var paddedUs = 0L
        private val stutter = Stutter()

        /** Writes [frame] on time; false when the player was flushed meanwhile. */
        fun play(frame: Frame): Boolean {
            // Music came back after a wait: that wait was a gap in it.
            if (idleSinceMs != 0L) stutter.gapEnded()
            idleSinceMs = 0
            stutter.reportEvery(STUTTER_REPORT_MS)
            var pcm = frame.pcm
            val dueUs = frame.dueUs
            if (!timed) {
                // Silence until the first frame is due, measured once the track is playing.
                while (true) {
                    val heard = heardUs(written)
                    if (heard == null) {
                        if (!writeSilence(CHUNK_US)) return false
                        continue
                    }
                    val waitUs = dueUs - heard
                    if (waitUs > CHUNK_US) {
                        if (!writeSilence(CHUNK_US)) return false
                        continue
                    }
                    if (waitUs > 0) {
                        if (!writeSilence(waitUs)) return false
                    } else {
                        // Already due: music held up on the way, now too late to hear.
                        stutter.skippedUs += minOf(-waitUs, durationUs(pcm))
                        pcm = trim(pcm, -waitUs) ?: return true
                    }
                    Log.i(TAG, "Music timed: first frame ${(-waitUs) / 1000} ms late, heard ${(dueUs - nowUs()) / 1000} ms after arriving")
                    timed = true
                    break
                }
            } else {
                heardUs(written)?.let { heard ->
                    val lateUs = heard - dueUs
                    syncErrorUs = lateUs
                    pcm = when {
                        lateUs > HARD_US -> {
                            // What is actually dropped: at most this frame.
                            val skippedUs = minOf(lateUs, durationUs(pcm))
                            trimmedUs += skippedUs
                            stutter.skippedUs += skippedUs
                            trim(pcm, lateUs) ?: return true
                        }
                        lateUs < -HARD_US -> {
                            paddedUs -= lateUs
                            stutter.paddedUs -= lateUs
                            if (!writeSilence(-lateUs)) return false
                            pcm
                        }
                        // Clock drift: one sample in 352 is too little to hear.
                        lateUs > SOFT_US -> pcm.copyOfRange(0, pcm.size - BYTES_PER_FRAME)
                        lateUs < -SOFT_US -> pcm + pcm.copyOfRange(pcm.size - BYTES_PER_FRAME, pcm.size)
                        else -> pcm
                    }
                }
            }
            return write(pcm)
        }

        /** Keeps the track timed while nothing arrives; false once it has been idle too long. */
        fun idle(): Boolean {
            val now = android.os.SystemClock.elapsedRealtime()
            if (idleSinceMs == 0L) idleSinceMs = now
            if (now - idleSinceMs > IDLE_CLOSE_MS) return false
            if (timed) stutter.waitingUs += POLL_MS * 1000
            return !timed || writeSilence(POLL_MS * 1000)
        }

        fun close() {
            stutter.report()
            if (track === out) track = null
            if (trimmedUs > 0 || paddedUs > 0) {
                Log.i(TAG, "Music resynced: ${trimmedUs / 1000} ms skipped, ${paddedUs / 1000} ms of silence added")
            }
            runCatching { out.pause() }
            out.release()
        }

        /** When the frame at [position] will be heard ([System.currentTimeMillis] µs), once the track reports it. */
        private fun heardUs(position: Long): Long? {
            if (!out.getTimestamp(timestamp) || timestamp.framePosition <= 0) return null
            val monotonicNs = timestamp.nanoTime + (position - timestamp.framePosition) * 1_000_000_000L / SAMPLE_RATE
            return (monotonicNs - System.nanoTime()) / 1000 + nowUs()
        }

        /** [pcm] without its first [us]; null when nothing is left. */
        private fun trim(pcm: ByteArray, us: Long): ByteArray? {
            val bytes = framesFor(us) * BYTES_PER_FRAME
            return if (bytes >= pcm.size) null else pcm.copyOfRange(bytes.toInt(), pcm.size)
        }

        private fun writeSilence(us: Long): Boolean = write(ByteArray((framesFor(us) * BYTES_PER_FRAME).toInt()))

        /** Waits for room rather than blocking, so a flush is seen at once. */
        private fun write(pcm: ByteArray): Boolean {
            var offset = 0
            while (offset < pcm.size) {
                if (generation != this@MusicPlayer.generation.get()) return false
                val count = try {
                    out.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                } catch (released: IllegalStateException) {
                    return false
                }
                if (count < 0) {
                    Log.w(TAG, "Music track write failed: $count")
                    return false
                }
                offset += count
                // A streaming track starts once its buffer is full; it is small, so that is soon.
                if (out.playState != AudioTrack.PLAYSTATE_PLAYING) out.play()
                if (offset < pcm.size) Thread.sleep(WRITE_WAIT_MS)
            }
            written += pcm.size / BYTES_PER_FRAME
            return true
        }

        private fun framesFor(us: Long) = us * SAMPLE_RATE / 1_000_000

        private fun durationUs(pcm: ByteArray) = pcm.size / BYTES_PER_FRAME * 1_000_000L / SAMPLE_RATE

        /**
         * What made the music stutter, logged and noted in Diagnostics every so often when there
         * was any, so a problem report tells a network that drops music (gaps, late music
         * skipped) from a device too slow to play it (the track running dry).
         */
        private inner class Stutter {
            /** Silence while nothing arrived; a gap once music comes back (else it was a pause). */
            var waitingUs = 0L
            private var gaps = 0
            private var gapUs = 0L
            var skippedUs = 0L
            var paddedUs = 0L
            private var since = android.os.SystemClock.elapsedRealtime()
            private var underruns = underrunCount()

            fun gapEnded() {
                if (waitingUs >= HARD_US) {
                    gaps++
                    gapUs += waitingUs
                }
                waitingUs = 0
            }

            fun reportEvery(intervalMs: Long) {
                if (android.os.SystemClock.elapsedRealtime() - since >= intervalMs) report()
            }

            fun report() {
                val now = android.os.SystemClock.elapsedRealtime()
                val count = underrunCount()
                val newUnderruns = count - underruns
                if (gapUs + skippedUs + paddedUs >= STUTTER_MIN_US || newUnderruns > 0) {
                    val text = "Music stuttered in ${(now - since) / 1000} s: $gaps gaps (${gapUs / 1000} ms with nothing arriving), " +
                        "${skippedUs / 1000} ms arrived late and skipped, ${paddedUs / 1000} ms of silence added, " +
                        "$newUnderruns underruns (the track ran dry)"
                    Log.w(TAG, text)
                    Diagnostics.record("music", text)
                }
                gaps = 0
                gapUs = 0
                skippedUs = 0
                paddedUs = 0
                underruns = count
                since = now
            }

            // Android 7+ count them; before, only the network side shows.
            private fun underrunCount(): Int =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) runCatching { out.underrunCount }.getOrDefault(0) else 0
        }
    }

    private fun createTrack(): AudioTrack {
        val channelMask = AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer * 2, SAMPLE_RATE * TRACK_BUFFER_MS / 1000 * BYTES_PER_FRAME))
            .build()
            .also { it.setVolume(volume()) }
    }

    private fun nowUs() = System.currentTimeMillis() * 1000

    companion object {
        private const val TAG = "CastBayMusic"
        private const val SAMPLE_RATE = 44100
        /** 16-bit stereo. */
        private const val BYTES_PER_FRAME = 4
        /** Music heard this long after arriving when the sender's clock isn't known yet. */
        private const val FALLBACK_DELAY_US = 500_000L
        /** The track itself: small, so it starts soon and holds little that a flush throws away. */
        private const val TRACK_BUFFER_MS = 250
        /** About five seconds of 352-sample frames, over twice what senders send ahead. */
        private const val MAX_QUEUED = 625
        private const val POLL_MS = 10L
        private const val WRITE_WAIT_MS = 5L
        private const val CHUNK_US = 10_000L
        /** Off by more than this, music skips or waits at once (a stall, or the start). */
        private const val HARD_US = 40_000L
        /** Off by more than this, a sample is dropped or repeated per frame. */
        private const val SOFT_US = 2_000L
        /** No music for this long (the sender paused): the track is closed. */
        private const val IDLE_CLOSE_MS = 1000L
        /** Stutter is reported at most this often while it goes on, and when the track closes. */
        private const val STUTTER_REPORT_MS = 30_000L
        /** Music coming back after longer than this was more likely a new start than a stall. */
        private const val STALL_MAX_MS = 60_000L
        /** Less than this in a report's time isn't worth one. */
        private const val STUTTER_MIN_US = 100_000L
    }
}
