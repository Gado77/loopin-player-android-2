package com.loopin.player2

import android.content.Context
import com.loopin.player2.core.cache.MediaManifest
import com.loopin.player2.core.cache.MediaSource
import com.loopin.player2.core.cache.PreparationResult
import com.loopin.player2.core.cache.PublicationResult
import com.loopin.player2.core.cache.TransactionalPlaylistStore
import com.loopin.player2.core.model.LocalAvailability
import com.loopin.player2.core.model.LogLevel
import com.loopin.player2.core.model.MediaReference
import com.loopin.player2.core.model.MediaType
import com.loopin.player2.core.model.PlayableMedia
import com.loopin.player2.core.model.PlayerLogger
import com.loopin.player2.core.model.Playlist
import com.loopin.player2.core.model.PlaylistItem
import com.loopin.player2.core.model.PlaylistRepository
import com.loopin.player2.core.model.DynamicContentType
import com.loopin.player2.core.model.DynamicMediaContent
import java.io.File

/** Local phase 3.2 publisher. It exercises the transactional path without any network dependency. */
class LocalTestPlaylistRepository(
    private val context: Context,
    private val logger: PlayerLogger,
    private val store: TransactionalPlaylistStore = TransactionalPlaylistStore(File(context.filesDir, "transactional-media")),
) : PlaylistRepository {

    override fun loadActivePlaylist(): Playlist = store.loadActivePlaylist() ?: withConfiguredDynamicItems(bundledFallbackPlaylist(1))

    override fun loadActivePlaylistAsync(onLoaded: (Playlist) -> Unit) {
        Thread({
            val playlist = runCatching {
                store.loadActivePlaylist() ?: bundledFallbackPlaylist(1)
            }.onFailure { error ->
                logger.log(LogLevel.ERROR, TAG, "Transactional playlist initialization failed", error)
            }.getOrElse { bundledFallbackPlaylist(1) }
            onLoaded(if (store.publicationState() == null) withConfiguredDynamicItems(playlist) else playlist)
        }, "loopin-playlist-prepare").apply {
            isDaemon = true
            start()
        }
    }

    private fun ensureMockPublication() {
        val abandoned = store.recoverAbandonedStaging()
        if (abandoned > 0) logger.log(LogLevel.WARN, TAG, "Recovered abandoned staging count=$abandoned")
        var activeVersion = store.publicationState()?.active?.playlistVersion
        if (activeVersion == null) {
            val v1 = prepare(mockManifest(1))
            if (v1 !is PreparationResult.Ready) {
                logger.log(LogLevel.ERROR, TAG, "Mock v1 rejected: $v1")
                return
            }
            val commit = store.commit(v1.versionRef)
            logger.log(LogLevel.INFO, TAG, "Mock v1 publication result=$commit")
            activeVersion = store.publicationState()?.active?.playlistVersion
        }

        if (activeVersion == 1L) {
            val v2 = prepare(mockManifest(2))
            if (v2 !is PreparationResult.Ready) {
                logger.log(LogLevel.ERROR, TAG, "Mock v2 rejected; ACTIVE remains version=$activeVersion result=$v2")
                return
            }
            val beforeCommit = store.publicationState()?.active?.playlistVersion
            logger.log(
                LogLevel.INFO,
                TAG,
                "Mock v2 staging READY; ACTIVE before commit=$beforeCommit versionRef=${v2.versionRef}",
            )
            val commit = store.commit(v2.versionRef)
            val state = store.publicationState()
            logger.log(
                if (commit is PublicationResult.Committed) LogLevel.INFO else LogLevel.ERROR,
                TAG,
                "Mock v2 commit result=$commit ACTIVE=${state?.active?.playlistVersion} PREVIOUS=${state?.previous?.playlistVersion}",
            )
        }
    }

    private fun prepare(manifest: MediaManifest): PreparationResult = store.prepare(manifest) { item ->
        resourceFor(item.id)?.let { resource -> MediaSource { context.resources.openRawResource(resource) } }
    }

    private fun mockManifest(version: Long) = MediaManifest(
        playlistId = "bundled-offline-validation",
        playlistVersion = version,
        generatedAtEpochMs = version,
        items = listOf(
            item("video-a", 0, MediaType.VIDEO, "screen.mp4", VIDEO_SIZE, VIDEO_SHA256, "video/mp4", version),
            item("image-a", 1, MediaType.IMAGE, "screen.png", IMAGE_SIZE, IMAGE_SHA256, "image/png", version, IMAGE_DURATION_MS),
            item("video-b", 2, MediaType.VIDEO, "screen.mp4", VIDEO_SIZE, VIDEO_SHA256, "video/mp4", version),
        ),
    )

    private fun item(
        id: String,
        order: Int,
        type: MediaType,
        logicalFileName: String,
        size: Long,
        sha256: String,
        mimeType: String,
        version: Long,
        durationMs: Long? = null,
    ) = com.loopin.player2.core.cache.ManifestItem(
        id = id,
        type = type,
        localFileName = logicalFileName,
        durationMs = durationMs,
        order = order,
        expectedSizeBytes = size,
        sha256 = sha256,
        mimeType = mimeType,
        metadata = mapOf("source" to "bundled-phase3.2-mock", "manifestVersion" to version.toString()),
    )

    private fun bundledFallbackPlaylist(version: Long) = Playlist(
        id = "bundled-offline-validation",
        version = version,
        updatedAtEpochMs = version,
        items = listOf(
            fallbackItem("video-a", 0, MediaType.VIDEO, R.raw.sample_video),
            fallbackItem("image-a", 1, MediaType.IMAGE, R.raw.sample_image, IMAGE_DURATION_MS),
            fallbackItem("video-b", 2, MediaType.VIDEO, R.raw.sample_video),
        ),
    )

    private fun fallbackItem(id: String, order: Int, type: MediaType, resourceId: Int, durationMs: Long? = null) =
        PlaylistItem(
            id = id,
            order = order,
            media = PlayableMedia(
                id = id,
                type = type,
                reference = MediaReference("android.resource://${context.packageName}/$resourceId"),
                durationMs = durationMs,
                metadata = mapOf("source" to "bundled-emergency-fallback"),
                localAvailability = LocalAvailability.AVAILABLE,
            ),
        )

    /** Explicit local Phase 7 program. Future Admin manifests will provide this ordered list directly. */
    private fun withConfiguredDynamicItems(base: Playlist): Playlist {
        val normal = base.orderedItems()
        if (normal.any { it.dynamic != null }) return base
        val programmed = buildList {
            normal.getOrNull(0)?.let { add(PlaylistItem(it.id, size, it.content)) }
            add(weatherItem("weather-a", size))
            normal.getOrNull(1)?.let { add(PlaylistItem(it.id, size, it.content)) }
            normal.getOrNull(2)?.let { add(PlaylistItem(it.id, size, it.content)) }
            add(weatherItem("weather-b", size))
            normal.drop(3).forEach { add(PlaylistItem(it.id, size, it.content)) }
        }
        return base.copy(items = programmed)
    }

    private fun weatherItem(id: String, order: Int) = PlaylistItem(
        id, order, DynamicMediaContent(DynamicContentType.WEATHER, WEATHER_DURATION_MS,
            mapOf("city" to "São José do Piauí")),
    )

    private fun resourceFor(id: String): Int? = when (id) {
        "video-a", "video-b" -> R.raw.sample_video
        "image-a" -> R.raw.sample_image
        else -> null
    }

    private companion object {
        const val TAG = "TransactionalPlaylist"
        const val IMAGE_DURATION_MS = 3_000L
        const val WEATHER_DURATION_MS = 5_000L
        const val VIDEO_SIZE = 2_915L
        const val IMAGE_SIZE = 1_510L
        const val VIDEO_SHA256 = "19efb3bddb343e35b772adb573fcdb6e050af5b4ec9173e9ff7cb6f79503f63a"
        const val IMAGE_SHA256 = "b0dd1b6b0103bf3ec36d103535a2ca42c825b23de445dbfffed7d80f9610c398"
    }
}
