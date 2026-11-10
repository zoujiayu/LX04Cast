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

/**
 * Draws the album artwork itself instead of using ImageView + clipToOutline. Outline
 * clipping is unreliable without hardware acceleration (this app disables it to save
 * the ~7 MB EGL/GL allocation on the LX04), while a Path clip works on software canvases.
 */
class ArtworkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var artwork: Bitmap? = null
    private val density = resources.displayMetrics.density
    private val radius = 12f * density
    private val frame = RectF()
    private val borderRect = RectF()
    private val source = Rect()
    private val fitted = RectF()
    private val path = Path()
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1F28.toInt()
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = 90
    }
    private val fillTintPaint = Paint().apply { color = 0x66000000 }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x4DFFFFFF
    }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shadeShader: Shader? = null
    private var sheenShader: Shader? = null
    private val placeholder = context.getDrawable(R.drawable.ic_cast)

    init {
        // Gives the cover a lifted, framed feel over the blurred backdrop. The outline is
        // also used for the platform elevation shadow, so this remains cheap (no extra
        // full-size bitmap or RenderScript pass).
        elevation = 14f * density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
    }

    fun setArtwork(next: Bitmap?) {
        if (artwork === next) return
        artwork = next
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        frame.set(0f, 0f, width.toFloat(), height.toFloat())
        borderRect.set(
            0.5f * density, 0.5f * density,
            width - 0.5f * density, height - 0.5f * density
        )
        shadeShader = LinearGradient(
            0f, height * 0.62f, 0f, height.toFloat(),
            0x00000000, 0x3D000000, Shader.TileMode.CLAMP
        )
        shadePaint.shader = shadeShader
        sheenShader = LinearGradient(
            0f, 0f, width * 0.75f, height * 0.55f,
            0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        sheenPaint.shader = sheenShader
        val iconSize = (minOf(width, height) * 0.28f).toInt()
        val left = (width - iconSize) / 2
        val top = (height - iconSize) / 2
        placeholder?.setBounds(left, top, left + iconSize, top + iconSize)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRoundRect(frame, radius, radius, backgroundPaint)
        val art = artwork
        if (art == null || art.isRecycled) {
            placeholder?.let {
                it.alpha = 70
                it.draw(canvas)
            }
            return
        }
        path.reset()
        path.addRoundRect(frame, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)

        // Show the whole image, never a centre-cropped part of it. For a non-square
        // cover the letterbox area is filled with a dimmed centre-crop of the same
        // art, so the frame still looks complete.
        val viewRatio = width.toFloat() / height.toFloat()
        val artRatio = art.width.toFloat() / art.height.toFloat()
        if (artRatio > viewRatio) {
            val cropWidth = (art.height * viewRatio).toInt()
            val inset = (art.width - cropWidth) / 2
            source.set(inset, 0, inset + cropWidth, art.height)
        } else {
            val cropHeight = (art.width / viewRatio).toInt()
            val inset = (art.height - cropHeight) / 2
            source.set(0, inset, art.width, inset + cropHeight)
        }
        if (kotlin.math.abs(artRatio - viewRatio) > 0.001f) {
            canvas.drawBitmap(art, source, frame, fillPaint)
            canvas.drawRect(frame, fillTintPaint)
        }
        val scale = minOf(width.toFloat() / art.width, height.toFloat() / art.height)
        val drawWidth = art.width * scale
        val drawHeight = art.height * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        fitted.set(left, top, left + drawWidth, top + drawHeight)
        canvas.drawBitmap(art, null, fitted, bitmapPaint)
        canvas.drawRect(frame, shadePaint)
        canvas.drawRect(frame, sheenPaint)
        canvas.restore()

        canvas.drawRoundRect(borderRect, radius, radius, borderPaint)
    }
}
