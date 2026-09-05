package com.loopin.player2

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import com.loopin.player2.core.cache.TransactionalPlaylistStore
import com.loopin.player2.core.model.DeviceIdentity
import com.loopin.player2.core.model.DynamicMediaContent
import com.loopin.player2.core.model.NormalMediaContent
import com.loopin.player2.core.model.Playlist
import com.loopin.player2.core.operations.*
import com.loopin.player2.core.playback.PlaybackState

class OperationalStateRegistry(context: Context) {
    private val preferences = context.getSharedPreferences("loopin_operational_state", Context.MODE_PRIVATE)
    @Volatile var playbackState = PlayerOperationalState.IDLE
    @Volatile var syncState = SyncHealth.NEVER_SYNCED
    @Volatile var cacheState = CacheHealth.INCOMPLETE
    @Volatile var updateState = OperationalUpdateState.UP_TO_DATE
    @Volatile var currentItemId: String? = null
    @Volatile var currentContentKind: String? = null
    @Volatile var currentMediaType: String? = null
    private var itemDiagnostics: Map<String, Pair<String, String?>> = emptyMap()
    val lastSyncEpochMs: Long? get() = preferences.getLong("last_sync", 0L).takeIf { it > 0 }
    val lastError: String? get() = preferences.getString("last_error", null)
    val lastErrorCode: String? get() = preferences.getString("last_error_code", null)
    val lastErrorAtEpochMs: Long? get() = preferences.getLong("last_error_at", 0L).takeIf { it > 0 }

    fun playlist(playlist: Playlist?) { itemDiagnostics = playlist?.items?.associate { item -> item.id to when(val content=item.content) {
        is NormalMediaContent -> "MEDIA" to content.media.type.name
        is DynamicMediaContent -> "DYNAMIC" to content.type.name
    } } ?: emptyMap() }
    fun currentItem(id: String?) { currentItemId=id;val value=id?.let(itemDiagnostics::get);currentContentKind=value?.first;currentMediaType=value?.second }

    fun playback(state: PlaybackState, error: String? = null) {
        playbackState = when (state) {
            PlaybackState.PLAYING -> PlayerOperationalState.PLAYING
            PlaybackState.PAUSED -> PlayerOperationalState.PAUSED
            PlaybackState.ERROR -> PlayerOperationalState.ERROR
            else -> PlayerOperationalState.IDLE
        }
        if (error != null) recordError("PLAYBACK_MEDIA_ERROR", error)
    }

    fun syncStarted() { syncState = SyncHealth.SYNCING }
    fun syncSucceeded() {
        syncState = SyncHealth.OK
        preferences.edit().putLong("last_sync", System.currentTimeMillis()).remove("last_error").remove("last_error_code").remove("last_error_at").apply()
    }
    fun syncFailed(error: String) { syncState = SyncHealth.ERROR; recordError(syncErrorCode(error), error) }
    fun recordError(code:String,error: String) { preferences.edit().putString("last_error", DeviceRuntimeSnapshotFactory.sanitizeError(error)).putString("last_error_code",code.take(64)).putLong("last_error_at",System.currentTimeMillis()).apply() }
    private fun syncErrorCode(error:String)=when{error.contains("checksum",true)->"SYNC_CHECKSUM_FAILED";error.contains("manifest",true)->"SYNC_MANIFEST_INVALID";error.contains("download",true)->"SYNC_DOWNLOAD_FAILED";else->"SYNC_NETWORK_ERROR"}
}

