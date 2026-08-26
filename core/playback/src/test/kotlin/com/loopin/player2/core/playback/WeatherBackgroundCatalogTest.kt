package com.loopin.player2.core.playback

import com.loopin.player2.core.content.WeatherBackground
import com.loopin.player2.core.content.WeatherBackgroundSelector
import kotlin.test.Test
import kotlin.test.assertEquals

class WeatherBackgroundCatalogTest {
    private val catalog = WeatherBackgroundCatalog(
        mapOf(
            WeatherBackground.CLEAR_DAY to "sun.mp4",
            WeatherBackground.RAIN_DAY to "rain.mp4",
            WeatherBackground.CLOUDY_NIGHT to "cloudy-night.mp4",
        ),
        "fallback.mp4",
    )

    @Test fun `sunny day resolves prepared local video`() =
        assertEquals("sun.mp4", catalog.resolve(WeatherBackground.CLEAR_DAY))

    @Test fun `rain resolves a different prepared local video`() =
        assertEquals("rain.mp4", catalog.resolve(WeatherBackground.RAIN_DAY))

    @Test fun `unmapped condition resolves offline fallback`() =
        assertEquals("fallback.mp4", catalog.resolve(WeatherBackground.STORM))

    @Test fun `selector and catalog resolve cloudy night`() = assertEquals(
        "cloudy-night.mp4",
        catalog.resolve(WeatherBackgroundSelector().select("Nublado", true)),
    )

    @Test fun `changing weather changes selected video without retaining prior value`() {
        assertEquals("sun.mp4", catalog.resolve(WeatherBackground.CLEAR_DAY))
        assertEquals("rain.mp4", catalog.resolve(WeatherBackground.RAIN_DAY))
    }

    @Test fun `explicit fallback condition is deterministic`() =
        assertEquals("fallback.mp4", catalog.resolve(WeatherBackground.FALLBACK))
}
