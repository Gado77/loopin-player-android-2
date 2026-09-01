package com.loopin.player2.core.cache

import com.loopin.player2.core.model.DynamicMediaContent as PlaybackDynamicContent
import com.loopin.player2.core.model.MediaType
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VersionedManifestTest {
    private val mediaBytes = "phase-nine-media".encodeToByteArray()

    @Test fun `schema 2 round trip is deterministic and normalizes mixed content`() {
        val manifest = mixedManifest()
        val first = VersionedManifestCodec.encode(manifest)
        val decoded = VersionedManifestCodec.decode(first)
        assertEquals(first, VersionedManifestCodec.encode(decoded))
        assertIs<NormalMediaContent>(decoded.items[0].content)
        assertIs<DynamicMediaContent>(decoded.items[1].content)
    }

    @Test fun `unknown and mixed fields are rejected`() {
        val valid = VersionedManifestCodec.encode(mixedManifest())
        assertFailsWith<IllegalArgumentException> {
            VersionedManifestCodec.decode(valid.replace("\"assetId\"", "\"dynamicType\": \"WEATHER\",\n      \"assetId\""))
        }
        assertFailsWith<IllegalArgumentException> {
            VersionedManifestCodec.decode(valid.replace("\"playlistId\"", "\"unexpected\": true,\n  \"playlistId\""))
        }
        assertFailsWith<IllegalArgumentException> {
            VersionedManifestCodec.decode(valid.replace("\"mimeType\": \"video/mp4\"", "\"mimeType\": \"video/mp4\",\n      \"remoteUrl\": \"https://signed.invalid\""))
        }
    }

    @Test fun `duplicate ids and orders are rejected`() {
        val item = mixedManifest().items.first()
        assertFailsWith<IllegalArgumentException> { mixedManifest().copy(items = listOf(item, item.copy(order = 2))).validate() }
        assertFailsWith<IllegalArgumentException> { mixedManifest().copy(items = listOf(item, item.copy(id = "other"))).validate() }
    }

    @Test fun `empty manifest and invalid weather configuration are rejected`() {
        assertFailsWith<IllegalArgumentException> { mixedManifest().copy(items = emptyList()).validate() }
        val weather = mixedManifest().items[1]
        assertFailsWith<IllegalArgumentException> {
            mixedManifest().copy(items = listOf(weather.copy(content = DynamicMediaContent(
                ManifestDynamicType.WEATHER, 20_000, mapOf("city" to "", "lat" to "-7", "lon" to "-41"),
            )))).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            mixedManifest().copy(items = listOf(weather.copy(content = DynamicMediaContent(
                ManifestDynamicType.WEATHER, 20_000, mapOf("city" to "Cidade", "lat" to "91", "lon" to "-41"),
            )))).validate()
        }
    }

    @Test fun `dynamic weather creates no cache object and remains ordered in playback`() {
        val root = Files.createTempDirectory("manifest-v2").toFile()
        val store = TransactionalPlaylistStore(root, SpacePolicy { _, _ -> true })
        val prepared = assertIs<PreparationResult.Ready>(store.prepare(mixedManifest()) {
            MediaSource { ByteArrayInputStream(mediaBytes) }
        })
        assertEquals(1, prepared.createdObjects)
        assertIs<PublicationResult.Committed>(store.commit(prepared.versionRef))
        val playlist = store.loadActivePlaylist()!!
        assertEquals(listOf("media", "weather"), playlist.items.map { it.id })
        assertIs<PlaybackDynamicContent>(playlist.items[1].content)
        assertEquals(1, root.resolve("objects").listFiles()?.count { it.isFile })
    }

    @Test fun `legacy active upgrades to v2 and rollback survives repository recreation`() {
        val root = Files.createTempDirectory("manifest-upgrade").toFile()
        val legacy = MediaManifest(playlistId = "playlist", playlistVersion = 1, generatedAtEpochMs = 1, items = listOf(
            ManifestItem("legacy", MediaType.VIDEO, localFileName = "legacy.mp4", order = 0,
                expectedSizeBytes = mediaBytes.size.toLong(), sha256 = hash(mediaBytes), mimeType = "video/mp4"),
        ))
        var store = TransactionalPlaylistStore(root, SpacePolicy { _, _ -> true })
        val v1 = assertIs<PreparationResult.Ready>(store.prepare(legacy) { MediaSource { ByteArrayInputStream(mediaBytes) } })
        store.commit(v1.versionRef)
        val v2 = assertIs<PreparationResult.Ready>(store.prepare(mixedManifest().copy(playlistVersion = 2)) {
            MediaSource { ByteArrayInputStream(mediaBytes) }
        })
        store.commit(v2.versionRef)
        store = TransactionalPlaylistStore(root, SpacePolicy { _, _ -> true })
        assertEquals(2, store.loadActivePlaylist()?.version)
        assertIs<RollbackResult.RolledBack>(store.rollback())
        assertEquals(1, TransactionalPlaylistStore(root, SpacePolicy { _, _ -> true }).loadActivePlaylist()?.version)
    }

    private fun mixedManifest() = VersionedManifest(2, "playlist", 9, 123, listOf(
        NormalizedManifestItem("media", 0, NormalMediaContent(MediaType.VIDEO, "asset-1", null,
            mediaBytes.size.toLong(), hash(mediaBytes), "video/mp4")),
        NormalizedManifestItem("weather", 1, DynamicMediaContent(ManifestDynamicType.WEATHER, 20_000,
            mapOf("city" to "São José do Piauí", "lat" to "-7.08", "lon" to "-41.47"))),
    ))

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
