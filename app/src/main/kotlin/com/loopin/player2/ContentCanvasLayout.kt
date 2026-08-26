package com.loopin.player2

import android.content.Context
import android.widget.FrameLayout
import com.loopin.player2.core.content.ContentPresentation

/** Presentation-only canvas; it never rotates the Activity or changes playback state. */
class ContentCanvasLayout(
    context: Context,
    val presentation: ContentPresentation,
) : FrameLayout(context) {
    constructor(context: Context) : this(context, ContentPresentation())

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        if (availableWidth <= 0 || availableHeight <= 0) return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val canvas = presentation.fitInside(availableWidth, availableHeight)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(canvas.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(canvas.height, MeasureSpec.EXACTLY),
        )
    }
}
