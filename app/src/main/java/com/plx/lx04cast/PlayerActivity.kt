package com.plx.lx04cast

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.plx.lx04cast.lyrics.LyricsRepository
import com.plx.lx04cast.lyrics.LyricsView
import com.plx.lx04cast.util.Log

/**
 * Two-page Apple Music / iOS StandBy inspired landscape player:
 * page 0 is now playing, page 1 is the transparent lyrics page. Both pages float over
 * the same fixed blurred artwork backdrop, so swiping moves only the translucent content.
 */
class PlayerActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var tickerRunning = false
    private lateinit var pager: PagerScrollView
    private lateinit var ambientView: ImageView
    private lateinit var coverView: ArtworkView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var playPauseButton: ImageButton
    private lateinit var previousButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var lyricsView: LyricsView
    private lateinit var dotPlaying: View
    private lateinit var dotLyrics: View
    private lateinit var pageDots: View
    private lateinit var playingContent: View
    private lateinit var controlBar: FrostedControlBar
    private var density = 1f

    private var currentLyricsKey = ""
    private var lyricsRequestKey = ""
    private var lyricsLoadedKey = ""
    private var lyricsTimeoutKey = ""
    private var coverSource: ByteArray? = null
    private var blurredBackdrop: Bitmap? = null
    private var controlBarShown = false
    private var screenWakeLock: PowerManager.WakeLock? = null

    private val stateListener: (PlayerUiState) -> Unit = { state -> render(state) }

    private val controlBarAutoHide = Runnable { hideControlBar() }

    private val lyricsTimeout = object : Runnable {
        override fun run() {
            val key = lyricsTimeoutKey
            if (key.isEmpty() || key != currentLyricsKey || key != lyricsRequestKey) return
            lyricsRequestKey = ""
            lyricsLoadedKey = key
            lyricsView.setEmptyText(getString(R.string.no_lyrics))
            lyricsView.setLyrics(emptyList(), false)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val state = StateHub.state
            if (!isVisible || pager.currentPage != LYRICS_PAGE || !state.playing) {
                tickerRunning = false
                return
            }
            lyricsView.setPosition(state.positionNowMs())
            // Twice a second keeps the highlight within ~0.5 s of the song's own clock;
            // setPosition only re-styles when the line actually changes.
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        setContentView(R.layout.activity_player)
        // The player covers the settings window; finishing it frees its list while casting.
        SettingsActivity.instance?.takeIf { !it.isFinishing }?.finish()

        pager = findViewById(R.id.pager)
        ambientView = findViewById(R.id.ambient)
        coverView = findViewById(R.id.cover)
        titleView = findViewById(R.id.title)
        artistView = findViewById(R.id.artist)
        playPauseButton = findViewById(R.id.btn_play_pause)
        previousButton = findViewById(R.id.btn_previous)
        nextButton = findViewById(R.id.btn_next)
        lyricsView = findViewById(R.id.lyrics)
        dotPlaying = findViewById(R.id.dot_playing)
        dotLyrics = findViewById(R.id.dot_lyrics)
        pageDots = findViewById(R.id.page_dots)
        playingContent = findViewById(R.id.page_playing_content)
        controlBar = findViewById(R.id.control_bar)
        density = resources.displayMetrics.density
        controlBar.alpha = 0f
        controlBar.translationY = -14f * density
        controlBar.scaleX = 0.96f
        controlBar.scaleY = 0.96f

        playPauseButton.setOnClickListener {
            CastService.toggle(this)
            resetControlBarHideTimer()
        }
        previousButton.setOnClickListener {
            CastService.previous(this)
            resetControlBarHideTimer()
        }
        nextButton.setOnClickListener {
            CastService.next(this)
            resetControlBarHideTimer()
        }
        controlBar.setOnClickListener { resetControlBarHideTimer() }
        coverView.setOnClickListener {
            Log.i(TAG, "Cover clicked; controlBarShown=$controlBarShown")
            toggleControlBar()
        }
        pager.onPageChanged = { page -> onPageChanged(page) }
        updateDots(PLAYING_PAGE)

        render(StateHub.state)
    }

    override fun onStart() {
        super.onStart()
        isVisible = true
        // Stay awake while the playback screen is visible, including during the device's
        // scheduled sleep window. Both the window flag and a screen wake lock are used;
        // some vendor sleep policies ignore FLAG_KEEP_SCREEN_ON alone.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        acquireScreenWakeLock()
        StateHub.addListener(stateListener)
        // Always open on the playback page; lyrics are one swipe away.
        pager.goToPage(PLAYING_PAGE)
        render(StateHub.state)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onStop() {
        isVisible = false
        StateHub.removeListener(stateListener)
        handler.removeCallbacks(ticker)
        tickerRunning = false
        releaseArtwork()
        lyricsView.setLyrics(emptyList(), false)
        lyricsRequestKey = ""
        lyricsLoadedKey = ""
        currentLyricsKey = ""
        lyricsTimeoutKey = ""
        handler.removeCallbacks(lyricsTimeout)
        hideControlBar(animate = false)
        LyricsRepository.trimMemory()
        releaseScreenWakeLock()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    private fun toggleControlBar() {
        if (controlBarShown) hideControlBar() else showControlBar()
    }

    private fun showControlBar() {
        if (!isVisible) return
        Log.i(TAG, "Showing control bar")
        controlBarShown = true
        controlBar.backdrop = blurredBackdrop
        controlBar.visibility = View.VISIBLE
        controlBar.animate().cancel()
        controlBar.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        // The bar floats above the page like the system player, but the album art must
        // not be hidden behind it: slide the whole page down just enough to clear it.
        controlBar.post { makeRoomForControlBar() }
        scheduleControlBarHide()
    }

    private fun makeRoomForControlBar() {
        if (!controlBarShown || !isVisible) return
        val targetTop = controlBar.bottom + (2f * density).toInt()
        val shift = (targetTop - coverView.top).coerceAtLeast(0).toFloat()
        playingContent.animate().cancel()
        playingContent.animate()
            .translationY(shift)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        pageDots.animate().cancel()
        pageDots.animate().alpha(0f).setDuration(150L).start()
    }

    private fun hideControlBar(animate: Boolean = true) {
        if (controlBarShown) Log.i(TAG, "Hiding control bar")
        controlBarShown = false
        handler.removeCallbacks(controlBarAutoHide)
        controlBar.animate().cancel()
        playingContent.animate().cancel()
        pageDots.animate().cancel()
        if (!animate) {
            controlBar.visibility = View.GONE
            controlBar.alpha = 0f
            controlBar.translationY = -14f * density
            controlBar.scaleX = 0.96f
            controlBar.scaleY = 0.96f
            playingContent.translationY = 0f
            pageDots.alpha = 1f
            return
        }
        playingContent.animate()
            .translationY(0f)
            .setDuration(200L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        pageDots.animate().alpha(1f).setDuration(180L).start()
        controlBar.animate()
            .alpha(0f)
            .translationY(-8f * density)
            .scaleX(0.97f)
            .scaleY(0.97f)
            .setDuration(180L)
            .withEndAction {
                if (!controlBarShown) controlBar.visibility = View.GONE
            }
            .start()
    }

    private fun scheduleControlBarHide() {
        handler.removeCallbacks(controlBarAutoHide)
        handler.postDelayed(controlBarAutoHide, CONTROL_BAR_HIDE_MS)
    }

    private fun resetControlBarHideTimer() {
        if (controlBarShown) scheduleControlBarHide()
    }

    @Suppress("DEPRECATION")
    private fun acquireScreenWakeLock() {
        if (screenWakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        screenWakeLock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "LX04Cast:player-screen"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseScreenWakeLock() {
        screenWakeLock?.let { if (it.isHeld) it.release() }
        screenWakeLock = null
    }

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        handler.post(ticker)
    }

    private fun updateControlButtons(state: PlayerUiState) {
        val hasSource = state.source.isNotEmpty()
        playPauseButton.isEnabled = hasSource
        playPauseButton.alpha = if (hasSource) 1f else 0.35f
        // DLNA cannot control the sender's queue. Keep the buttons clickable so a tap
        // explains that the phone must change tracks, instead of looking broken.
        previousButton.isEnabled = hasSource
        nextButton.isEnabled = hasSource
        val brightSkip = state.source == "AirPlay"
        previousButton.alpha = if (!hasSource) 0.3f else if (brightSkip) 1f else 0.5f
        nextButton.alpha = if (!hasSource) 0.3f else if (brightSkip) 1f else 0.5f
    }

    private fun onPageChanged(page: Int) {
        updateDots(page)
        if (page != PLAYING_PAGE) hideControlBar()
        val state = StateHub.state
        if (page == LYRICS_PAGE) {
            lyricsView.setPosition(state.positionNowMs())
            if (state.source.isNotEmpty()) loadLyrics(state)
        }
        syncTicker()
    }

    private fun syncTicker() {
        if (isVisible && pager.currentPage == LYRICS_PAGE && StateHub.state.playing) {
            startTicker()
        } else {
            handler.removeCallbacks(ticker)
            tickerRunning = false
        }
    }

    private fun updateDots(page: Int) {
        dotPlaying.alpha = if (page == PLAYING_PAGE) 1f else 0.35f
        dotLyrics.alpha = if (page == LYRICS_PAGE) 1f else 0.35f
    }

    private fun render(state: PlayerUiState) {
        if (state.source.isEmpty()) {
            titleView.text = getString(R.string.waiting)
            artistView.text = getString(R.string.idle_artist_fmt, AppSettings.deviceName)
            playPauseButton.setImageResource(R.drawable.ic_play)
            currentLyricsKey = ""
            lyricsRequestKey = ""
            lyricsLoadedKey = ""
            lyricsTimeoutKey = ""
            handler.removeCallbacks(lyricsTimeout)
            lyricsView.setEmptyText(getString(R.string.no_lyrics))
            lyricsView.setLyrics(emptyList(), false)
            releaseArtwork()
            hideControlBar(animate = false)
            updateControlButtons(state)
            if (pager.currentPage != PLAYING_PAGE) pager.goToPage(PLAYING_PAGE)
            syncTicker()
            return
        }

        titleView.text = state.title?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
        artistView.text = state.artist?.takeIf { it.isNotBlank() }
            ?: getString(R.string.unknown_artist)
        playPauseButton.setImageResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play)
        showCover(state.cover)
        updateControlButtons(state)

        // Some AirPlay senders update the title with every lyric line. Use the stable song
        // identity (parsed from the artist metadata when available) so those line changes do
        // not restart the lookup and leave the page stuck on "loading".
        val lyricsKey = LyricsRepository.queryFor(
            state.title, state.artist, state.album, state.neteaseId
        )?.key.orEmpty()
        if (lyricsKey != currentLyricsKey) {
            currentLyricsKey = lyricsKey
            lyricsLoadedKey = ""
            lyricsRequestKey = ""
            lyricsTimeoutKey = ""
            handler.removeCallbacks(lyricsTimeout)
            lyricsView.setEmptyText(getString(R.string.no_lyrics))
            lyricsView.setLyrics(emptyList(), false)
            if (pager.currentPage == LYRICS_PAGE && lyricsKey.isNotEmpty()) {
                loadLyrics(state)
            }
        }
        syncTicker()
    }

    // ---- Artwork ----

    private fun showCover(bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) {
            releaseArtwork()
            return
        }
        if (coverSource === bytes) return
        val art = decode(bytes, ART_SIZE)
        if (art == null) {
            releaseArtwork()
            return
        }
        coverSource = bytes
        coverView.setArtwork(art)
        // Build a small, genuinely blurred backdrop instead of just stretching a thumbnail.
        // 160px is large enough to keep the colours and shapes, tiny enough for this device.
        val backdrop = blurredBackdrop(art)
        blurredBackdrop = backdrop
        ambientView.setImageBitmap(backdrop)
        (ambientView.drawable as? BitmapDrawable)?.isFilterBitmap = true
        controlBar.backdrop = backdrop
    }

    private fun blurredBackdrop(art: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(art, AMBIENT_SIZE, AMBIENT_SIZE, true)
        val width = small.width
        val height = small.height
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        // Three box-blur passes give a smoother, Gaussian-like backdrop without the blocky
        // look of scaling a tiny thumbnail over the whole screen.
        boxBlur(pixels, width, height, BLUR_RADIUS)
        boxBlur(pixels, width, height, BLUR_RADIUS)
        boxBlur(pixels, width, height, BLUR_RADIUS)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        if (small !== art) small.recycle()
        return out
    }

    private fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
        val temp = IntArray(width * height)
        val divisor = radius * 2 + 1

        for (y in 0 until height) {
            var sumR = 0
            var sumG = 0
            var sumB = 0
            for (i in -radius..radius) {
                val color = pixels[y * width + i.coerceIn(0, width - 1)]
                sumR += (color shr 16) and 0xFF
                sumG += (color shr 8) and 0xFF
                sumB += color and 0xFF
            }
            for (x in 0 until width) {
                temp[y * width + x] = (0xFF shl 24) or
                    ((sumR / divisor) shl 16) or
                    ((sumG / divisor) shl 8) or
                    (sumB / divisor)
                val add = pixels[y * width + (x + radius + 1).coerceAtMost(width - 1)]
                val sub = pixels[y * width + (x - radius).coerceAtLeast(0)]
                sumR += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                sumG += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                sumB += (add and 0xFF) - (sub and 0xFF)
            }
        }

        for (x in 0 until width) {
            var sumR = 0
            var sumG = 0
            var sumB = 0
            for (i in -radius..radius) {
                val color = temp[i.coerceIn(0, height - 1) * width + x]
                sumR += (color shr 16) and 0xFF
                sumG += (color shr 8) and 0xFF
                sumB += color and 0xFF
            }
            for (y in 0 until height) {
                pixels[y * width + x] = (0xFF shl 24) or
                    ((sumR / divisor) shl 16) or
                    ((sumG / divisor) shl 8) or
                    (sumB / divisor)
                val add = temp[(y + radius + 1).coerceAtMost(height - 1) * width + x]
                val sub = temp[(y - radius).coerceAtLeast(0) * width + x]
                sumR += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                sumG += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                sumB += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }

    private fun releaseArtwork() {
        coverSource = null
        blurredBackdrop = null
        coverView.setArtwork(null)
        ambientView.setImageDrawable(null)
        controlBar.backdrop = null
    }

    private fun decode(bytes: ByteArray, maxSize: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            )
        } catch (error: Exception) {
            Log.w(TAG, "Could not decode cover art", error)
            null
        }
    }

    // ---- Lyrics page ----

    private fun loadLyrics(state: PlayerUiState) {
        val query = LyricsRepository.queryFor(
            state.title, state.artist, state.album, state.neteaseId
        ) ?: return
        val key = query.key
        if (key.isEmpty() || key == lyricsLoadedKey || key == lyricsRequestKey) return
        if (!isVisible || pager.currentPage != LYRICS_PAGE) return
        lyricsRequestKey = key
        lyricsView.setEmptyText(getString(R.string.lyrics_loading))
        lyricsView.setLyrics(emptyList(), false)
        lyricsTimeoutKey = key
        handler.removeCallbacks(lyricsTimeout)
        handler.postDelayed(lyricsTimeout, LYRICS_TIMEOUT_MS)
        Log.i(TAG, "Lyrics request key='$key' title='${query.title}' artist='${query.artist}'")
        LyricsRepository.fetch(query) { lyrics ->
            runOnUiThread {
                if (!isVisible || currentLyricsKey != key) return@runOnUiThread
                handler.removeCallbacks(lyricsTimeout)
                lyricsRequestKey = ""
                lyricsLoadedKey = key
                if (lyrics != null && lyrics.lines.isNotEmpty()) {
                    lyricsView.setLyrics(lyrics.lines, lyrics.synced)
                    lyricsView.setPosition(StateHub.state.positionNowMs())
                } else {
                    lyricsView.setEmptyText(getString(R.string.no_lyrics))
                    lyricsView.setLyrics(emptyList(), false)
                }
            }
        }
    }

    companion object {
        private const val TAG = "LX04Player"
        private const val PLAYING_PAGE = 0
        private const val LYRICS_PAGE = 1
        private const val ART_SIZE = 320
        private const val AMBIENT_SIZE = 160
        private const val BLUR_RADIUS = 12
        private const val LYRICS_TIMEOUT_MS = 10_000L
        private const val CONTROL_BAR_HIDE_MS = 5_000L

        @Volatile
        var isVisible = false
            private set

        @Volatile
        private var instance: PlayerActivity? = null

        /** Called from the receiver service on the main thread after a sender disconnect. */
        fun closeIfOpen() {
            instance?.takeIf { !it.isFinishing && !it.isDestroyed }?.finish()
        }
    }
}
