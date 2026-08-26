package com.loopin.player2.core.playback

import com.loopin.player2.core.model.LogLevel
import com.loopin.player2.core.model.PlayerLogger
import com.loopin.player2.core.model.Playlist
import com.loopin.player2.core.model.PlaylistItem
import java.util.concurrent.CopyOnWriteArrayList

class LoopingPlaybackEngine(
    private val itemPlayer: CurrentItemPlayer,
    private val logger: PlayerLogger,
    private val maxRetriesPerItem: Int = 1,
) : PlaybackEngine, CurrentItemPlayer.Callback {
    private val listeners = CopyOnWriteArrayList<(PlaybackSnapshot) -> Unit>()
    private var items: List<PlaylistItem> = emptyList()
    private var currentIndex = -1
    private var retryCount = 0
    private val failedItemIds = linkedSetOf<String>()
    private var released = false

    @Volatile
    override var snapshot: PlaybackSnapshot = PlaybackSnapshot()
        private set

    @Synchronized
    override fun load(playlist: Playlist) {
        check(!released) { "Playback engine is released" }
        itemPlayer.stopAndReleaseCurrent()
        val ordered = playlist.orderedItems()
        items = ordered.filter(PlaylistItem::isValid)
        ordered.filterNot(PlaylistItem::isValid).forEach {
            logger.log(LogLevel.WARN, TAG, "Ignoring invalid local item id=${it.id}")
        }
        currentIndex = -1
        retryCount = 0
        failedItemIds.clear()
        publish(PlaybackSnapshot(playlistId = playlist.id))
    }

    @Synchronized
    override fun start() {
        if (released || items.isEmpty()) {
            publish(snapshot.copy(state = PlaybackState.IDLE, currentItemId = null, currentIndex = -1))
            return
        }
        if (currentIndex < 0) currentIndex = 0
        playCurrent(PlaybackState.PREPARING)
    }

    @Synchronized
    override fun pause() {
        if (snapshot.state == PlaybackState.PLAYING) {
            itemPlayer.pause()
            publish(snapshot.copy(state = PlaybackState.PAUSED))
        }
    }

    @Synchronized
    override fun resume() {
        if (snapshot.state == PlaybackState.PAUSED) {
            itemPlayer.resume()
            publish(snapshot.copy(state = PlaybackState.PLAYING))
        }
    }

    @Synchronized
    override fun stop() {
        itemPlayer.stopAndReleaseCurrent()
        currentIndex = -1
        retryCount = 0
        publish(snapshot.copy(state = PlaybackState.IDLE, currentItemId = null, currentIndex = -1))
    }

    @Synchronized
    override fun release() {
        if (released) return
        released = true
        itemPlayer.release()
        items = emptyList()
        currentIndex = -1
        publish(snapshot.copy(state = PlaybackState.IDLE, currentItemId = null, currentIndex = -1))
        listeners.clear()
    }

    override fun subscribe(listener: (PlaybackSnapshot) -> Unit): AutoCloseable {
        listeners += listener
        listener(snapshot)
        return AutoCloseable { listeners -= listener }
    }

    @Synchronized
    override fun onStarted() {
        if (!released && currentIndex in items.indices) {
            publish(snapshot.copy(state = PlaybackState.PLAYING, lastError = null))
        }
    }

    @Synchronized
    override fun onCompleted() {
        if (released || currentIndex !in items.indices) return
        failedItemIds.remove(items[currentIndex].id)
        retryCount = 0
        publish(snapshot.copy(state = PlaybackState.COMPLETED))
        advanceAndPlay()
    }

    @Synchronized
    override fun onError(error: Throwable) {
        if (released || currentIndex !in items.indices) return
        val failedItem = items[currentIndex]
        logger.log(LogLevel.ERROR, TAG, "Playback failed for item=${failedItem.id}", error)
        itemPlayer.stopAndReleaseCurrent()
        if (retryCount < maxRetriesPerItem) {
            retryCount += 1
            publish(snapshot.copy(state = PlaybackState.RECOVERING, lastError = error.message))
            playCurrent(PlaybackState.RECOVERING)
            return
        }
        retryCount = 0
        failedItemIds += failedItem.id
        if (failedItemIds.size >= items.size) {
            publish(snapshot.copy(state = PlaybackState.ERROR, lastError = "All playlist items failed"))
            logger.log(LogLevel.ERROR, TAG, "All ${items.size} playlist items failed; playback stopped safely")
            return
        }
        advanceAndPlay()
    }

    private fun advanceAndPlay() {
        val previousIndex = currentIndex
        currentIndex = (currentIndex + 1) % items.size
        val loops = snapshot.completedLoops + if (currentIndex <= previousIndex) 1 else 0
        publish(snapshot.copy(completedLoops = loops))
        playCurrent(PlaybackState.PREPARING)
    }

    private fun playCurrent(state: PlaybackState) {
        val item = items[currentIndex]
        publish(
            snapshot.copy(
                state = state,
                currentItemId = item.id,
                currentIndex = currentIndex,
            ),
        )
        itemPlayer.play(item, this)
    }

    private fun publish(value: PlaybackSnapshot) {
        snapshot = value
        listeners.forEach { listener -> runCatching { listener(value) } }
    }

    private companion object { const val TAG = "PlaybackEngine" }
}
