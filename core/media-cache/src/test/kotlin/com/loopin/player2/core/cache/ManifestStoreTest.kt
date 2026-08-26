package com.loopin.player2.core.cache

import com.loopin.player2.core.model.MediaType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ManifestStoreTest {
    @Test
    fun `manifest survives round trip`() {
        val directory = Files.createTempDirectory("manifest").toFile()
        val store = ManifestStore(directory)
        val manifest = manifest()

        store.save(manifest)

        assertEquals(manifest, ManifestStore(directory).loadLastValid())
        assertNull(directory.listFiles()?.firstOrNull { it.name.endsWith(".part") })
    }

    @Test
    fun `unsupported schema is rejected without replacing valid manifest`() {
        val directory = Files.createTempDirectory("manifest").toFile()
        val store = ManifestStore(directory)
        store.save(manifest())

        assertFailsWith<IllegalArgumentException> { store.save(manifest().copy(schemaVersion = 99)) }

        assertEquals(1L, store.loadLastValid()?.playlistVersion)
    }

    @Test
    fun `unsafe local filename is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            manifest().copy(items = listOf(item().copy(localFileName = "../escape.mp4"))).validate()
        }
    }

    @Test
    fun `backup remains readable after interrupted activation`() {
        val directory = Files.createTempDirectory("manifest").toFile()
        val store = ManifestStore(directory)
        val manifest = manifest()
        store.save(manifest)
        directory.resolve("active-manifest.json").renameTo(directory.resolve("active-manifest.json.bak"))

        assertEquals(manifest, ManifestStore(directory).loadLastValid())
    }

    @Test
    fun `corrupt active manifest falls back to retained previous version`() {
        val directory = Files.createTempDirectory("manifest").toFile()
        val store = ManifestStore(directory)
        store.save(manifest().copy(playlistVersion = 1))
        store.save(manifest().copy(playlistVersion = 2))
        directory.resolve("active-manifest.json").writeText("{ incomplete")

        assertEquals(1L, ManifestStore(directory).loadLastValid()?.playlistVersion)
    }

    @Test
    fun `empty active and invalid backup yield no manifest`() {
        val directory = Files.createTempDirectory("manifest").toFile()
        directory.resolve("active-manifest.json").writeText("")
        directory.resolve("active-manifest.json.bak").writeText("not-json")

        assertNull(ManifestStore(directory).loadLastValid())
    }

    private fun manifest() = MediaManifest(playlistId = "playlist", playlistVersion = 1, generatedAtEpochMs = 10, items = listOf(item()))

    private fun item() = ManifestItem(
        id = "video",
        type = MediaType.VIDEO,
        localFileName = "video.mp4",
        order = 0,
    )
}
