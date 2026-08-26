package com.loopin.player2.core.cache

import com.loopin.player2.core.model.LocalAvailability
import com.loopin.player2.core.model.MediaType
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class SafeMediaCacheTest {
    private val bytes = "verified-media".encodeToByteArray()

    @Test
    fun `verified part becomes ready final file`() {
        val directory = Files.createTempDirectory("cache").toFile()
        val cache = SafeMediaCache(directory)
        val item = item()

        val result = cache.store(item, MediaSource { ByteArrayInputStream(bytes) })

        assertIs<CacheWriteResult.Ready>(result)
        assertEquals(CacheState.READY, cache.inspect(item).state)
        assertEquals(bytes.toList(), directory.resolve(item.localFileName).readBytes().toList())
        assertFalse(directory.resolve(item.localFileName + ".part").exists())
    }

    @Test
    fun `wrong checksum is failed and part is discarded`() {
        val directory = Files.createTempDirectory("cache").toFile()
        val cache = SafeMediaCache(directory)
        val item = item().copy(sha256 = "0".repeat(64))

        val result = cache.store(item, MediaSource { ByteArrayInputStream(bytes) })

        assertIs<CacheWriteResult.Failed>(result)
        assertEquals(CacheState.FAILED, cache.inspect(item).state)
        assertFalse(directory.resolve(item.localFileName).exists())
        assertFalse(directory.resolve(item.localFileName + ".part").exists())
    }

    @Test
    fun `interrupted transfer never becomes playable`() {
        val directory = Files.createTempDirectory("cache").toFile()
        val cache = SafeMediaCache(directory)
        val item = item()

        val result = cache.store(item, MediaSource { throw IOException("interrupted") })

        assertIs<CacheWriteResult.Failed>(result)
        assertNull(cache.toPlaylist(manifest(item)).items.single().media.reference.localUri)
        assertEquals(LocalAvailability.MISSING, cache.toPlaylist(manifest(item)).items.single().media.localAvailability)
    }

    @Test
    fun `corrupt final file is invalid and excluded from playback`() {
        val directory = Files.createTempDirectory("cache").toFile()
        val cache = SafeMediaCache(directory)
        val item = item()
        directory.resolve(item.localFileName).writeText("corrupt")

        assertEquals(CacheState.INVALID, cache.inspect(item).state)
        assertNull(cache.toPlaylist(manifest(item)).items.single().media.reference.localUri)
    }

    @Test
    fun `orphan part is removed on cache startup`() {
        val directory = Files.createTempDirectory("cache").toFile()
        directory.resolve("orphan.mp4.part").writeText("partial")

        SafeMediaCache(directory)

        assertFalse(directory.resolve("orphan.mp4.part").exists())
    }

    @Test
    fun `previous valid file is restored after interrupted promotion`() {
        val directory = Files.createTempDirectory("cache").toFile()
        directory.resolve("video.mp4.previous").writeBytes(bytes)

        val cache = SafeMediaCache(directory)

        assertEquals(CacheState.READY, cache.inspect(item()).state)
        assertFalse(directory.resolve("video.mp4.previous").exists())
    }

    @Test
    fun `cache instances serialize writes to the same directory`() {
        val directory = Files.createTempDirectory("cache").toFile()
        val first = SafeMediaCache(directory)
        val second = SafeMediaCache(directory)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val firstWrite = executor.submit<CacheWriteResult> {
                first.store(item(), MediaSource {
                    entered.countDown()
                    release.await(2, TimeUnit.SECONDS)
                    ByteArrayInputStream(bytes)
                })
            }
            entered.await(2, TimeUnit.SECONDS)
            val secondWrite = executor.submit<CacheWriteResult> {
                second.store(item(), MediaSource { ByteArrayInputStream(bytes) })
            }
            release.countDown()

            assertIs<CacheWriteResult.Ready>(firstWrite.get(2, TimeUnit.SECONDS))
            assertIs<CacheWriteResult.Ready>(secondWrite.get(2, TimeUnit.SECONDS))
            assertEquals(CacheState.READY, first.inspect(item()).state)
            assertFalse(directory.resolve("video.mp4.part").exists())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `ten fifty and one hundred small items remain consistent`() {
        listOf(10, 50, 100).forEach { count ->
            val directory = Files.createTempDirectory("cache-scale-$count").toFile()
            val cache = SafeMediaCache(directory)
            val items = (0 until count).map { index ->
                item().copy(id = "media-$index", localFileName = "media-$index.mp4", order = index)
            }

            val writeStarted = System.nanoTime()
            items.forEach { media ->
                assertIs<CacheWriteResult.Ready>(cache.store(media, MediaSource { ByteArrayInputStream(bytes) }))
            }
            val writeMs = (System.nanoTime() - writeStarted) / 1_000_000
            val readStarted = System.nanoTime()
            val playlist = cache.toPlaylist(
                MediaManifest(playlistId = "playlist", playlistVersion = 1, generatedAtEpochMs = 0, items = items),
            )
            val readMs = (System.nanoTime() - readStarted) / 1_000_000

            assertEquals(count, playlist.items.count { it.media.isLocallyPlayable() })
            assertEquals(count, directory.listFiles { file -> file.extension == "mp4" }?.size)
            assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".part") })
            kotlin.test.assertTrue(directory.resolve("cache-state.properties").length() < 10_000L)
            println("CACHE_SCALE count=$count writeMs=$writeMs readMs=$readMs stateBytes=${directory.resolve("cache-state.properties").length()}")
        }
    }

    private fun manifest(item: ManifestItem) = MediaManifest(
        playlistId = "playlist",
        playlistVersion = 1,
        generatedAtEpochMs = 0,
        items = listOf(item),
    )

    private fun item() = ManifestItem(
        id = "video",
        type = MediaType.VIDEO,
        localFileName = "video.mp4",
        order = 0,
        expectedSizeBytes = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
    )
}
