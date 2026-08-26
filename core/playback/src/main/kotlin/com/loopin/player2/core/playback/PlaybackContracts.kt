package com.loopin.player2.core.playback

import com.loopin.player2.core.model.Playlist
import com.loopin.player2.core.model.PlaylistItem

enum class PlaybackState { IDLE, PREPARING, PLAYING, PAUSED, COMPLETED, ERROR, RECOVERING }

data class PlaybackSnapshot(
    val state: PlaybackState = PlaybackState.IDLE,
    val playlistId: String? = null,
    val currentItemId: String? = null,
    val currentIndex: Int = -1,
    val completedLoops: Long = 0,
    val lastError: String? = null,
)

interface PlaybackEngine {
    val snapshot: PlaybackSnapshot
    fun load(playlist: Playlist)
    fun start()
    fun pause()
    fun resume()
    fun stop()
    fun release()
    fun subscribe(listener: (PlaybackSnapshot) -> Unit): AutoCloseable
}

interface CurrentItemPlayer {
    interface Callback {
        fun onStarted()
        fun onCompleted()
        fun onError(error: Throwable)
    }

    fun play(item: PlaylistItem, callback: Callback)
    fun pause()
    fun resume()
    fun stopAndReleaseCurrent()
    fun release()
}
