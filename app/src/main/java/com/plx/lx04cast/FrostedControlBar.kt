package com.plx.lx04cast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import kotlin.math.max

/**
 * The floating player control panel. Its backdrop is a crop of the same heavily blurred
 * cover image used for the page background, so the panel reads as frosted glass instead
 * of a flat semi-transparent rectangle.
 */
class FrostedControlBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var backdrop: Bitmap? = null
        set(value) {
            if (field === value) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    // 56dp tall; a 28dp radius gives a clean capsule without the odd white rim the
    // previous stroked outline drew around every corner.
    private val cornerRadius = 28f * density
    private val path = Path()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint()
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sheen: Shader? = null
    private val source = Rect()
    private val destination = RectF()

    init {
        setWillNotDraw(false)
        clipToOutline = true
        elevation = 10f * density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        path.reset()
        path.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), cornerRadius, cornerRadius, Path.Direction.CW)
        sheen = LinearGradient(
            0f, 0f, 0f, height * 0.62f,
            0x2EFFFFFF, 0x00FFFFFF,
            Shader.TileMode.CLAMP
        )
        sheenPaint.shader = sheen
    }

    override fun onDraw(canvas: Canvas) {
        val width = width
        val height = height
        if (width == 0 || height == 0) return
        canvas.save()
        canvas.clipPath(path)

        val image = backdrop
        if (image != null && !image.isRecycled && image.width > 0 && image.height > 0) {
            val scale = max(width.toFloat() / image.width, height.toFloat() / image.height)
            val sourceWidth = width / scale
            val sourceHeight = height / scale
            val left = (image.width - sourceWidth) / 2f
            val top = (image.height - sourceHeight) / 2f
            source.set(
                left.toInt(), top.toInt(),
                (left + sourceWidth).toInt(), (top + sourceHeight).toInt()
            )
            destination.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(image, source, destination, bitmapPaint)
        } else {
            canvas.drawColor(0xFF25272C.toInt())
        }
        canvas.drawColor(0x5C000000)
        canvas.drawRect(0f, 0f, width.toFloat(), height * 0.62f, sheenPaint)
        canvas.restore()
    }
}
