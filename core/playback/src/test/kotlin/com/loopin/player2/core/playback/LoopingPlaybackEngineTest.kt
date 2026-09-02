package com.loopin.player2.core.playback

import com.loopin.player2.core.model.LocalAvailability
import com.loopin.player2.core.model.LogLevel
import com.loopin.player2.core.model.MediaReference
import com.loopin.player2.core.model.MediaType
import com.loopin.player2.core.model.PlayableMedia
import com.loopin.player2.core.model.PlayerLogger
import com.loopin.player2.core.model.Playlist
import com.loopin.player2.core.model.PlaylistItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoopingPlaybackEngineTest {
    private val player = FakeItemPlayer()
    private val engine = LoopingPlaybackEngine(player, SilentLogger)

    @Test
    fun `advances and loops in deterministic order`() {
        engine.load(playlist(item("second", 2), item("first", 1)))
        engine.start()
        assertEquals("first", engine.snapshot.currentItemId)
        player.complete()
        assertEquals("second", engine.snapshot.currentItemId)
        player.complete()
        assertEquals("first", engine.snapshot.currentItemId)
        assertEquals(1, engine.snapshot.completedLoops)
    }

    @Test
    fun `empty playlist remains idle`() {
        engine.load(playlist())
        engine.start()
        assertEquals(PlaybackState.IDLE, engine.snapshot.state)
        assertTrue(player.played.isEmpty())
    }

    @Test
    fun `invalid item is skipped`() {
        engine.load(playlist(item("bad", 0, local = false), item("good", 1)))
        engine.start()
        assertEquals(listOf("good"), player.played)
    }

    @Test
    fun `error retries once then skips and continues`() {
        engine.load(playlist(item("bad", 0), item("good", 1)))
        engine.start()
        player.fail()
        assertEquals(PlaybackState.RECOVERING, engine.snapshot.state)
        assertEquals(listOf("bad", "bad"), player.played)
        player.fail()
        assertEquals("good", engine.snapshot.currentItemId)
        assertEquals(PlaybackState.PREPARING, engine.snapshot.state)
    }

    @Test
    fun `all failed items enter safe error state`() {
        engine.load(playlist(item("only", 0)))
        engine.start()
        player.fail()
        player.fail()
        assertEquals(PlaybackState.ERROR, engine.snapshot.state)
        assertEquals("All playlist items failed", engine.snapshot.lastError)
    }

    @Test
    fun `state machine publishes preparing playing paused and completed`() {
        val states = mutableListOf<PlaybackState>()
        engine.subscribe { states += it.state }
        engine.load(playlist(item("one", 0), item("two", 1)))
        engine.start()
        player.started()
        engine.pause()
        engine.resume()
        player.complete()
        assertTrue(states.containsAll(listOf(PlaybackState.PREPARING, PlaybackState.PLAYING, PlaybackState.PAUSED, PlaybackState.COMPLETED)))
    }

    @Test
    fun `release frees current player and returns idle`() {
        engine.load(playlist(item("one", 0)))
        engine.start()
        engine.release()
        assertTrue(player.released)
        assertEquals(PlaybackState.IDLE, engine.snapshot.state)
    }

    @Test
    fun `committed playlist waits for current item completion`() {
        engine.load(Playlist("old", 1, 1, listOf(item("old-a", 0), item("old-b", 1))))
        engine.start()
        engine.replaceAfterCurrent(Playlist("remote", 1, 2, listOf(item("remote-a", 0))))
        assertEquals("old-a", engine.snapshot.currentItemId)
        player.complete()
        assertEquals("remote", engine.snapshot.playlistId)
        assertEquals("remote-a", engine.snapshot.currentItemId)
        assertEquals(listOf("old-a", "remote-a"), player.played)
    }

    private fun playlist(vararg items: PlaylistItem) = Playlist("local", 1, 1, items.toList())

    private fun item(id: String, order: Int, local: Boolean = true) = PlaylistItem(
        id,
        order,
        PlayableMedia(
            id,
            MediaType.VIDEO,
            MediaReference(if (local) "file:///$id.mp4" else null),
            localAvailability = if (local) LocalAvailability.AVAILABLE else LocalAvailability.MISSING,
        ),
    )

    private class FakeItemPlayer : CurrentItemPlayer {
        val played = mutableListOf<String>()
        var callback: CurrentItemPlayer.Callback? = null
        var released = false

        override fun play(item: PlaylistItem, callback: CurrentItemPlayer.Callback) {
            played += item.id
            this.callback = callback
        }
        override fun pause() = Unit
        override fun resume() = Unit
        override fun stopAndReleaseCurrent() = Unit
        override fun release() { released = true }
        fun started() = callback!!.onStarted()
        fun complete() = callback!!.onCompleted()
        fun fail() = callback!!.onError(IllegalStateException("broken"))
    }

    private object SilentLogger : PlayerLogger {
        override fun log(level: LogLevel, tag: String, message: String, error: Throwable?) = Unit
    }
}
