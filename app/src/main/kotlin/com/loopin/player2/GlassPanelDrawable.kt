package com.loopin.player2

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Faithful low-cost port of the legacy WEATHER glass: white alpha fill and a subtle white rim. */
class GlassPanelDrawable(
    private val density: Float,
    private val radiusDp: Float = 30f,
    private val fillAlpha: Int = 0x20,
) : Drawable() {
    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(fillAlpha, 255, 255, 255)
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density.coerceAtLeast(1f)
        color = Color.argb(0x30, 255, 255, 255)
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        val inset = border.strokeWidth / 2f
        rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
    }

    override fun draw(canvas: Canvas) {
        val radius = radiusDp * density
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, border)
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
        border.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        fill.colorFilter = colorFilter
        border.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