class AndroidPairingStore(context: Context) : PairingStore {
    private val prefs = context.getSharedPreferences("loopin_pairing", Context.MODE_PRIVATE)
    override fun load(): PairingSnapshot {
        val state = runCatching { PairingState.valueOf(prefs.getString("state", PairingState.UNPAIRED.name)!!) }
            .getOrDefault(PairingState.UNPAIRED)
        val assignment = if (state == PairingState.PAIRED) DeviceAssignment(
            establishmentId = prefs.getString("establishment_id", null), screenName = prefs.getString("screen_name", null),
            city = prefs.getString("city", null), logicalLocation = prefs.getString("logical_location", null),
            playlistId = prefs.getString("playlist_id", null),
        ) else null
        return PairingSnapshot(state, assignment, prefs.getString("error", null))
    }
    override fun save(snapshot: PairingSnapshot) {
        val edit = prefs.edit().clear().putString("state", snapshot.state.name).putString("error", snapshot.error)
        snapshot.assignment?.let {
            edit.putString("establishment_id", it.establishmentId).putString("screen_name", it.screenName)
                .putString("city", it.city).putString("logical_location", it.logicalLocation)
                .putString("playlist_id", it.playlistId)
        }
        check(edit.commit()) { "Pairing state could not be persisted" }
    }
}

class AndroidUpdateSettingsStore(context: Context) : UpdateSettingsStore {
    private val prefs = context.getSharedPreferences("loopin_update_settings", Context.MODE_PRIVATE)
    override fun loadChannel(): UpdateChannel = runCatching {
        UpdateChannel.valueOf(prefs.getString("channel", UpdateChannel.STABLE.name)!!)
    }.getOrDefault(UpdateChannel.STABLE)
    override fun saveChannel(channel: UpdateChannel) { check(prefs.edit().putString("channel", channel.name).commit()) }
}

class AndroidDeviceHealthCollector(
    private val context: Context,
    private val identity: DeviceIdentity,
    private val stateManager: com.loopin.player2.core.foundation.DeviceStateManager,
    private val operational: OperationalStateRegistry,
    private val store: TransactionalPlaylistStore,
    private val sessionId: String,
    private val updateManager: OperationalUpdateManager,
) : DeviceHealthCollector {
    override fun collect(): DeviceHealthSnapshot {
        val memory = ActivityManager.MemoryInfo().also {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        val storage = StatFs(Environment.getDataDirectory().absolutePath)
        operational.cacheState = if (store.publicationState()?.active != null) CacheHealth.OK else CacheHealth.INCOMPLETE
        val online = stateManager.snapshot().networkAvailable
        val playback = if (!online && operational.playbackState == PlayerOperationalState.IDLE)
            PlayerOperationalState.OFFLINE else operational.playbackState
        val publication = store.publicationState()
        val storageLow = storage.availableBytes < 500L * 1024L * 1024L || (storage.totalBytes > 0 && storage.availableBytes * 10L < storage.totalBytes)
        val health = when {
            playback == PlayerOperationalState.ERROR -> HealthState.ERROR
            operational.cacheState != CacheHealth.OK || operational.syncState == SyncHealth.ERROR || memory.lowMemory || storageLow -> HealthState.DEGRADED
            else -> HealthState.HEALTHY
        }
        return DeviceHealthSnapshot(identity.internalId, identity.friendlyCode, System.currentTimeMillis(),
            SystemClock.elapsedRealtime(), memory.availMem, storage.availableBytes, storage.totalBytes,
            if (online) ConnectionStatus.ONLINE else ConnectionStatus.OFFLINE, BuildConfig.VERSION_NAME,
            playback, operational.cacheState, operational.syncState, operational.lastSyncEpochMs,
            operational.lastError, health, sessionId, memory.lowMemory,
            publication?.active?.playlistId, publication?.active?.playlistVersion, publication?.active?.manifestSha256,
            publication?.previous?.playlistId, operational.currentItemId, operational.currentContentKind,
            operational.currentMediaType, operational.lastErrorCode, operational.lastErrorAtEpochMs,
            updateManager.snapshot().channel.name, BuildConfig.VERSION_CODE.toLong(), updateManager.snapshot().state.name,
            updateManager.snapshot().availableVersionCode, updateManager.snapshot().preparedVersionCode,
            updateManager.snapshot().lastCheckEpochMs, updateManager.snapshot().lastError, "INTERACTIVE")
    }
}
