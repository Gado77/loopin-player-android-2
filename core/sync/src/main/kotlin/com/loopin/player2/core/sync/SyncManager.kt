package com.loopin.player2.core.sync

import com.loopin.player2.core.cache.PreparationResult
import com.loopin.player2.core.cache.PublicationResult
import com.loopin.player2.core.cache.TransactionalPlaylistStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

enum class SyncState {
    IDLE, CHECKING, UP_TO_DATE, UPDATE_AVAILABLE, PREPARING, DOWNLOADING,
    VALIDATING, COMMITTING, SUCCESS, FAILED, OFFLINE,
}

data class SyncSnapshot(
    val state: SyncState = SyncState.IDLE,
    val localVersion: Long? = null,
    val remoteVersion: Long? = null,
    val detail: String? = null,
)

enum class SyncEvent {
    SYNC_STARTED,
    SYNC_CHECKED,
    SYNC_UP_TO_DATE,
    SYNC_UPDATE_AVAILABLE,
    SYNC_DOWNLOAD_STARTED,
    SYNC_DOWNLOAD_COMPLETED,
    SYNC_VALIDATION_FAILED,
    SYNC_COMMIT_STARTED,
    SYNC_COMMIT_SUCCESS,
    SYNC_FAILED,
    SYNC_OFFLINE,
}

fun interface SyncEventSink {
    fun record(event: SyncEvent, detail: String?)
}

sealed interface SyncResult {
    data class Success(val version: Long) : SyncResult
    data class UpToDate(val version: Long?) : SyncResult
    data class Failed(val reason: String, val retryable: Boolean) : SyncResult
    data class Offline(val reason: String) : SyncResult
    data object AlreadyRunning : SyncResult
}

class SyncManager(
    private val remoteManifestSource: RemoteManifestSource,
    private val localManifestSource: LocalManifestSource,
    private val mediaSourceFactory: RemoteMediaSourceFactory,
    private val store: TransactionalPlaylistStore,
    private val events: SyncEventSink = SyncEventSink { _, _ -> },
) {
    private val running = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<(SyncSnapshot) -> Unit>()

    @Volatile
    var snapshot: SyncSnapshot = SyncSnapshot()
        private set

    fun syncOnce(): SyncResult {
        if (!running.compareAndSet(false, true)) return SyncResult.AlreadyRunning
        return try {
            val localVersion = localManifestSource.activeVersion()
            publish(SyncSnapshot(SyncState.CHECKING, localVersion))
            emit(SyncEvent.SYNC_STARTED, "localVersion=$localVersion")
            when (val remote = remoteManifestSource.fetch(localVersion)) {
                RemoteManifestResult.Unchanged -> upToDate(localVersion)
                is RemoteManifestResult.Offline -> offline(remote.reason, localVersion)
                is RemoteManifestResult.Failed -> failed(remote.reason, remote.retryable, localVersion)
                is RemoteManifestResult.Available -> synchronizeManifest(remote.manifest, localVersion)
            }
        } finally {
            running.set(false)
        }
    }

    fun subscribe(listener: (SyncSnapshot) -> Unit): AutoCloseable {
        listeners += listener
        listener(snapshot)
        return AutoCloseable { listeners -= listener }
    }

    private fun synchronizeManifest(manifest: com.loopin.player2.core.cache.MediaManifest, localVersion: Long?): SyncResult {
        emit(SyncEvent.SYNC_CHECKED, "remoteVersion=${manifest.playlistVersion}")
        if (localVersion != null && manifest.playlistVersion <= localVersion) return upToDate(localVersion)
        publish(SyncSnapshot(SyncState.UPDATE_AVAILABLE, localVersion, manifest.playlistVersion))
        emit(SyncEvent.SYNC_UPDATE_AVAILABLE, "remoteVersion=${manifest.playlistVersion}")
        publish(snapshot.copy(state = SyncState.PREPARING))
        publish(snapshot.copy(state = SyncState.DOWNLOADING))
        emit(SyncEvent.SYNC_DOWNLOAD_STARTED, "items=${manifest.items.size}")
        val prepared = store.prepare(manifest, mediaSourceFactory::sourceFor)
        if (prepared !is PreparationResult.Ready) {
            val reason = (prepared as PreparationResult.Rejected).reason
            emit(SyncEvent.SYNC_VALIDATION_FAILED, reason)
            return failed(reason, true, localVersion, manifest.playlistVersion)
        }
        emit(SyncEvent.SYNC_DOWNLOAD_COMPLETED, "created=${prepared.createdObjects} reused=${prepared.reusedObjects}")
        publish(snapshot.copy(state = SyncState.VALIDATING))
        publish(snapshot.copy(state = SyncState.COMMITTING))
        emit(SyncEvent.SYNC_COMMIT_STARTED, "version=${manifest.playlistVersion}")
        return when (val commit = store.commit(prepared.versionRef)) {
            is PublicationResult.Committed -> {
                publish(SyncSnapshot(SyncState.SUCCESS, commit.activeVersion, manifest.playlistVersion))
                emit(SyncEvent.SYNC_COMMIT_SUCCESS, "active=${commit.activeVersion}")
                SyncResult.Success(commit.activeVersion)
            }
            is PublicationResult.Rejected -> failed(commit.reason, true, localVersion, manifest.playlistVersion)
        }
    }

    private fun upToDate(version: Long?): SyncResult {
        publish(SyncSnapshot(SyncState.UP_TO_DATE, version, version))
        emit(SyncEvent.SYNC_UP_TO_DATE, "version=$version")
        return SyncResult.UpToDate(version)
    }

    private fun offline(reason: String, localVersion: Long?): SyncResult {
        publish(SyncSnapshot(SyncState.OFFLINE, localVersion, detail = reason))
        emit(SyncEvent.SYNC_OFFLINE, reason)
        return SyncResult.Offline(reason)
    }

    private fun failed(reason: String, retryable: Boolean, local: Long?, remote: Long? = null): SyncResult {
        publish(SyncSnapshot(SyncState.FAILED, local, remote, reason))
        emit(SyncEvent.SYNC_FAILED, reason)
        return SyncResult.Failed(reason, retryable)
    }

    private fun publish(value: SyncSnapshot) {
        snapshot = value
        listeners.forEach { it(value) }
    }

    private fun emit(event: SyncEvent, detail: String?) = runCatching { events.record(event, detail) }
}

data class SyncRetryPolicy(
    val shortDelayMs: Long = 30_000L,
    val mediumDelayMs: Long = 2L * 60L * 1_000L,
    val longDelayMs: Long = 10L * 60L * 1_000L,
    val regularIntervalMs: Long = 6L * 60L * 60L * 1_000L,
) {
    fun delayAfterFailure(consecutiveFailures: Int): Long = when (consecutiveFailures) {
        1 -> shortDelayMs
        2 -> mediumDelayMs
        3 -> longDelayMs
        else -> regularIntervalMs
    }
}
