package com.loopin.player2.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaModelsTest {
    @Test
    fun `playlist order is deterministic with id tie breaker`() {
        val playlist = Playlist("p", 10, 1, listOf(item("b", 2), item("c", 1), item("a", 2)))
        assertEquals(listOf("c", "a", "b"), playlist.orderedItems().map(PlaylistItem::id))
    }

    @Test
    fun `image requires positive duration`() {
        assertTrue(item("valid", 0, MediaType.IMAGE, 3_000).isValid())
        assertFalse(item("missing", 0, MediaType.IMAGE, null).isValid())
        assertFalse(item("zero", 0, MediaType.IMAGE, 0).isValid())
    }

    @Test
    fun `remote-only item is not locally playable`() {
        val media = PlayableMedia(
            id = "remote",
            type = MediaType.VIDEO,
            reference = MediaReference(null, "https://future.invalid/video.mp4"),
            localAvailability = LocalAvailability.MISSING,
        )
        assertFalse(PlaylistItem("remote", 0, media).isValid())
    }

    private fun item(id: String, order: Int, type: MediaType = MediaType.VIDEO, duration: Long? = null) =
        PlaylistItem(
            id,
            order,
            PlayableMedia(
                id,
                type,
                MediaReference("file:///$id"),
                durationMs = duration,
                localAvailability = LocalAvailability.AVAILABLE,
            ),
        )
}
