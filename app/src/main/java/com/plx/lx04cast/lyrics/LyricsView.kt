package com.plx.lx04cast.lyrics

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * Full, vertically scrollable lyrics. Unlike the old fixed five-line window, every line is
 * present as a TextView, so long lyrics are readable end to end. The current line is
 * highlighted and followed automatically. A vertical swipe pauses following for a few
 * seconds so the page can be read; a horizontal page swipe never disables it.
 */
class LyricsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val scrollView = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        clipToPadding = false
    }
    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val emptyView = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(0xB3FFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setPadding(24, 0, 24, 0)
    }

    private var lines: List<LyricLine> = emptyList()
    private var lineViews: List<TextView> = emptyList()
    private var synced = false
    private var current = -1
    private var manualScroll = false
    private var emptyText = "暂无歌词"
    private var downX = 0f
    private var downY = 0f
    private var trackingTouch = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val main = Handler(Looper.getMainLooper())

    private val resumeFollow = Runnable {
        manualScroll = false
        if (synced && current in lines.indices) {
            lineViews.getOrNull(current)?.let(::centerOn)
        }
    }

    private val normalTypeface = Typeface.DEFAULT
    private val currentTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    init {
        addView(
            scrollView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        scrollView.addView(
            container,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        addView(
            emptyView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.CENTER
            }
        )
        scrollView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    trackingTouch = true
                    main.removeCallbacks(resumeFollow)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (trackingTouch) {
                        val dx = abs(event.x - downX)
                        val dy = abs(event.y - downY)
                        // Only a deliberate vertical drag means "let me read this part".
                        // Swiping horizontally to this page must keep auto-follow enabled.
                        if (dy > touchSlop && dy > dx * 1.2f) {
                            manualScroll = true
                            trackingTouch = false
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    trackingTouch = false
                    if (manualScroll) {
                        main.removeCallbacks(resumeFollow)
                        main.postDelayed(resumeFollow, AUTO_FOLLOW_RESUME_MS)
                    }
                }
            }
            false
        }
    }

    fun setEmptyText(text: String) {
        emptyText = text
        if (lines.isEmpty()) {
            emptyView.text = text
            emptyView.visibility = VISIBLE
        }
    }

    fun setLyrics(next: List<LyricLine>, isSynced: Boolean) {
        lines = next
        synced = isSynced
        current = -1
        manualScroll = false
        trackingTouch = false
        main.removeCallbacks(resumeFollow)
        container.removeAllViews()
        lineViews = next.map { line ->
            TextView(context).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                includeFontPadding = false
                setLineSpacing(4f * density, 1f)
                setPadding(0, (10f * density).toInt(), 0, (10f * density).toInt())
                text = line.text
                setTextColor(0xB3FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f)
            }.also { container.addView(it) }
        }
        emptyView.text = emptyText
        emptyView.visibility = if (lineViews.isEmpty()) VISIBLE else GONE
        scrollView.scrollTo(0, 0)
    }

    fun setPosition(ms: Long) {
        if (!synced || lines.isEmpty()) return
        val index = indexFor(ms)
        if (index == current) return
        lineViews.getOrNull(current)?.let { styleLine(it, false) }
        current = index
        lineViews.getOrNull(index)?.let {
            styleLine(it, true)
            if (!manualScroll) centerOn(it)
        }
    }

    private fun styleLine(view: TextView, isCurrent: Boolean) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isCurrent) 32f else 23f)
        view.setTextColor(if (isCurrent) 0xFFFFFFFF.toInt() else 0xB3FFFFFF.toInt())
        view.typeface = if (isCurrent) currentTypeface else normalTypeface
    }

    private fun centerOn(view: TextView) {
        scrollView.post {
            if (manualScroll) return@post
            val target = view.top - (scrollView.height - view.height) / 2
            scrollView.smoothScrollTo(0, target.coerceAtLeast(0))
        }
    }

    private fun indexFor(ms: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var result = 0
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lines[middle].timeMs <= ms) {
                result = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return result
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(resumeFollow)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val AUTO_FOLLOW_RESUME_MS = 5_000L
    }
}
