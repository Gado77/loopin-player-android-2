package com.loopin.player2.core.sync

import com.loopin.player2.core.cache.MediaSource
import com.loopin.player2.core.cache.ReservedSpacePolicy
import com.loopin.player2.core.cache.SpacePolicy
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable
data class PlayerUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val releaseChannel: String,
    val releaseNotes: String? = null,
) {
    fun validate(): PlayerUpdateInfo {
        require(versionCode > 0) { "versionCode must be positive" }
        require(versionName.isNotBlank()) { "versionName is required" }
        require(isHttpUrl(downloadUrl)) { "Invalid APK download URL" }
        require(sizeBytes > 0) { "APK size must be positive" }
        require(Regex("[A-Fa-f0-9]{64}").matches(sha256)) { "Invalid APK SHA-256" }
        require(Regex("[a-z][a-z0-9_-]{1,31}").matches(releaseChannel)) { "Invalid release channel" }
        return this
    }
}

sealed interface PlayerUpdateSourceResult {
    data class Available(val info: PlayerUpdateInfo) : PlayerUpdateSourceResult
    data class Offline(val reason: String) : PlayerUpdateSourceResult
    data class Failed(val reason: String, val retryable: Boolean) : PlayerUpdateSourceResult
}

fun interface PlayerUpdateSource {
    fun latest(releaseChannel: String): PlayerUpdateSourceResult
}

fun interface UpdateMediaSourceFactory {
    fun sourceFor(info: PlayerUpdateInfo): MediaSource
}

fun interface ApkSignatureVerifier {
    fun isTrusted(apk: File): Boolean
}

enum class InstallerAvailability { AVAILABLE, REQUIRES_USER_ACTION, UNAVAILABLE }

sealed interface InstallationResult {
    data object Installed : InstallationResult
    data object UserActionRequired : InstallationResult
    data class Failed(val reason: String) : InstallationResult
}

interface PlayerInstaller {
    fun availability(): InstallerAvailability
    fun install(apk: File): InstallationResult
}

sealed interface UpdateCheckResult {
    data class Available(val info: PlayerUpdateInfo) : UpdateCheckResult
    data class UpToDate(val installedVersionCode: Long) : UpdateCheckResult
    data class Offline(val reason: String) : UpdateCheckResult
    data class Failed(val reason: String, val retryable: Boolean) : UpdateCheckResult
}

sealed interface ApkPreparationResult {
    data class Ready(val info: PlayerUpdateInfo, val apk: File) : ApkPreparationResult
    data class Rejected(val reason: String) : ApkPreparationResult
}

class PlayerUpdateManager(
    private val source: PlayerUpdateSource,
    private val mediaSources: UpdateMediaSourceFactory,
    private val installer: PlayerInstaller,
    private val signatureVerifier: ApkSignatureVerifier,
    private val directory: File,
    private val spacePolicy: SpacePolicy = ReservedSpacePolicy(),
) {
    private var prepared: ApkPreparationResult.Ready? = null

    init {
        require(directory.exists() || directory.mkdirs()) { "Cannot create APK update directory" }
        directory.listFiles { file -> file.name.endsWith(".part") }?.forEach(File::delete)
    }

    fun check(installedVersionCode: Long, channel: String): UpdateCheckResult = when (val remote = source.latest(channel)) {
        is PlayerUpdateSourceResult.Offline -> UpdateCheckResult.Offline(remote.reason)
        is PlayerUpdateSourceResult.Failed -> UpdateCheckResult.Failed(remote.reason, remote.retryable)
        is PlayerUpdateSourceResult.Available -> {
            val info = runCatching { remote.info.validate() }
                .getOrElse { return UpdateCheckResult.Failed(it.message ?: "Invalid update metadata", false) }
            if (info.versionCode <= installedVersionCode) UpdateCheckResult.UpToDate(installedVersionCode)
            else UpdateCheckResult.Available(info)
        }
    }

    fun prepare(info: PlayerUpdateInfo): ApkPreparationResult {
        val validated = runCatching { info.validate() }
            .getOrElse { return ApkPreparationResult.Rejected(it.message ?: "Invalid update metadata") }
        if (!spacePolicy.hasSpace(directory, validated.sizeBytes + 1_048_576L)) {
            return ApkPreparationResult.Rejected("Insufficient storage")
        }
        val target = File(directory, "${validated.sha256.lowercase()}.apk")
        val part = File(directory, "${validated.sha256.lowercase()}.apk.part")
        if (target.isFile && validateApk(target, validated)) {
            return ApkPreparationResult.Ready(validated, target).also { prepared = it }
        }
        part.delete()
        return try {
            var bytesWritten = 0L
            mediaSources.sourceFor(validated).open().use { input ->
                FileOutputStream(part).use { fileOutput ->
                    val output = fileOutput.buffered()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytesWritten += count
                        if (bytesWritten > validated.sizeBytes) error("APK exceeds expected size")
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            require(validateApk(part, validated)) { "APK integrity or signature validation failed" }
            if (target.exists() && !target.delete()) error("Cannot replace prepared APK")
            check(part.renameTo(target)) { "Cannot activate prepared APK" }
            ApkPreparationResult.Ready(validated, target).also { prepared = it }
        } catch (error: Exception) {
            part.delete()
            ApkPreparationResult.Rejected(error.message ?: error.javaClass.simpleName)
        }
    }

    fun installPrepared(): InstallationResult {
        val update = prepared ?: return InstallationResult.Failed("No validated APK is prepared")
        if (!validateApk(update.apk, update.info)) return InstallationResult.Failed("Prepared APK is no longer valid")
        return when (installer.availability()) {
            InstallerAvailability.UNAVAILABLE -> InstallationResult.Failed("Installation unavailable")
            InstallerAvailability.REQUIRES_USER_ACTION -> InstallationResult.UserActionRequired
            InstallerAvailability.AVAILABLE -> installer.install(update.apk)
        }
    }

    private fun validateApk(file: File, info: PlayerUpdateInfo): Boolean =
        file.isFile && file.length() == info.sizeBytes && sha256(file).equals(info.sha256, true) && signatureVerifier.isTrusted(file)

    private fun sha256(file: File): String = file.inputStream().buffered().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
