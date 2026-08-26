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
import com.loopin.player2.core.sync.HttpRemoteManifestSource
import com.loopin.player2.core.sync.HttpRemoteMediaSourceFactory
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
            }
        }
        container.syncScheduler?.schedule()
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
    val syncManager: SyncManager?,
    val syncScheduler: ContentSyncScheduler?,
    val pairingManager: DevicePairingManager,
    val healthManager: DeviceHealthManager,
    val heartbeatSource: HeartbeatSource,
    val commandExecutor: CommandExecutor,
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
            val syncManager = if (syncConfig.enabled) SyncManager(
                remoteManifestSource = HttpRemoteManifestSource(syncConfig.manifestUrl),
                localManifestSource = LocalManifestSource { transactionalStore.publicationState()?.active?.playlistVersion },
                mediaSourceFactory = HttpRemoteMediaSourceFactory(),
                store = transactionalStore,
                events = SyncEventSink { event, detail ->
                    logger.log(LogLevel.INFO, "ContentSync", "${event.name}${detail?.let { " $it" }.orEmpty()}")
                    when (event.name) {
                        "SYNC_STARTED" -> operationalState.syncStarted()
                        "SYNC_COMMIT_SUCCESS", "SYNC_UP_TO_DATE" -> operationalState.syncSucceeded()
                        "SYNC_FAILED", "SYNC_VALIDATION_FAILED" -> operationalState.syncFailed(detail ?: event.name)
                    }
                },
            ) else null
            val syncScheduler = if (syncManager != null) ContentSyncScheduler(application, syncConfig, logger) else null
            val pairing = DevicePairingManager(AndroidPairingStore(application))
            val health = DeviceHealthManager(AndroidDeviceHealthCollector(application, config.identity, stateManager, operationalState, transactionalStore))
            return AppContainer(
                config = config,
                logger = logger,
                stateManager = stateManager,
                networkStateObserver = NetworkStateObserver(application, stateManager, logger),
                telemetry = DeferredTelemetrySink(),
                remoteCommands = DeferredRemoteCommandHandler(),
                playlistRepository = LocalTestPlaylistRepository(application, logger, transactionalStore),
                syncManager = syncManager,
                syncScheduler = syncScheduler,
                pairingManager = pairing,
                healthManager = health,
                heartbeatSource = LocalHeartbeatSource(health),
                commandExecutor = DeferredCommandExecutor(),
                updateManager = OperationalUpdateManager(AndroidUpdateSettingsStore(application), BuildConfig.VERSION_NAME),
                operationalState = operationalState,
            )
        }
    }
}
