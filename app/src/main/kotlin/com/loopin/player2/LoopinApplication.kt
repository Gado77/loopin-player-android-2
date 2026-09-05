package com.loopin.player2

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.loopin.player2.core.foundation.BoundedFileLogger
import com.loopin.player2.core.foundation.DeferredRemoteCommandHandler
import com.loopin.player2.core.foundation.DeferredTelemetrySink
import com.loopin.player2.core.foundation.DeviceStateManager
import com.loopin.player2.core.foundation.EssentialConfigStore
import com.loopin.player2.core.foundation.GlobalExceptionHandler
import com.loopin.player2.core.foundation.NetworkStateObserver
import com.loopin.player2.core.model.EssentialDeviceConfig
import com.loopin.player2.core.model.LogLevel
import com.loopin.player2.core.model.PlayerLogger
import com.loopin.player2.core.model.PlaylistRepository
import com.loopin.player2.core.model.RemoteCommandHandler
import com.loopin.player2.core.model.TelemetryEvent
import com.loopin.player2.core.model.TelemetrySink
import com.loopin.player2.core.cache.TransactionalPlaylistStore
import com.loopin.player2.core.sync.AuthenticatedRemoteManifestSource
import com.loopin.player2.core.sync.AuthenticatedRemoteMediaSourceFactory
import com.loopin.player2.core.sync.LocalManifestSource
import com.loopin.player2.core.sync.SyncEventSink
import com.loopin.player2.core.sync.SyncManager
import com.loopin.player2.core.operations.*

class LoopinApplication : Application(), Application.ActivityLifecycleCallbacks {
    lateinit var container: AppContainer
        private set

    private var visibleActivityCount = 0
    private var lastReportedNetwork: Boolean? = null

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.create(this)
        registerActivityLifecycleCallbacks(this)
        installExceptionHandler()
        container.networkStateObserver.start()
        container.stateManager.subscribe { state ->
            if (lastReportedNetwork != state.networkAvailable) {
                lastReportedNetwork = state.networkAvailable
                container.logger.log(LogLevel.INFO, TAG, if (state.networkAvailable)
                    OperationalLogEvent.DEVICE_ONLINE else OperationalLogEvent.DEVICE_OFFLINE)
                if (state.networkAvailable && container.pairingManager.snapshot().state == PairingState.PAIRED) {
                    container.heartbeatScheduler.schedule()
                    container.syncScheduler?.schedule(0)
                    container.commandScheduler.schedule()
                }
            }
        }
        if (container.pairingManager.snapshot().state == PairingState.PAIRED) {
            container.syncScheduler?.schedule(0)
            container.commandScheduler.schedule()
        }
        container.telemetry.record(TelemetryEvent("foundation_started", System.currentTimeMillis()))
        container.logger.log(LogLevel.INFO, TAG, OperationalLogEvent.DEVICE_STARTED)
        container.logger.log(LogLevel.INFO, TAG, OperationalLogEvent.DEVICE_READY)
        container.logger.log(
            LogLevel.INFO,
            TAG,
            "Foundation ready for internalId=${container.config.identity.internalId}",
        )
    }

    private fun installExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(
            GlobalExceptionHandler(container.logger, container.stateManager, previous),
        )
    }

    override fun onActivityStarted(activity: Activity) {
        visibleActivityCount += 1
        container.stateManager.setActivityVisible(true)
    }

    override fun onActivityStopped(activity: Activity) {
        visibleActivityCount = (visibleActivityCount - 1).coerceAtLeast(0)
        if (visibleActivityCount == 0) container.stateManager.setActivityVisible(false)
    }

    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        private const val TAG = "LoopinApplication"
    }
}

