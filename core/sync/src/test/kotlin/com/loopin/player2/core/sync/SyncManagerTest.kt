package com.loopin.player2.core.sync

import com.loopin.player2.core.cache.ManifestItem
import com.loopin.player2.core.cache.MediaManifest
import com.loopin.player2.core.cache.MediaSource
import com.loopin.player2.core.cache.PreparationResult
import com.loopin.player2.core.cache.PublicationResult
import com.loopin.player2.core.cache.SpacePolicy
import com.loopin.player2.core.cache.TransactionalPlaylistStore
import com.loopin.player2.core.model.MediaType
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SyncManagerTest {
    private val first = "content-one".encodeToByteArray()
    private val second = "content-two".encodeToByteArray()

    @Test
    fun `server available returns remote manifest`() {
        val manifest = manifest(2, item("two", second))
        val body = Json.encodeToString(MediaManifest.serializer(), manifest)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/manifest") { exchange ->
                exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            start()
        }
        try {
            val result = HttpRemoteManifestSource("http://127.0.0.1:${server.address.port}/manifest").fetch(1)
            assertEquals(manifest, assertIs<RemoteManifestResult.Available>(result).manifest)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `server offline produces offline state and leaves local content`() {
        val fixture = fixtureWithV1()
        val socket = ServerSocket(0)
        val port = socket.localPort
        socket.close()
        val manager = manager(fixture, HttpRemoteManifestSource("http://127.0.0.1:$port/manifest"))

        assertIs<SyncResult.Offline>(manager.syncOnce())
        assertEquals(SyncState.OFFLINE, manager.snapshot.state)
        assertEquals(1L, fixture.store.loadActivePlaylist()?.version)
    }

    @Test
    fun `equal manifest is up to date`() {
        val fixture = fixtureWithV1()
        val manager = manager(fixture, RemoteManifestSource { RemoteManifestResult.Available(manifest(1, item("one", first))) })

        assertIs<SyncResult.UpToDate>(manager.syncOnce())
        assertEquals(SyncState.UP_TO_DATE, manager.snapshot.state)
    }

    @Test
    fun `new manifest is detected before preparation`() {
        val fixture = fixtureWithV1()
        val states = mutableListOf<SyncState>()
        val manager = manager(fixture, RemoteManifestSource { RemoteManifestResult.Available(manifest(2, item("two", second))) })
        manager.subscribe { states += it.state }

        assertIs<SyncResult.Success>(manager.syncOnce())
        assertTrue(SyncState.UPDATE_AVAILABLE in states)
        assertTrue(SyncState.PREPARING in states)
    }

    @Test
    fun `interrupted download fails and active remains playable`() {
        val fixture = fixtureWithV1()
        val manager = manager(
            fixture,
            RemoteManifestSource { RemoteManifestResult.Available(manifest(2, item("two", second))) },
            RemoteMediaSourceFactory { MediaSource { throw IOException("interrupted") } },
        )

        assertIs<SyncResult.Failed>(manager.syncOnce())
        assertEquals(1L, fixture.store.loadActivePlaylist()?.version)
    }

    @Test
    fun `invalid media checksum rejects update`() {
        val fixture = fixtureWithV1()
        val manager = manager(
            fixture,
            RemoteManifestSource { RemoteManifestResult.Available(manifest(2, item("two", second))) },
            RemoteMediaSourceFactory { MediaSource { ByteArrayInputStream("corrupt".encodeToByteArray()) } },
        )

        assertIs<SyncResult.Failed>(manager.syncOnce())
        assertEquals(1L, fixture.store.loadActivePlaylist()?.version)
    }

    @Test
    fun `valid update commits active and retains previous`() {
        val fixture = fixtureWithV1()
        val events = mutableListOf<SyncEvent>()
        val manager = manager(
            fixture,
            RemoteManifestSource { RemoteManifestResult.Available(manifest(2, item("two", second))) },
            events = SyncEventSink { event, _ -> events += event },
        )

        assertEquals(2L, assertIs<SyncResult.Success>(manager.syncOnce()).version)
        assertEquals(2L, fixture.store.publicationState()?.active?.playlistVersion)
        assertEquals(1L, fixture.store.publicationState()?.previous?.playlistVersion)
        assertTrue(SyncEvent.SYNC_COMMIT_SUCCESS in events)
    }

    @Test
    fun `partially available playlist is never committed`() {
        val fixture = fixtureWithV1()
        val remote = manifest(2, item("one", first), item("two", second, 1))
        val manager = manager(
            fixture,
            RemoteManifestSource { RemoteManifestResult.Available(remote) },
            RemoteMediaSourceFactory { item -> if (item.id == "one") source(first) else null },
        )

        assertIs<SyncResult.Failed>(manager.syncOnce())
        assertEquals(1L, fixture.store.loadActivePlaylist()?.version)
    }

    @Test
    fun `retry policy backs off and then returns to regular window`() {
        val policy = SyncRetryPolicy(10, 20, 40, 1_000)

        assertEquals(listOf(10L, 20L, 40L, 1_000L), (1..4).map(policy::delayAfterFailure))
    }

    @Test
    fun `restart recovers committed remote update`() {
        val fixture = fixtureWithV1()
        val manager = manager(fixture, RemoteManifestSource { RemoteManifestResult.Available(manifest(2, item("two", second))) })
        assertIs<SyncResult.Success>(manager.syncOnce())

        val reopened = TransactionalPlaylistStore(fixture.root, SpacePolicy { _, _ -> true })

        assertEquals(2L, reopened.loadActivePlaylist()?.version)
    }

    @Test
    fun `offline synchronization does not invalidate playback snapshot`() {
        val fixture = fixtureWithV1()
        val playback = fixture.store.loadActivePlaylist()!!
        val file = File(java.net.URI(playback.items.single().media.reference.localUri!!))
        val manager = manager(fixture, RemoteManifestSource { RemoteManifestResult.Offline("offline") })

        assertIs<SyncResult.Offline>(manager.syncOnce())
        assertTrue(file.isFile)
        assertEquals(first.toList(), file.readBytes().toList())
    }

    @Test
    fun `simultaneous synchronization is rejected`() {
        val fixture = fixtureWithV1()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val manager = manager(fixture, RemoteManifestSource {
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            RemoteManifestResult.Unchanged
        })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val firstRun = executor.submit<SyncResult> { manager.syncOnce() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertIs<SyncResult.AlreadyRunning>(manager.syncOnce())
            release.countDown()
            assertIs<SyncResult.UpToDate>(firstRun.get(2, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    private fun manager(
        fixture: Fixture,
        remote: RemoteManifestSource,
        media: RemoteMediaSourceFactory = RemoteMediaSourceFactory { item ->
            when (item.id) {
                "one" -> source(first)
                "two" -> source(second)
                else -> null
            }
        },
        events: SyncEventSink = SyncEventSink { _, _ -> },
    ) = SyncManager(
        remote,
        LocalManifestSource { fixture.store.publicationState()?.active?.playlistVersion },
        media,
        fixture.store,
        events,
    )

    private fun fixtureWithV1(): Fixture {
        val root = Files.createTempDirectory("sync").toFile()
        val store = TransactionalPlaylistStore(root, SpacePolicy { _, _ -> true })
        val manifest = manifest(1, item("one", first))
        val prepared = assertIs<PreparationResult.Ready>(store.prepare(manifest) { source(first) })
        assertIs<PublicationResult.Committed>(store.commit(prepared.versionRef))
        return Fixture(root, store)
    }

    private fun manifest(version: Long, vararg items: ManifestItem) = MediaManifest(
        playlistId = "remote-playlist",
        playlistVersion = version,
        generatedAtEpochMs = version,
        items = items.toList(),
    )

    private fun item(id: String, bytes: ByteArray, order: Int = 0) = ManifestItem(
        id = id,
        type = MediaType.VIDEO,
        remoteUrl = "https://cdn.example/$id.mp4",
        localFileName = "$id.mp4",
        order = order,
        expectedSizeBytes = bytes.size.toLong(),
        sha256 = hash(bytes),
    )

    private fun source(bytes: ByteArray) = MediaSource { ByteArrayInputStream(bytes) }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private data class Fixture(val root: File, val store: TransactionalPlaylistStore)
}
