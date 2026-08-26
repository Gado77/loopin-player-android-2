package com.loopin.player2.core.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.loopin.player2.core.content.*
import com.loopin.player2.core.model.LogLevel
import com.loopin.player2.core.model.PlayerLogger
import com.loopin.player2.core.model.PlaylistItem
import java.util.Calendar

interface DynamicContentSurface {
    fun showWeather(snapshot: WeatherSnapshot, background: WeatherBackground)
    fun hideWeather()
}
fun interface WeatherBackgroundMediaResolver { fun uriFor(background: WeatherBackground): String }

/** Maps a condition to one local medium while guaranteeing a deterministic offline fallback. */
class WeatherBackgroundCatalog<T>(
    private val media: Map<WeatherBackground, T>,
    private val fallback: T,
) {
    fun resolve(background: WeatherBackground): T = media[background] ?: fallback
}

class WeatherItemPlayer(
    context: Context,
    private val surface: PlaybackSurface,
    private val contentView: DynamicContentSurface,
    private val weather: WeatherProvider,
    private val backgrounds: WeatherBackgroundMediaResolver,
    private val online: () -> Boolean,
    private val logger: PlayerLogger,
) : CurrentItemPlayer {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var completion: Runnable? = null
    private var callback: CurrentItemPlayer.Callback? = null
    private var remainingMs = 0L
    private var deadline = 0L
    private var started = false

    override fun play(item: PlaylistItem, callback: CurrentItemPlayer.Callback) {
        stopAndReleaseCurrent()
        val dynamic = item.dynamic ?: return callback.onError(IllegalArgumentException("Missing dynamic content"))
        val rawSnapshot = weather.current(online())
        val configuredCity = dynamic.configuration["city"]?.takeIf(String::isNotBlank)
        val weatherData = rawSnapshot.data
        val snapshot = if (configuredCity != null && weatherData != null)
            rawSnapshot.copy(data = weatherData.copy(city = configuredCity)) else rawSnapshot
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val background = WeatherBackgroundSelector().select(snapshot.data?.condition.orEmpty(), hour !in 6..17)
        contentView.showWeather(snapshot, background); logWeather(snapshot)
        this.callback = callback; remainingMs = dynamic.durationMs
        val exo = ExoPlayer.Builder(appContext).build().also { player = it }
        exo.repeatMode = Player.REPEAT_MODE_ONE
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) startOnce(callback)
            }
            override fun onPlayerError(error: PlaybackException) {
                logger.log(LogLevel.WARN, "WeatherItemPlayer", "Weather background unavailable; continuing with static fallback", error)
                startOnce(callback)
            }
        })
        surface.showVideo(exo); exo.setMediaItem(MediaItem.fromUri(Uri.parse(backgrounds.uriFor(background))))
        exo.playWhenReady = true; exo.prepare()
    }
    private fun startOnce(callback: CurrentItemPlayer.Callback) {
        if (started) return; started = true; callback.onStarted(); scheduleCompletion()
    }
    override fun pause() {
        player?.pause(); completion?.let(handler::removeCallbacks)
        if (deadline > 0) remainingMs = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)
    }
    override fun resume() { player?.play(); if (started) scheduleCompletion() }
    override fun stopAndReleaseCurrent() {
        completion?.let(handler::removeCallbacks); completion = null; callback = null
        player?.release(); player = null; started = false; deadline = 0
        contentView.hideWeather(); surface.clear()
    }
    override fun release() = stopAndReleaseCurrent()
    private fun scheduleCompletion() {
        val task = Runnable { val completed = callback; stopAndReleaseCurrent(); completed?.onCompleted() }
        completion = task; deadline = SystemClock.elapsedRealtime() + remainingMs; handler.postDelayed(task, remainingMs)
    }
    private fun logWeather(snapshot: WeatherSnapshot) {
        val event = when (snapshot.state) {
            WeatherState.AVAILABLE -> ContentLogEvent.WEATHER_UPDATED
            WeatherState.STALE -> ContentLogEvent.WEATHER_STALE
            WeatherState.UNAVAILABLE -> ContentLogEvent.WEATHER_UNAVAILABLE
            WeatherState.LOADING -> return
        }
        logger.log(LogLevel.INFO, "WeatherItemPlayer", event)
    }
}
