package com.loopin.player2.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class MediaType { VIDEO, IMAGE }

enum class LocalAvailability { AVAILABLE, MISSING, UNKNOWN }

data class MediaReference(
    val localUri: String?,
    val remoteUrl: String? = null,
) {
    fun playbackUri(): String? = localUri?.takeIf(String::isNotBlank)
}

data class PlayableMedia(
    val id: String,
    val type: MediaType,
    val reference: MediaReference,
    val durationMs: Long? = null,
    val checksum: String? = null,
    val sizeBytes: Long? = null,
    val metadata: Map<String, String> = emptyMap(),
    val localAvailability: LocalAvailability = LocalAvailability.UNKNOWN,
) {
    fun isLocallyPlayable(): Boolean =
        id.isNotBlank() &&
            reference.playbackUri() != null &&
            localAvailability == LocalAvailability.AVAILABLE &&
            (type != MediaType.IMAGE || durationMs != null && durationMs > 0L)
}

sealed interface PlaylistContent
data class NormalMediaContent(val media: PlayableMedia) : PlaylistContent
enum class DynamicContentType { WEATHER }
data class DynamicMediaContent(
    val type: DynamicContentType,
    val durationMs: Long,
    val configuration: Map<String, String> = emptyMap(),
) : PlaylistContent {
    init { require(durationMs > 0) }
}

data class PlaylistItem(
    val id: String,
    val order: Int,
    val content: PlaylistContent,
) {
    constructor(id: String, order: Int, media: PlayableMedia) : this(id, order, NormalMediaContent(media))
    val media: PlayableMedia get() = (content as NormalMediaContent).media
    val dynamic: DynamicMediaContent? get() = content as? DynamicMediaContent
    fun isValid(): Boolean = id.isNotBlank() && order >= 0 && when (content) {
        is NormalMediaContent -> content.media.isLocallyPlayable()
        is DynamicMediaContent -> content.durationMs > 0
    }
}

data class Playlist(
    val id: String,
    val version: Long,
    val updatedAtEpochMs: Long,
    val items: List<PlaylistItem>,
) {
    fun orderedItems(): List<PlaylistItem> = items.sortedWith(compareBy(PlaylistItem::order, PlaylistItem::id))
}

interface PlaylistRepository {
    fun loadActivePlaylist(): Playlist

    fun loadActivePlaylistAsync(onLoaded: (Playlist) -> Unit) {
        onLoaded(loadActivePlaylist())
    }
}

/** Future cache boundary. Playback continues to consume only a resolved local URI. */
interface LocalMediaResolver {
    fun resolveLocalUri(media: PlayableMedia): String?
}

/** Future synchronization boundary; intentionally has no implementation in phase 2. */
interface PlaylistSyncSource {
    fun loadNewerThan(currentVersion: Long?): Playlist?
}
