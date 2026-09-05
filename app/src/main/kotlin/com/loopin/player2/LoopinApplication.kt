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
import com.loopin.player2.core.sync.*
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
                    container.updateScheduler.schedule()
                }
            }
        }
        if (container.pairingManager.snapshot().state == PairingState.PAIRED) {
            container.syncScheduler?.schedule(0)
            container.commandScheduler.schedule()
            container.updateScheduler.schedule()
            container.updateAttemptStore.load()?.let { attempt ->
                if (attempt.state == UpdateInstallationState.POST_UPDATE_VERIFYING) {
                    container.updateSource.reportInstall(attempt)
                    container.heartbeatScheduler.schedule()
                }
            }
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
    val updateCoordinator: UpdateCoordinator,
    val updateScheduler: DeviceUpdateScheduler,
    val updateInstallCoordinator: UpdateInstallCoordinator,
    val updateAttemptStore: UpdateInstallAttemptStore,
    val updateSource: AuthenticatedPlayerUpdateSource,
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
            val updateState = OperationalUpdateManager(AndroidUpdateSettingsStore(application), BuildConfig.VERSION_NAME)
            val updateScheduler = DeviceUpdateScheduler(application)
            val updateSource = AuthenticatedPlayerUpdateSource(BuildConfig.UPDATE_ENDPOINT, credentialStore::credential, BuildConfig.VERSION_CODE.toLong()) { channel -> runCatching { updateState.setChannel(UpdateChannel.valueOf(channel)) } }
            val platformInstaller=AndroidPackageUpdateInstaller(application)
            val attemptStore=SharedPreferencesUpdateAttemptStore(application)
            val preparedStore=SharedPreferencesPreparedUpdateStore(application)
            val apkManager = PlayerUpdateManager(updateSource, updateSource,
                object:PlayerInstaller{override fun availability()=InstallerAvailability.REQUIRES_USER_ACTION;override fun install(apk:java.io.File)=InstallationResult.UserActionRequired},
                AndroidApkSignatureVerifier(application), java.io.File(application.filesDir,"updates"), installedVersionCode=BuildConfig.VERSION_CODE.toLong(),
                preparedStore=preparedStore)
            preparedStore.load()?.takeIf { it.apk.isFile&&it.info.versionCode>BuildConfig.VERSION_CODE.toLong() }?.let {
                updateState.update(OperationalUpdateState.READY_TO_INSTALL,it.info.versionName,versionCode=it.info.versionCode)
            }
            val updateCoordinator = UpdateCoordinator(apkManager, updateState, BuildConfig.VERSION_CODE.toLong())
            val installCoordinator=UpdateInstallCoordinator(apkManager,UpdateInstallAuthorizer(updateSource::authorizeInstall),platformInstaller,attemptStore,BuildConfig.VERSION_CODE.toLong(),{pairing.snapshot().state==PairingState.PAIRED})
            installCoordinator.verifyAfterStartup(
                config.identity.internalId.isNotBlank(),
                pairing.snapshot().state == PairingState.PAIRED && credentialStore.credential()!=null,
                runCatching { transactionalStore.publicationState(); true }.getOrDefault(false),
            )
            val sessionId = newPlayerSessionId()
            val health = DeviceHealthManager(AndroidDeviceHealthCollector(application, config.identity, stateManager, operationalState, transactionalStore, sessionId, updateState,attemptStore,{platformInstaller.capability().name}))
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
                checkUpdate = { if(updateScheduler.schedule(0)) "scheduled" else "schedule_failed" },
                installUpdate = {
                    val code=when(val result=installCoordinator.requestInstall()){
                        UpdateInstallRequestResult.Accepted->"install_request_accepted"
                        UpdateInstallRequestResult.PermissionRequired->"user_action_required"
                        UpdateInstallRequestResult.AlreadyRunning->"install_already_running"
                        UpdateInstallRequestResult.NoPreparedUpdate->"no_prepared_update"
                        UpdateInstallRequestResult.NotAuthorized->"update_not_authorized"
                        is UpdateInstallRequestResult.Failed->result.code.lowercase()
                    }
                    attemptStore.load()?.let(updateSource::reportInstall)
                    code
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
                updateManager = updateState,
                updateCoordinator = updateCoordinator,
                updateScheduler = updateScheduler,
                updateInstallCoordinator = installCoordinator,
                updateAttemptStore = attemptStore,
                updateSource = updateSource,
                operationalState = operationalState,
            )
        }
    }
}
