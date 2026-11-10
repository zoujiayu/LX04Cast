package com.plx.lx04cast

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * Dependency-free two-page pager. The pages live in one horizontal row over a shared,
 * stationary blurred background; swiping moves only the transparent page content.
 */
class PagerScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {

    private var pageWidth = 0
    private var pageCount = 2
    private var pendingFling = 0

    var currentPage = 0
        private set

    var onPageChanged: ((Int) -> Unit)? = null

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = false
        isSmoothScrollingEnabled = true
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width == 0 || width == oldWidth) return
        pageWidth = width
        val row = getChildAt(0) as? LinearLayout ?: return
        pageCount = row.childCount.coerceAtLeast(1)
        for (index in 0 until row.childCount) {
            val page = row.getChildAt(index)
            val params = page.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(width, height)
            params.width = width
            page.layoutParams = params
        }
        // HorizontalScrollView is a FrameLayout: keep its child's FrameLayout.LayoutParams
        // type, only change the width to fit all pages.
        row.layoutParams?.let { params ->
            if (params.width != width * pageCount) {
                params.width = width * pageCount
                row.layoutParams = params
            }
        }
        post { scrollTo(currentPage.coerceIn(0, pageCount - 1) * width, 0) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> pendingFling = 0
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val handled = super.onTouchEvent(event)
                if (pageWidth > 0) {
                    var target = currentPage
                    when {
                        pendingFling > 0 -> target = currentPage + 1
                        pendingFling < 0 -> target = currentPage - 1
                        scrollX > currentPage * pageWidth + pageWidth / 2 -> target = currentPage + 1
                        scrollX < currentPage * pageWidth - pageWidth / 2 -> target = currentPage - 1
                    }
                    val next = target.coerceIn(0, pageCount - 1)
                    if (next != currentPage) {
                        currentPage = next
                        smoothScrollTo(next * pageWidth, 0)
                        onPageChanged?.invoke(next)
                    } else if (scrollX != currentPage * pageWidth) {
                        smoothScrollTo(currentPage * pageWidth, 0)
                    }
                }
                pendingFling = 0
                return handled
            }
        }
        return super.onTouchEvent(event)
    }

    /** Suppress the framework's free fling; a page release always snaps to a page. */
    override fun fling(velocityX: Int) {
        pendingFling = velocityX
    }

    fun goToPage(page: Int) {
        val target = page.coerceIn(0, pageCount - 1)
        if (pageWidth == 0) {
            post { goToPage(target) }
            return
        }
        if (target == currentPage) return
        currentPage = target
        smoothScrollTo(target * pageWidth, 0)
        onPageChanged?.invoke(target)
    }
}
