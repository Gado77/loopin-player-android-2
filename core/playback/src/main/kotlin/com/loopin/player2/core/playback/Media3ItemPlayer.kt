package com.loopin.player2.core.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.loopin.player2.core.model.MediaType
import com.loopin.player2.core.model.PlaylistItem
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@UnstableApi
class Media3ItemPlayer(
    context: Context,
    private val surface: PlaybackSurface,
) : CurrentItemPlayer {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val decoderExecutor = ThreadPoolExecutor(
        0,
        1,
        5L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(),
    )
    private var player: ExoPlayer? = null
    private var imageDecode: Future<*>? = null
    private var imageCompletion: Runnable? = null
    private var imageDeadlineMs = 0L
    private var imageRemainingMs = 0L
    private var generation = 0L

    override fun play(item: PlaylistItem, callback: CurrentItemPlayer.Callback) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Playback must run on the main thread" }
        stopAndReleaseCurrent()
        require(item.dynamic == null) { "Dynamic content requires a dynamic renderer" }
        val token = generation
        when (item.media.type) {
            MediaType.VIDEO -> playVideo(item, token, callback)
            MediaType.IMAGE -> playImage(item, token, callback)
        }
    }

    private fun playVideo(item: PlaylistItem, token: Long, callback: CurrentItemPlayer.Callback) {
        val uri = item.media.reference.playbackUri() ?: return callback.onError(IllegalArgumentException("Missing local URI"))
        val exoPlayer = ExoPlayer.Builder(applicationContext)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(2_000, 10_000, 500, 1_000)
                    .build(),
            )
            .build()
        player = exoPlayer
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (token != generation) return
                when (playbackState) {
                    Player.STATE_READY -> callback.onStarted()
                    Player.STATE_ENDED -> complete(token, callback)
                    Player.STATE_IDLE, Player.STATE_BUFFERING -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                fail(token, callback, error)
            }
        })
        surface.showVideo(exoPlayer)
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.playWhenReady = true
        exoPlayer.prepare()
    }

    private fun playImage(item: PlaylistItem, token: Long, callback: CurrentItemPlayer.Callback) {
        val uri = item.media.reference.playbackUri() ?: return callback.onError(IllegalArgumentException("Missing local URI"))
        val durationMs = item.media.durationMs ?: return callback.onError(IllegalArgumentException("Missing image duration"))
        imageDecode = decoderExecutor.submit {
            val result = runCatching { decodeSampled(Uri.parse(uri)) }
            mainHandler.post {
                if (token != generation) {
                    result.getOrNull()?.recycle()
                    return@post
                }
                result.fold(
                    onSuccess = { bitmap ->
                        surface.showImage(bitmap)
                        callback.onStarted()
                        imageRemainingMs = durationMs
                        val completion = Runnable { complete(token, callback) }
                        imageCompletion = completion
                        scheduleImageCompletion(completion)
                    },
                    onFailure = { error -> fail(token, callback, error) },
                )
            }
        }
    }

    override fun pause() {
        player?.pause()
        imageCompletion?.let(mainHandler::removeCallbacks)
        if (imageDeadlineMs > 0L) {
            imageRemainingMs = (imageDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
        }
    }

    override fun resume() {
        player?.play()
        imageCompletion?.let(::scheduleImageCompletion)
    }

    override fun stopAndReleaseCurrent() {
        generation += 1
        mainHandler.removeCallbacksAndMessages(null)
        imageDecode?.cancel(true)
        imageDecode = null
        imageCompletion = null
        imageDeadlineMs = 0L
        imageRemainingMs = 0L
        player?.release()
        player = null
        surface.clear()
    }

    override fun release() {
        stopAndReleaseCurrent()
        decoderExecutor.shutdownNow()
    }

    private fun complete(token: Long, callback: CurrentItemPlayer.Callback) {
        if (token != generation) return
        stopAndReleaseCurrent()
        callback.onCompleted()
    }

    private fun fail(token: Long, callback: CurrentItemPlayer.Callback, error: Throwable) {
        if (token != generation) return
        stopAndReleaseCurrent()
        callback.onError(error)
    }

    private fun scheduleImageCompletion(completion: Runnable) {
        imageDeadlineMs = SystemClock.elapsedRealtime() + imageRemainingMs
        mainHandler.postDelayed(completion, imageRemainingMs)
    }

    private fun decodeSampled(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        applicationContext.contentResolver.openInputStream(uri).use { input ->
            checkNotNull(input) { "Cannot open image URI" }
            BitmapFactory.decodeStream(input, null, bounds)
        }
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported image" }
        val targetWidth = surface.width.takeIf { it > 0 } ?: 1280
        val targetHeight = surface.height.takeIf { it > 0 } ?: 720
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth && bounds.outHeight / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return applicationContext.contentResolver.openInputStream(uri).use { input ->
            checkNotNull(input) { "Cannot reopen image URI" }
            checkNotNull(BitmapFactory.decodeStream(input, null, options)) { "Image decode failed" }
        }
    }
}
