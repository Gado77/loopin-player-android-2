package com.loopin.player2.core.cache

import com.loopin.player2.core.model.MediaType
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransactionalPlaylistStoreTest {
    private val v1Bytes = "version-one".encodeToByteArray()
    private val v2Bytes = "version-two".encodeToByteArray()

    @Test
    fun `v1 active then valid v2 commits atomically`() {
        val fixture = fixture()
        fixture.publish(manifest(1, item("media", "same.mp4", v1Bytes)))
        val prepared = fixture.prepare(manifest(2, item("media", "same.mp4", v2Bytes)))

        assertEquals(1L, fixture.store.loadActivePlaylist()?.version)
        assertIs<PublicationResult.Committed>(fixture.store.commit(prepared.versionRef))
        assertEquals(2L, fixture.store.loadActivePlaylist()?.version)
        assertEquals(1L, fixture.store.publicationState()?.previous?.playlistVersion)
    }

    @Test
    fun `partial v2 rejection keeps v1 fully playable`() {
        val fixture = fixture()
        val active = fixture.publish(manifest(1, item("one", "one.mp4", v1Bytes)))
        val v1File = fileOf(active)
        val candidate = manifest(2, item("one", "one.mp4", v1Bytes), item("missing", "missing.mp4", v2Bytes, 1))

        assertIs<PreparationResult.Rejected>(fixture.store.prepare(candidate) { if (it.id == "one") source(v1Bytes) else null })

        assertEquals(1L, fixture.store.loadActivePlaylist()?.version)
        assertTrue(v1File.isFile)
        assertEquals(v1Bytes.toList(), v1File.readBytes().toList())
    }

    @Test
    fun `corrupt candidate media is rejected without changing active`() {
        val fixture = fixture()
        fixture.publish(manifest(1, item("one", "one.mp4", v1Bytes)))
        val candidate = manifest(2, item("two", "two.mp4", v2Bytes))

        val result = fixture.store.prepare(candidate) { source("corrupt".encodeToByteArray()) }

        assertIs<PreparationResult.Rejected>(result)
        assertEquals(1L, fixture.store.loadActivePlaylist()?.version)
    }

    @Test
    fun `same logical filename across versions never overwrites active object`() {
        val fixture = fixture()
        val v1 = fixture.publish(manifest(1, item("media", "shared-name.mp4", v1Bytes)))
        val v1File = fileOf(v1)
        val ready = fixture.prepare(manifest(2, item("media", "shared-name.mp4", v2Bytes)))
        fixture.store.commit(ready.versionRef)
        val v2File = fileOf(fixture.store.loadActivePlaylist()!!)

        assertNotEquals(v1File.absolutePath, v2File.absolutePath)
        assertEquals(v1Bytes.toList(), v1File.readBytes().toList())
        assertEquals(v2Bytes.toList(), v2File.readBytes().toList())
    }

    @Test
    fun `same content is shared by versions without duplication`() {
        val fixture = fixture()
        fixture.publish(manifest(1, item("old-id", "old.mp4", v1Bytes)))
        val ready = fixture.prepare(manifest(2, item("new-id", "new.mp4", v1Bytes)))

        assertEquals(1, ready.reusedObjects)
        assertEquals(0, ready.createdObjects)
        fixture.store.commit(ready.versionRef)
        assertEquals(1, fixture.objects().size)
    }

    @Test
    fun `new v2 media creates a second immutable object`() {
        val fixture = fixture()
        fixture.publish(manifest(1, item("one", "one.mp4", v1Bytes)))
        val ready = fixture.prepare(manifest(2, item("two", "two.mp4", v2Bytes)))

        assertEquals(1, ready.createdObjects)
        fixture.store.commit(ready.versionRef)
        assertEquals(2, fixture.objects().size)
    }

    @Test
    fun `invalid manifest never creates an active candidate`() {
        val fixture = fixture()
        fixture.publish(manifest(1, item("one", "one.mp4", v1Bytes)))
        val invalid = manifest(2, item("one", "one.mp4", v2Bytes)).copy(schemaVersion = 99)

        assertIs<PreparationResult.Rejected>(fixture.store.prepare(invalid) { source(v2Bytes) })
        assertEquals(1L, fixture.store.loadActivePlaylist()?.version)
    }

    @Test
    fun `incomplete staging cannot be committed`() {
        val fixture = fixture()
        fixture.publish(manifest(1, item("one", "one.mp4", v1Bytes)))
        assertIs<PreparationResult.Rejected>(
            fixture.store.prepare(manifest(2, item("two", "two.mp4", v2Bytes))) { null },
        )
        val rejectedStage = fixture.root.resolve("staging").listFiles()!!.single().name

        assertIs<PublicationResult.Rejected>(fixture.store.commit(rejectedStage))
        assertEquals(1L, fixture.store.loadActivePlaylist()?.version)
    }

    @Test
    fun `interruption before pointer activation leaves v1 active`() {
        val fixture = fixture()
        fixture.publish(manifest(1, item("one", "one.mp4", v1Bytes)))
        val ready = fixture.prepare(manifest(2, item("two", "two.mp4", v2Bytes)))

        assertFailsWith<SimulatedPowerLoss> {
            fixture.store.commit(ready.versionRef) { step ->
                if (step == CommitStep.AFTER_BACKUP_BEFORE_ACTIVATE) throw SimulatedPowerLoss()
            }
        }

        assertEquals(1L, fixture.store.loadActivePlaylist()?.version)
    }

    @Test
    fun `recovery after interruption immediately after activation returns v2 or v1`() {
        val fixture = fixture()
        fixture.publish(manifest(1, item("one", "one.mp4", v1Bytes)))
        val ready = fixture.prepare(manifest(2, item("two", "two.mp4", v2Bytes)))
        assertFailsWith<SimulatedPowerLoss> {
            fixture.store.commit(ready.versionRef) { step ->
                if (step == CommitStep.AFTER_ACTIVATE) throw SimulatedPowerLoss()
            }
        }

        val recovered = TransactionalPlaylistStore(fixture.root).loadActivePlaylist()

        assertNotNull(recovered)
        assertTrue(recovered.version == 1L || recovered.version == 2L)
        assertTrue(recovered.items.all { it.media.isLocallyPlayable() })
    }

    @Test
    fun `embedded previous recovers when active media and pointer backup are lost`() {
        val fixture = fixture()
        val v1 = fixture.publish(manifest(1, item("one", "one.mp4", v1Bytes)))
        val v2 = fixture.publish(manifest(2, item("two", "two.mp4", v2Bytes)))
        fileOf(v2).delete()
        fixture.root.resolve("pointers/active-playlist.json.bak").delete()

        val recovered = TransactionalPlaylistStore(fixture.root).loadActivePlaylist()

        assertEquals(1L, recovered?.version)
        assertEquals(v1Bytes.toList(), fileOf(recovered!!).readBytes().toList())
    }

    @Test
    fun `rollback swaps active v2 and previous v1 after validation`() {
        val fixture = fixture()
        fixture.publish(manifest(1, item("one", "one.mp4", v1Bytes)))
        fixture.publish(manifest(2, item("two", "two.mp4", v2Bytes)))

        assertIs<RollbackResult.RolledBack>(fixture.store.rollback())
        assertEquals(1L, fixture.store.loadActivePlaylist()?.version)
        assertEquals(2L, fixture.store.publicationState()?.previous?.playlistVersion)
    }

    @Test
    fun `rollback rejects previous with missing media`() {
        val fixture = fixture()
        val v1 = fixture.publish(manifest(1, item("one", "one.mp4", v1Bytes)))
        fixture.publish(manifest(2, item("two", "two.mp4", v2Bytes)))
        fileOf(v1).delete()

        assertIs<RollbackResult.Rejected>(fixture.store.rollback())
        assertEquals(2L, fixture.store.loadActivePlaylist()?.version)
    }

    @Test
    fun `insufficient space rejects candidate and preserves active`() {
        val root = Files.createTempDirectory("transaction-space").toFile()
        val fixture = Fixture(root, TransactionalPlaylistStore(root, SpacePolicy { _, _ -> false }))

        assertIs<PreparationResult.Rejected>(fixture.store.prepare(manifest(1, item("one", "one.mp4", v1Bytes))) { source(v1Bytes) })
        assertNull(fixture.store.loadActivePlaylist())
        assertTrue(fixture.objects().isEmpty())
    }

    @Test
    fun `two concurrent updates are serialized and state remains complete`() {
        val fixture = fixture()
        fixture.publish(manifest(1, item("one", "one.mp4", v1Bytes)))
        val executor = Executors.newFixedThreadPool(2)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val first = executor.submit<PreparationResult> {
                fixture.store.prepare(manifest(2, item("two", "two.mp4", v2Bytes))) {
                    started.countDown(); release.await(2, TimeUnit.SECONDS); source(v2Bytes)
                }
            }
            started.await(2, TimeUnit.SECONDS)
            val second = executor.submit<PreparationResult> {
                fixture.store.prepare(manifest(3, item("three", "three.mp4", "three".encodeToByteArray()))) {
                    source("three".encodeToByteArray())
                }
            }
            release.countDown()

            val firstReady = assertIs<PreparationResult.Ready>(first.get(3, TimeUnit.SECONDS))
            val secondReady = assertIs<PreparationResult.Ready>(second.get(3, TimeUnit.SECONDS))
            fixture.store.commit(firstReady.versionRef)
            fixture.store.commit(secondReady.versionRef)
            assertEquals(3L, fixture.store.loadActivePlaylist()?.version)
            assertEquals(2L, fixture.store.publicationState()?.previous?.playlistVersion)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `playback snapshot remains readable while v2 staging fails`() {
        val fixture = fixture()
        val playbackV1 = fixture.publish(manifest(1, item("one", "same.mp4", v1Bytes)))

        fixture.store.prepare(manifest(2, item("two", "same.mp4", v2Bytes))) { throw IOException("power loss") }

        assertEquals(v1Bytes.toList(), fileOf(playbackV1).readBytes().toList())
        assertEquals(1L, fixture.store.loadActivePlaylist()?.version)
    }

    @Test
    fun `playback snapshot remains readable during and after commit`() {
        val fixture = fixture()
        val playbackV1 = fixture.publish(manifest(1, item("one", "same.mp4", v1Bytes)))
        val oldFile = fileOf(playbackV1)
        val ready = fixture.prepare(manifest(2, item("two", "same.mp4", v2Bytes)))

        fixture.store.commit(ready.versionRef)

        assertTrue(oldFile.isFile)
        assertEquals(v1Bytes.toList(), oldFile.readBytes().toList())
        assertEquals(2L, fixture.store.loadActivePlaylist()?.version)
    }

    @Test
    fun `orphan immutable object is never exposed to playback`() {
        val fixture = fixture()
        fixture.publish(manifest(1, item("one", "one.mp4", v1Bytes)))
        fixture.root.resolve("objects/${"f".repeat(64)}").writeText("orphan")

        val active = fixture.store.loadActivePlaylist()!!

        assertEquals(1, active.items.size)
        assertEquals(v1Bytes.toList(), fileOf(active).readBytes().toList())
    }

    @Test
    fun `abandoned preparing staging is removed after crash recovery`() {
        val fixture = fixture()
        val abandoned = fixture.root.resolve("staging/${"a".repeat(64)}")
        abandoned.mkdirs()
        abandoned.resolve("preparation.json.part").writeText("partial")

        TransactionalPlaylistStore(fixture.root).recoverAbandonedStaging()

        assertFalse(abandoned.exists())
    }

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("transaction").toFile()
        return Fixture(root, TransactionalPlaylistStore(root, SpacePolicy { _, _ -> true }, clock = { 123L }))
    }

    private fun manifest(version: Long, vararg items: ManifestItem) = MediaManifest(
        playlistId = "playlist",
        playlistVersion = version,
        generatedAtEpochMs = version,
        items = items.toList(),
    )

    private fun item(id: String, fileName: String, bytes: ByteArray, order: Int = 0) = ManifestItem(
        id = id,
        type = MediaType.VIDEO,
        localFileName = fileName,
        order = order,
        expectedSizeBytes = bytes.size.toLong(),
        sha256 = hash(bytes),
        mimeType = "video/mp4",
    )

    private fun source(bytes: ByteArray) = MediaSource { ByteArrayInputStream(bytes) }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fileOf(playlist: com.loopin.player2.core.model.Playlist): File =
        File(URI(playlist.items.first().media.reference.localUri!!))

    private inner class Fixture(val root: File, val store: TransactionalPlaylistStore) {
        fun prepare(manifest: MediaManifest): PreparationResult.Ready = assertIs(
            store.prepare(manifest) { item ->
                when (item.sha256) {
                    hash(v1Bytes) -> source(v1Bytes)
                    hash(v2Bytes) -> source(v2Bytes)
                    else -> error("Unknown fixture media")
                }
            },
        )

        fun publish(manifest: MediaManifest): com.loopin.player2.core.model.Playlist {
            val ready = prepare(manifest)
            assertIs<PublicationResult.Committed>(store.commit(ready.versionRef))
            return store.loadActivePlaylist()!!
        }

        fun objects(): List<File> = root.resolve("objects").listFiles()?.filter(File::isFile).orEmpty()
    }

    private class SimulatedPowerLoss : RuntimeException()
}
