package com.loopin.player2.core.operations

import com.loopin.player2.core.model.DeviceIdentity

enum class PairingState { UNPAIRED, PAIRING, PAIRED, PAIRING_ERROR }

data class DeviceAssignment(
    val establishmentId: String? = null,
    val screenName: String? = null,
    val city: String? = null,
    val logicalLocation: String? = null,
    val playlistId: String? = null,
    val deviceSettings: Map<String, String> = emptyMap(),
)

data class PairingSnapshot(
    val state: PairingState = PairingState.UNPAIRED,
    val assignment: DeviceAssignment? = null,
    val error: String? = null,
)

interface PairingStore {
    fun load(): PairingSnapshot
    fun save(snapshot: PairingSnapshot)
}

class DevicePairingManager(private val store: PairingStore) {
    @Volatile private var current = store.load()
    fun snapshot(): PairingSnapshot = current
    @Synchronized fun begin(): PairingSnapshot = persist(PairingSnapshot(PairingState.PAIRING))
    @Synchronized fun complete(assignment: DeviceAssignment): PairingSnapshot =
        persist(PairingSnapshot(PairingState.PAIRED, assignment))
    @Synchronized fun fail(reason: String): PairingSnapshot =
        persist(PairingSnapshot(PairingState.PAIRING_ERROR, error = reason.take(256)))
    @Synchronized fun reset(): PairingSnapshot = persist(PairingSnapshot())
    private fun persist(value: PairingSnapshot): PairingSnapshot = value.also { store.save(it); current = it }
}

enum class ConnectionStatus { ONLINE, OFFLINE }
enum class SyncHealth { OK, SYNCING, ERROR, NEVER_SYNCED }
enum class CacheHealth { OK, INCOMPLETE, ERROR }
enum class PlayerOperationalState { PLAYING, PAUSED, ERROR, OFFLINE, IDLE }
enum class HealthState { HEALTHY, DEGRADED, ERROR }

data class DeviceHealthSnapshot(
    val internalId: String,
    val friendlyCode: String,
    val collectedAtEpochMs: Long,
    val uptimeMs: Long,
    val availableMemoryBytes: Long,
    val freeStorageBytes: Long,
    val totalStorageBytes: Long,
    val connection: ConnectionStatus,
    val appVersion: String,
    val playbackState: PlayerOperationalState,
    val cacheState: CacheHealth,
    val syncState: SyncHealth,
    val lastSyncEpochMs: Long?,
    val lastError: String?,
    val healthState: HealthState,
)

fun interface DeviceHealthCollector { fun collect(): DeviceHealthSnapshot }

class DeviceHealthManager(private val collector: DeviceHealthCollector) {
    @Volatile private var last: DeviceHealthSnapshot? = null
    fun collectNow(): DeviceHealthSnapshot = collector.collect().also { last = it }
    fun lastSnapshot(): DeviceHealthSnapshot? = last
}

data class DeviceHeartbeat(
    val deviceCode: String,
    val timestampEpochMs: Long,
    val appVersion: String,
    val connection: ConnectionStatus,
    val playbackState: PlayerOperationalState,
    val cacheState: CacheHealth,
    val freeStorageBytes: Long,
    val lastSyncEpochMs: Long?,
    val healthState: HealthState,
)

fun interface HeartbeatSource { fun create(): DeviceHeartbeat }

class LocalHeartbeatSource(private val healthManager: DeviceHealthManager) : HeartbeatSource {
    override fun create(): DeviceHeartbeat = healthManager.collectNow().let {
        DeviceHeartbeat(it.friendlyCode, it.collectedAtEpochMs, it.appVersion, it.connection,
            it.playbackState, it.cacheState, it.freeStorageBytes, it.lastSyncEpochMs, it.healthState)
    }
}

enum class CommandType {
    RELOAD_PLAYLIST, SYNC_NOW, RESTART_PLAYER, CLEAR_CACHE, CHECK_UPDATE,
    REBOOT_DEVICE, CAPTURE_SCREENSHOT, GET_STATUS, UNKNOWN;
    companion object { fun parse(value: String): CommandType = entries.firstOrNull { it.name == value } ?: UNKNOWN }
}

data class RemoteCommand(val id: String, val type: CommandType, val payload: String? = null)
sealed interface CommandResult {
    data object Deferred : CommandResult
    data class Unsupported(val rawType: String) : CommandResult
}
fun interface CommandExecutor { fun execute(command: RemoteCommand): CommandResult }
class DeferredCommandExecutor : CommandExecutor {
    override fun execute(command: RemoteCommand): CommandResult = if (command.type == CommandType.UNKNOWN)
        CommandResult.Unsupported(CommandType.UNKNOWN.name) else CommandResult.Deferred
}

enum class UpdateChannel { STABLE, BETA }
enum class OperationalUpdateState {
    UP_TO_DATE, UPDATE_AVAILABLE, DOWNLOADING, DOWNLOADED, VALIDATING,
    INSTALLATION_UNAVAILABLE, INSTALLING, INSTALL_FAILED, INVALID
}

data class OperationalUpdateSnapshot(
    val currentVersion: String,
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val state: OperationalUpdateState = OperationalUpdateState.UP_TO_DATE,
    val availableVersion: String? = null,
    val lastError: String? = null,
)

interface UpdateSettingsStore { fun loadChannel(): UpdateChannel; fun saveChannel(channel: UpdateChannel) }
class OperationalUpdateManager(private val store: UpdateSettingsStore, currentVersion: String) {
    @Volatile private var current = OperationalUpdateSnapshot(currentVersion, store.loadChannel())
    fun snapshot(): OperationalUpdateSnapshot = current
    @Synchronized fun setChannel(channel: UpdateChannel) { store.saveChannel(channel); current = current.copy(channel = channel) }
    @Synchronized fun update(state: OperationalUpdateState, version: String? = current.availableVersion, error: String? = null) {
        current = current.copy(state = state, availableVersion = version, lastError = error)
    }
}

object OperationalLogEvent {
    const val DEVICE_STARTED = "DEVICE_STARTED"; const val DEVICE_READY = "DEVICE_READY"
    const val DEVICE_OFFLINE = "DEVICE_OFFLINE"; const val DEVICE_ONLINE = "DEVICE_ONLINE"
    const val PAIRING_STARTED = "PAIRING_STARTED"; const val PAIRING_SUCCESS = "PAIRING_SUCCESS"
    const val PAIRING_FAILED = "PAIRING_FAILED"; const val SYNC_STARTED = "SYNC_STARTED"
    const val SYNC_SUCCESS = "SYNC_SUCCESS"; const val SYNC_FAILED = "SYNC_FAILED"
    const val UPDATE_AVAILABLE = "UPDATE_AVAILABLE"; const val UPDATE_DOWNLOAD_STARTED = "UPDATE_DOWNLOAD_STARTED"
    const val UPDATE_DOWNLOAD_SUCCESS = "UPDATE_DOWNLOAD_SUCCESS"; const val UPDATE_VALIDATION_FAILED = "UPDATE_VALIDATION_FAILED"
    const val UPDATE_INSTALL_STARTED = "UPDATE_INSTALL_STARTED"; const val UPDATE_INSTALL_FAILED = "UPDATE_INSTALL_FAILED"
    const val CACHE_ERROR = "CACHE_ERROR"; const val PLAYBACK_ERROR = "PLAYBACK_ERROR"
}
