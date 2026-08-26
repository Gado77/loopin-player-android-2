package com.loopin.player2.core.playback

import com.loopin.player2.core.content.*
import com.loopin.player2.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DynamicPlaylistPlaybackTest {
    @Test fun `normal media advances to normal media`() = assertSequence(listOf(normal("a", 0), normal("b", 1)), "a", "b")
    @Test fun `normal media advances to weather`() = assertSequence(listOf(normal("a", 0), weather("w", 1)), "a", "w")
    @Test fun `weather advances to normal media`() = assertSequence(listOf(weather("w", 0), normal("a", 1)), "w", "a")
    @Test fun `multiple weather items preserve positions`() = assertOrder(normal("a", 0), weather("w1", 1), normal("b", 2), weather("w2", 3))
    @Test fun `weather can be first item`() = assertOrder(weather("w", 0), normal("a", 1))
    @Test fun `weather can be last item`() = assertOrder(normal("a", 0), weather("w", 1))
    @Test fun `playlist without weather remains unchanged`() = assertOrder(normal("a", 0), normal("b", 1))
    @Test fun `playlist can contain only weather`() = assertOrder(weather("w1", 0), weather("w2", 1))
    @Test fun `clear condition selects day background`() = assertEquals(WeatherBackground.CLEAR_DAY, WeatherBackgroundSelector().select("Ensolarado", false))
    @Test fun `clear condition selects night background`() = assertEquals(WeatherBackground.CLEAR_NIGHT, WeatherBackgroundSelector().select("Céu limpo", true))
    @Test fun `offline weather uses cached value`() {
        val cached = weatherData(); val repository = WeatherRepository({ WeatherSourceResult.Failure("offline") }, Cache(cached), { cached.updatedAtEpochMs })
        assertEquals("Cidade", repository.current(false).data?.city)
    }
    @Test fun `missing weather returns unavailable without blocking playlist`() {
        val repository = WeatherRepository({ WeatherSourceResult.Failure("offline") }, Cache(null))
        assertEquals(WeatherState.UNAVAILABLE, repository.current(false).state)
    }
    @Test fun `completion returns exactly to next programmed item`() = assertSequence(listOf(normal("a", 0), weather("w", 1), normal("b", 2)), "a", "w", "b")
    @Test fun `playlist input is not mutated during playback`() {
        val items = listOf(normal("a", 2), weather("w", 1)); val original = items.toList(); engine(items).second.start()
        assertEquals(original, items)
    }
    @Test fun `player restart begins from first programmed item`() {
        val items = listOf(normal("a", 0), weather("w", 1)); val first = engine(items).second.apply { start() }.snapshot.currentItemId
        val restarted = engine(items).second.apply { start() }.snapshot.currentItemId
        assertEquals(first, restarted)
    }
    @Test fun `dynamic playlist functions without internet dependency`() {
        assertTrue(weather("w", 0).isValid()); assertOrder(weather("w", 0), normal("a", 1))
    }

    private fun assertSequence(items: List<PlaylistItem>, vararg expected: String) {
        val (player, engine) = engine(items); engine.start()
        val actual = mutableListOf(engine.snapshot.currentItemId!!)
        repeat(expected.size - 1) { player.complete(); actual += engine.snapshot.currentItemId!! }
        assertEquals(expected.toList(), actual)
    }
    private fun assertOrder(vararg items: PlaylistItem) {
        val (player, engine) = engine(items.toList()); engine.start(); val actual = mutableListOf<String>()
        repeat(items.size) { actual += engine.snapshot.currentItemId!!; player.complete() }
        assertEquals(items.sortedBy { it.order }.map { it.id }, actual)
    }
    private fun engine(items: List<PlaylistItem>): Pair<FakePlayer, LoopingPlaybackEngine> {
        val player = FakePlayer(); val engine = LoopingPlaybackEngine(player, Logger)
        engine.load(Playlist("p", 1, 1, items)); return player to engine
    }
    private fun normal(id: String, order: Int) = PlaylistItem(id, order, PlayableMedia(id, MediaType.VIDEO,
        MediaReference("file:///$id.mp4"), localAvailability = LocalAvailability.AVAILABLE))
    private fun weather(id: String, order: Int) = PlaylistItem(id, order,
        DynamicMediaContent(DynamicContentType.WEATHER, 5_000, mapOf("city" to "Cidade")))
    private fun weatherData() = WeatherData("Cidade", 28.0, 29.0, "Limpo", "sun", 31.0, 22.0, 1)
    private class Cache(private var data: WeatherData?) : WeatherCache { override fun load() = data; override fun save(data: WeatherData) { this.data = data } }
    private class FakePlayer : CurrentItemPlayer {
        private var callback: CurrentItemPlayer.Callback? = null
        override fun play(item: PlaylistItem, callback: CurrentItemPlayer.Callback) { this.callback = callback }
        override fun pause() = Unit; override fun resume() = Unit; override fun stopAndReleaseCurrent() = Unit; override fun release() = Unit
        fun complete() = callback!!.onCompleted()
    }
    private object Logger : PlayerLogger { override fun log(level: LogLevel, tag: String, message: String, error: Throwable?) = Unit }
}
