package com.loopin.player2.core.operations

import com.loopin.player2.core.model.DeviceIdentity
import java.util.concurrent.atomic.AtomicBoolean

enum class PairingState { UNPAIRED, PAIRING, PAIRED, PAIRING_ERROR }

data class PairingWindow(
    val pairingToken: String,
    val pairingCode: String,
    val qrPayload: String,
    val expiresAtElapsedMs: Long,
) {
    init {
        require(pairingToken.length >= 32)
        require(Regex("[0-9]{6}").matches(pairingCode))
        require(qrPayload.isNotBlank())
        require(expiresAtElapsedMs > 0)
    }
    fun isExpired(nowElapsedMs: Long): Boolean = nowElapsedMs >= expiresAtElapsedMs
    fun secondsRemaining(nowElapsedMs: Long): Int =
        ((expiresAtElapsedMs - nowElapsedMs + 999L) / 1_000L).toInt().coerceAtLeast(0)
}

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

data class DeviceHeartbeatPayload(
    val action: String = "heartbeat",
    val appVersion: String,
    val metadata: Map<String, Any?>,
)

object DeviceHeartbeatPayloadFactory {
    fun create(heartbeat: DeviceHeartbeat): DeviceHeartbeatPayload = DeviceHeartbeatPayload(
        appVersion = heartbeat.appVersion.take(100),
        metadata = linkedMapOf(
            "connection" to heartbeat.connection.name,
            "playback_state" to heartbeat.playbackState.name,
            "cache_state" to heartbeat.cacheState.name,
            "health_state" to heartbeat.healthState.name,
            "free_storage_bytes" to heartbeat.freeStorageBytes.coerceAtLeast(0L),
            "last_sync_epoch_ms" to heartbeat.lastSyncEpochMs,
        ),
    )
}

class DeviceHeartbeatRequest private constructor(
    val endpoint: String,
    val headers: Map<String, String>,
    val payload: DeviceHeartbeatPayload,
) {
    override fun toString(): String = "DeviceHeartbeatRequest(endpoint=$endpoint, headers=[redacted], payload=$payload)"

    companion object {
        fun create(endpoint: String, credential: String, heartbeat: DeviceHeartbeat): DeviceHeartbeatRequest {
            require(endpoint.startsWith("https://")) { "Heartbeat endpoint must use HTTPS" }
            require(credential.length in 40..128) { "Device credential is invalid" }
            return DeviceHeartbeatRequest(
                endpoint = endpoint,
                headers = mapOf(
                    "Authorization" to "Bearer $credential",
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                ),
                payload = DeviceHeartbeatPayloadFactory.create(heartbeat),
            )
        }
    }
}

sealed interface HeartbeatTransportResult {
    data object Success : HeartbeatTransportResult
    data object Unauthorized : HeartbeatTransportResult
    data class Failed(val httpStatus: Int? = null, val retryable: Boolean) : HeartbeatTransportResult
}

fun heartbeatTransportResultForStatus(status: Int): HeartbeatTransportResult = when (status) {
    in 200..299 -> HeartbeatTransportResult.Success
    401 -> HeartbeatTransportResult.Unauthorized
    else -> HeartbeatTransportResult.Failed(
        httpStatus = status,
        retryable = status == 408 || status == 429 || status >= 500,
    )
}

fun interface DeviceHeartbeatTransport {
    fun send(request: DeviceHeartbeatRequest): HeartbeatTransportResult
}

sealed interface HeartbeatDispatchResult {
    data object Success : HeartbeatDispatchResult
    data object Unauthorized : HeartbeatDispatchResult
    data class Failed(val retryable: Boolean) : HeartbeatDispatchResult
    data object SkippedUnpaired : HeartbeatDispatchResult
    data object SkippedMissingCredential : HeartbeatDispatchResult
    data object AlreadyRunning : HeartbeatDispatchResult
}

class DeviceHeartbeatDispatcher(
    private val endpoint: String,
    private val pairingState: () -> PairingState,
    private val credential: () -> String?,
    private val heartbeatSource: HeartbeatSource,
    private val transport: DeviceHeartbeatTransport,
) {
    private val running = AtomicBoolean(false)

    fun dispatch(): HeartbeatDispatchResult {
        if (pairingState() != PairingState.PAIRED) return HeartbeatDispatchResult.SkippedUnpaired
        val secret = credential()?.takeIf { it.length in 40..128 }
            ?: return HeartbeatDispatchResult.SkippedMissingCredential
        if (!running.compareAndSet(false, true)) return HeartbeatDispatchResult.AlreadyRunning
        return try {
            when (val result = transport.send(DeviceHeartbeatRequest.create(endpoint, secret, heartbeatSource.create()))) {
                HeartbeatTransportResult.Success -> HeartbeatDispatchResult.Success
                HeartbeatTransportResult.Unauthorized -> HeartbeatDispatchResult.Unauthorized
                is HeartbeatTransportResult.Failed -> HeartbeatDispatchResult.Failed(result.retryable)
            }
        } finally {
            running.set(false)
        }
    }
}

class HeartbeatBackoffPolicy(
    private val normalIntervalMs: Long = 5L * 60L * 1_000L,
    private val authenticationDelayMs: Long = 60L * 60L * 1_000L,
) {
    fun delayAfter(result: HeartbeatDispatchResult, consecutiveFailures: Int): Long = when (result) {
        HeartbeatDispatchResult.Success -> normalIntervalMs
        HeartbeatDispatchResult.Unauthorized -> authenticationDelayMs
        is HeartbeatDispatchResult.Failed -> if (!result.retryable) normalIntervalMs else when (consecutiveFailures) {
            1 -> 60_000L
            2 -> 5L * 60L * 1_000L
            3 -> 15L * 60L * 1_000L
            else -> 30L * 60L * 1_000L
        }
        HeartbeatDispatchResult.SkippedUnpaired,
        HeartbeatDispatchResult.SkippedMissingCredential -> authenticationDelayMs
        HeartbeatDispatchResult.AlreadyRunning -> normalIntervalMs
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
