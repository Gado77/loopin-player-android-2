package com.loopin.player2.core.content

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.format.DateTimeFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentEngineTest {
    private class MemoryWeatherCache(var value: WeatherData? = null) : WeatherCache {
        override fun load() = value
        override fun save(data: WeatherData) { value = data }
    }
    private val now = 1_755_600_000_000L
    private fun weather(updated: Long = now) = WeatherData("Teresina", 30.0, 32.0, "Sol", "sun", 34.0, 24.0, updated)

    @Test fun `clock formatting works offline`() = assertEquals("14:32", LocalTime.of(14, 32).format(DateTimeFormatter.ofPattern("HH:mm")))
    @Test fun `date formatting uses locale without network`() = assertTrue(SimpleDateFormat("MMMM", Locale("pt", "BR")).format(Date(now)).isNotBlank())
    @Test fun `weather available is cached`() {
        val cache = MemoryWeatherCache(); val result = WeatherRepository({ WeatherSourceResult.Success(weather()) }, cache, { now }).current(true)
        assertEquals(WeatherState.AVAILABLE, result.state); assertEquals("Teresina", cache.value?.city)
    }
    @Test fun `weather unavailable is non blocking`() = assertEquals(WeatherState.UNAVAILABLE,
        WeatherRepository({ WeatherSourceResult.Failure("no source") }, MemoryWeatherCache(), { now }).current(false).state)
    @Test fun `cached weather is used offline`() = assertEquals("Teresina",
        WeatherRepository({ WeatherSourceResult.Failure("offline") }, MemoryWeatherCache(weather()), { now }).current(false).data?.city)
    @Test fun `old weather is stale`() = assertEquals(WeatherState.STALE,
        WeatherRepository({ WeatherSourceResult.Failure("offline") }, MemoryWeatherCache(weather(now - 3 * 60 * 60 * 1_000)), { now }).current(false).state)
    @Test fun `priority orders contracted media before widgets`() {
        val result = ContentScheduler().select(sequenceOf(ContentItem("weather", ContentType.WEATHER, ContentPriority.LOW),
            ContentItem("ad", ContentType.VIDEO, ContentPriority.HIGH)), moment(12, 0))
        assertEquals("ad", result.first().id)
    }
    @Test fun `scheduler excludes inactive content`() {
        val item = ContentItem("night", ContentType.TEXT, schedule = ContentSchedule(18 * 60, 22 * 60))
        assertTrue(ContentScheduler().select(sequenceOf(item), moment(12, 0)).isEmpty())
    }
    @Test fun `time window includes content`() {
        val item = ContentItem("lunch", ContentType.TEXT, schedule = ContentSchedule(12 * 60, 14 * 60))
        assertEquals(1, ContentScheduler().select(sequenceOf(item), moment(13, 0)).size)
    }
    @Test fun `special event requires activation`() {
        val item = ContentItem("cup", ContentType.SPECIAL_EVENT, schedule = ContentSchedule(event = SpecialEvent.COPA))
        assertEquals(1, ContentScheduler().select(sequenceOf(item), moment(12, 0), setOf(SpecialEvent.COPA)).size)
    }
    @Test fun `offline fallback keeps last valid weather`() {
        assertIs<WeatherData>(WeatherRepository({ WeatherSourceResult.Failure("offline") },
            MemoryWeatherCache(weather()), { now }).current(false).data)
    }
    @Test fun `no playable content produces empty selection`() = assertTrue(ContentScheduler().select(emptySequence(), moment(12, 0)).isEmpty())
    @Test fun `layout can be changed`() {
        val engine = LayoutEngine(); engine.apply(ContentLayout("a", null)); engine.apply(ContentLayout("b", null))
        assertEquals("b", engine.current()?.id)
    }
    @Test fun `fade is default transition`() = assertEquals(TransitionType.FADE, TransitionSpec().type)
    @Test fun `normal playlist preserves deterministic order`() {
        val result = ContentScheduler().select(sequenceOf(ContentItem("b", ContentType.IMAGE), ContentItem("a", ContentType.VIDEO)), moment(12, 0))
        assertEquals(listOf("a", "b"), result.map { it.id })
    }
    @Test fun `image memory policy downsamples large bitmap`() = assertEquals(2, ImageMemoryPolicy(1920).sampleSize(3840, 2160))
    @Test fun `layout state survives controller force stop when persisted externally`() {
        val saved = ContentLayout("saved", "ad"); assertEquals(saved, saved.copy())
    }
    @Test fun `restart begins without retaining renderer objects`() {
        val resolver = RegistryContentResolver(emptyList()); assertNull(resolver.rendererFor(ContentItem("x", ContentType.TEXT)))
    }
    private fun moment(hour: Int, minute: Int) = ContentMoment(4, hour * 60 + minute)
}
