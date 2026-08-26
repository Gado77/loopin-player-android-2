package com.loopin.player2.core.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout

@OptIn(markerClass = [UnstableApi::class])
class PlaybackSurface(context: Context) : FrameLayout(context) {
    private var displayedBitmap: Bitmap? = null
    private val videoView = PlayerView(context).apply {
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        visibility = View.GONE
    }
    private val imageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(Color.BLACK)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        visibility = View.GONE
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(videoView)
        addView(imageView)
    }

    internal fun showVideo(player: Player) {
        imageView.setImageDrawable(null)
        recycleDisplayedBitmap()
        imageView.visibility = View.GONE
        videoView.player = player
        videoView.visibility = View.VISIBLE
    }

    internal fun showImage(bitmap: Bitmap) {
        imageView.setImageDrawable(null)
        recycleDisplayedBitmap()
        displayedBitmap = bitmap
        videoView.player = null
        videoView.visibility = View.GONE
        imageView.setImageBitmap(bitmap)
        imageView.visibility = View.VISIBLE
    }

    internal fun clear() {
        videoView.player = null
        videoView.visibility = View.GONE
        imageView.setImageDrawable(null)
        imageView.visibility = View.GONE
        recycleDisplayedBitmap()
    }

    private fun recycleDisplayedBitmap() {
        displayedBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        displayedBitmap = null
    }
}