data class AppContainer(
    val config: EssentialDeviceConfig,
    val logger: PlayerLogger,
    val stateManager: DeviceStateManager,
    val networkStateObserver: NetworkStateObserver,
    val telemetry: TelemetrySink,
    val remoteCommands: RemoteCommandHandler,
    val playlistRepository: PlaylistRepository,
    val playlistActivationNotifier: PlaylistActivationNotifier,
    val syncManager: SyncManager?,
    val syncScheduler: ContentSyncScheduler?,
    val pairingManager: DevicePairingManager,
    val healthManager: DeviceHealthManager,
    val heartbeatSource: HeartbeatSource,
    val heartbeatDispatcher: DeviceHeartbeatDispatcher,
    val heartbeatScheduler: DeviceHeartbeatScheduler,
    val commandExecutor: CommandExecutor,
    val commandDispatcher: DeviceCommandDispatcher,
    val commandScheduler: DeviceCommandScheduler,
    val updateManager: OperationalUpdateManager,
    val operationalState: OperationalStateRegistry,
) {
    companion object {
        fun create(application: Application): AppContainer {
            val logger = BoundedFileLogger(application)
            val stateManager = DeviceStateManager()
            val config = EssentialConfigStore(application).loadOrCreate()
            val transactionalStore = TransactionalPlaylistStore(java.io.File(application.filesDir, "transactional-media"))
            val operationalState = OperationalStateRegistry(application)
            val syncConfig = RemoteSyncConfigStore(application).load()
            val credentialStore = DeviceCredentialStore(application)
            val playlistRepository = LocalTestPlaylistRepository(application, logger, transactionalStore)
            val playlistActivationNotifier = PlaylistActivationNotifier()
            val syncManager = SyncManager(
                remoteManifestSource = AuthenticatedRemoteManifestSource(BuildConfig.MANIFEST_ENDPOINT, credentialStore::credential),
                localManifestSource = LocalManifestSource { transactionalStore.publicationState()?.active },
                mediaSourceFactory = AuthenticatedRemoteMediaSourceFactory(BuildConfig.MEDIA_ENDPOINT, credentialStore::credential),
                store = transactionalStore,
                events = SyncEventSink { event, detail ->
                    logger.log(LogLevel.INFO, "ContentSync", "${event.name}${detail?.let { " $it" }.orEmpty()}")
                    when (event.name) {
                        "SYNC_STARTED" -> operationalState.syncStarted()
                        "SYNC_COMMIT_SUCCESS", "SYNC_UP_TO_DATE" -> operationalState.syncSucceeded()
                        "SYNC_FAILED", "SYNC_VALIDATION_FAILED" -> operationalState.syncFailed(detail ?: event.name)
                    }
                },
                onCommitted = {
                    transactionalStore.loadActivePlaylist()?.let(playlistActivationNotifier::publish)
                },
            )
            val syncScheduler = ContentSyncScheduler(application, syncConfig, logger)
            val pairing = DevicePairingManager(AndroidPairingStore(application))
            val sessionId = newPlayerSessionId()
            val health = DeviceHealthManager(AndroidDeviceHealthCollector(application, config.identity, stateManager, operationalState, transactionalStore, sessionId))
            val heartbeatSource = LocalHeartbeatSource(health)
            val heartbeatScheduler = DeviceHeartbeatScheduler(application, logger)
            val heartbeatDispatcher = DeviceHeartbeatDispatcher(
                endpoint = BuildConfig.PAIRING_ENDPOINT,
                pairingState = { pairing.snapshot().state },
                credential = credentialStore::credential,
                heartbeatSource = heartbeatSource,
                transport = DeviceHeartbeatHttpApi(),
            )
            val commandScheduler = DeviceCommandScheduler(application, logger)
            val commandExecutor = SafeCommandExecutor(
                status = health::collectNow,
                syncNow = {
                    val result = when {
                        syncManager.isRunning() -> "already_running"
                        syncScheduler.schedule(0) -> "scheduled"
                        else -> "schedule_failed"
                    }
                    logger.log(LogLevel.INFO, "DeviceCommands", "COMMAND_SYNC_REQUESTED code=$result")
                    result
                },
                reloadPlaylist = {
                    val result = transactionalStore.loadActivePlaylist()?.let {
                        playlistActivationNotifier.publish(it)
                        "reloaded"
                    } ?: "no_active_playlist"
                    logger.log(LogLevel.INFO, "DeviceCommands", "COMMAND_RELOAD_REQUESTED code=$result")
                    result
                },
            )
            val commandDispatcher = DeviceCommandDispatcher(
                endpoint = BuildConfig.COMMAND_ENDPOINT,
                pairingState = { pairing.snapshot().state },
                credential = credentialStore::credential,
                transport = DeviceCommandHttpApi(),
                executor = commandExecutor,
                executed = SharedPreferencesExecutedCommandStore(application),
            )
            return AppContainer(
                config = config,
                logger = logger,
                stateManager = stateManager,
                networkStateObserver = NetworkStateObserver(application, stateManager, logger),
                telemetry = DeferredTelemetrySink(),
                remoteCommands = DeferredRemoteCommandHandler(),
                playlistRepository = playlistRepository,
                playlistActivationNotifier = playlistActivationNotifier,
                syncManager = syncManager,
                syncScheduler = syncScheduler,
                pairingManager = pairing,
                healthManager = health,
                heartbeatSource = heartbeatSource,
                heartbeatDispatcher = heartbeatDispatcher,
                heartbeatScheduler = heartbeatScheduler,
                commandExecutor = commandExecutor,
                commandDispatcher = commandDispatcher,
                commandScheduler = commandScheduler,
                updateManager = OperationalUpdateManager(AndroidUpdateSettingsStore(application), BuildConfig.VERSION_NAME),
                operationalState = operationalState,
            )
        }
    }
}
