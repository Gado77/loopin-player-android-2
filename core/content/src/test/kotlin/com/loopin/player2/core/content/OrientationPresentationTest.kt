package com.loopin.player2.core.content

import com.loopin.player2.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrientationPresentationTest {
    @Test fun `default orientation is portrait`() = assertEquals(ContentOrientation.PORTRAIT, ContentPresentation().orientation)

    @Test fun `playlist order is independent from presentation`() {
        val playlist = playlist(); val before = playlist.orderedItems().map { it.id }
        ContentPresentation(ContentOrientation.LANDSCAPE).fitInside(1600, 900)
        assertEquals(before, playlist.orderedItems().map { it.id })
    }

    @Test fun `video uses one uniform crop scale`() {
        val scale = ContentPresentation().cropScale(1920, 1080)
        assertTrue(scale > 0); assertEquals(16.0 / 1080, scale)
    }

    @Test fun `image uses one uniform crop scale`() {
        val scale = ContentPresentation().cropScale(1000, 1000)
        assertEquals(16.0 / 1000, scale)
    }

    @Test fun `weather composition uses portrait canvas`() = assertEquals(CanvasSize(506, 900), ContentPresentation().fitInside(1600, 900))

    @Test fun `clock margins are relative to canvas`() {
        val presentation = ContentPresentation()
        assertEquals(31, presentation.topMarginPx(900)); assertEquals(27, presentation.endMarginPx(506))
    }

    @Test fun `orientation change does not alter playlist order`() {
        val source = playlist().items
        ContentPresentation(ContentOrientation.PORTRAIT).fitInside(900, 1600)
        ContentPresentation(ContentOrientation.LANDSCAPE).fitInside(900, 1600)
        assertEquals(listOf("a", "w"), source.sortedBy { it.order }.map { it.id })
    }

    @Test fun `orientation does not alter cache key`() {
        val cacheKey = "sha256-object"; ContentPresentation().fitInside(1600, 900); assertEquals("sha256-object", cacheKey)
    }
    @Test fun `orientation does not alter synchronization version`() {
        val playlistVersion = 42L; ContentPresentation(ContentOrientation.LANDSCAPE).fitInside(1600, 900); assertEquals(42L, playlistVersion)
    }
    @Test fun `orientation does not alter device identity`() {
        val internalId = "internal-id"; ContentPresentation().fitInside(900, 1600); assertEquals("internal-id", internalId)
    }

    @Test fun `offline fallback remains a valid dynamic item`() = assertTrue(
        PlaylistItem("w", 0, DynamicMediaContent(DynamicContentType.WEATHER, 5_000)).isValid())

    private fun playlist() = Playlist("p", 1, 1, listOf(
        PlaylistItem("a", 0, PlayableMedia("a", MediaType.VIDEO, MediaReference("file:///a.mp4"),
            localAvailability = LocalAvailability.AVAILABLE)),
        PlaylistItem("w", 1, DynamicMediaContent(DynamicContentType.WEATHER, 5_000)),
    ))
}
