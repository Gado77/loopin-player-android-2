package com.loopin.player2.core.sync

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class InstallationCapability { INTERACTIVE_READY, INTERACTIVE_PERMISSION_REQUIRED, DEVICE_OWNER, UNAVAILABLE }
enum class UpdateInstallationState {
    READY_TO_INSTALL, INSTALL_PERMISSION_REQUIRED, INSTALL_REQUESTED, USER_ACTION_REQUIRED,
    INSTALLING, POST_UPDATE_VERIFYING, INSTALLED, INSTALL_DEFERRED, INSTALL_CANCELED,
    INSTALL_FAILED, UPDATE_RECOVERY_REQUIRED
}

data class UpdateInstallAttempt(
    val releaseId: String,
    val fromVersionCode: Long,
    val targetVersionCode: Long,
    val targetVersionName: String,
    val apkSha256: String,
    val startedAtEpochMs: Long,
    val state: UpdateInstallationState,
    val failureCode: String? = null,
)

interface UpdateInstallAttemptStore { fun load(): UpdateInstallAttempt?; fun save(value: UpdateInstallAttempt); fun clear() }
sealed interface InstallAuthorizationResult { data object Authorized:InstallAuthorizationResult; data object Unauthorized:InstallAuthorizationResult; data class Failed(val retryable:Boolean):InstallAuthorizationResult }
fun interface UpdateInstallAuthorizer { fun authorize(info: PlayerUpdateInfo): InstallAuthorizationResult }

sealed interface PlatformInstallResult {
    data object Requested:PlatformInstallResult
    data object PermissionRequired:PlatformInstallResult
    data class Failed(val code:String):PlatformInstallResult
}
interface PlatformUpdateInstaller {
    fun capability(): InstallationCapability
    fun install(apk: File, attempt: UpdateInstallAttempt): PlatformInstallResult
}

sealed interface UpdateInstallRequestResult {
    data object Accepted:UpdateInstallRequestResult
    data object PermissionRequired:UpdateInstallRequestResult
    data object AlreadyRunning:UpdateInstallRequestResult
    data object NoPreparedUpdate:UpdateInstallRequestResult
    data object NotAuthorized:UpdateInstallRequestResult
    data class Failed(val code:String):UpdateInstallRequestResult
}

class UpdateInstallCoordinator(
    private val updateManager: PlayerUpdateManager,
    private val authorizer: UpdateInstallAuthorizer,
    private val installer: PlatformUpdateInstaller,
    private val store: UpdateInstallAttemptStore,
    private val currentVersionCode: Long,
    private val isPaired: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val running = AtomicBoolean(false)

    fun requestInstall(): UpdateInstallRequestResult {
        if (store.load()?.state in setOf(UpdateInstallationState.INSTALL_REQUESTED, UpdateInstallationState.INSTALLING, UpdateInstallationState.POST_UPDATE_VERIFYING))
            return UpdateInstallRequestResult.AlreadyRunning
        if (!running.compareAndSet(false, true)) return UpdateInstallRequestResult.AlreadyRunning
        return try {
            if (!isPaired()) return UpdateInstallRequestResult.NotAuthorized
            val ready = updateManager.revalidatePrepared() as? ApkPreparationResult.Ready
                ?: return UpdateInstallRequestResult.NoPreparedUpdate
            if (authorizer.authorize(ready.info) !is InstallAuthorizationResult.Authorized)
                return UpdateInstallRequestResult.NotAuthorized
            val attempt = UpdateInstallAttempt(ready.info.releaseId, currentVersionCode, ready.info.versionCode,
                ready.info.versionName, ready.info.sha256.lowercase(), now(), UpdateInstallationState.INSTALL_REQUESTED)
            store.save(attempt)
            when (val result = installer.install(ready.apk, attempt)) {
                PlatformInstallResult.Requested -> {
                    store.save(attempt.copy(state = UpdateInstallationState.INSTALLING)); UpdateInstallRequestResult.Accepted
                }
                PlatformInstallResult.PermissionRequired -> {
                    store.save(attempt.copy(state = UpdateInstallationState.INSTALL_PERMISSION_REQUIRED)); UpdateInstallRequestResult.PermissionRequired
                }
                is PlatformInstallResult.Failed -> {
                    store.save(attempt.copy(state = UpdateInstallationState.INSTALL_FAILED, failureCode = result.code.take(64)))
                    UpdateInstallRequestResult.Failed(result.code.take(64))
                }
            }
        } finally { running.set(false) }
    }

    fun verifyAfterStartup(identityReadable:Boolean, pairingReadable:Boolean, activeReadable:Boolean): UpdateInstallAttempt? {
        val attempt=store.load()?:return null
        if(currentVersionCode==attempt.targetVersionCode) {
            val state=if(identityReadable&&pairingReadable&&activeReadable) UpdateInstallationState.POST_UPDATE_VERIFYING else UpdateInstallationState.UPDATE_RECOVERY_REQUIRED
            return attempt.copy(state=state).also(store::save)
        }
        return attempt
    }
}
