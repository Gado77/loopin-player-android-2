package com.loopin.player2.core.sync

import com.loopin.player2.core.cache.MediaSource
import com.loopin.player2.core.cache.SpacePolicy
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs

class PlayerUpdateManagerTest {
    private val apk = "signed-apk-fixture".encodeToByteArray()

    @Test
    fun `equal remote version is up to date`() {
        assertIs<UpdateCheckResult.UpToDate>(manager(info(10)).check(10, "stable"))
    }

    @Test
    fun `new remote version is available`() {
        assertIs<UpdateCheckResult.Available>(manager(info(11)).check(10, "stable"))
    }

    @Test
    fun `lower remote version never downgrades`() {
        assertIs<UpdateCheckResult.UpToDate>(manager(info(9)).check(10, "stable"))
    }

    @Test
    fun `invalid APK signature is rejected`() {
        val manager = manager(info(11), signature = ApkSignatureVerifier { false })

        assertIs<ApkPreparationResult.Rejected>(manager.prepare(info(11)))
    }

    @Test
    fun `invalid APK checksum is rejected and part removed`() {
        val directory = Files.createTempDirectory("updates").toFile()
        val bad = info(11).copy(sha256 = "0".repeat(64))
        val manager = manager(bad, directory = directory)

        assertIs<ApkPreparationResult.Rejected>(manager.prepare(bad))
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".part") })
    }

    @Test
    fun `interrupted APK download is rejected`() {
        val manager = manager(info(11), media = UpdateMediaSourceFactory { MediaSource { throw IOException("interrupted") } })

        assertIs<ApkPreparationResult.Rejected>(manager.prepare(info(11)))
    }

    @Test
    fun `insufficient APK storage is rejected before download`() {
        val manager = manager(info(11), space = SpacePolicy { _, _ -> false })

        assertIs<ApkPreparationResult.Rejected>(manager.prepare(info(11)))
    }

    @Test
    fun `installation unavailable keeps prepared APK`() {
        val directory = Files.createTempDirectory("updates").toFile()
        val manager = manager(
            info(11),
            directory = directory,
            installer = installer(InstallerAvailability.UNAVAILABLE, InstallationResult.Installed),
        )
        assertIs<ApkPreparationResult.Ready>(manager.prepare(info(11)))

        assertIs<InstallationResult.Failed>(manager.installPrepared())
        assertFalse(directory.listFiles().orEmpty().none { it.extension == "apk" })
    }

    @Test
    fun `installer error is reported without deleting APK`() {
        val directory = Files.createTempDirectory("updates").toFile()
        val manager = manager(
            info(11),
            directory = directory,
            installer = installer(InstallerAvailability.AVAILABLE, InstallationResult.Failed("installer error")),
        )
        assertIs<ApkPreparationResult.Ready>(manager.prepare(info(11)))

        assertIs<InstallationResult.Failed>(manager.installPrepared())
        assertFalse(directory.listFiles().orEmpty().none { it.extension == "apk" })
    }

    private fun manager(
        update: PlayerUpdateInfo,
        media: UpdateMediaSourceFactory = UpdateMediaSourceFactory { MediaSource { ByteArrayInputStream(apk) } },
        signature: ApkSignatureVerifier = ApkSignatureVerifier { true },
        directory: java.io.File = Files.createTempDirectory("updates").toFile(),
        space: SpacePolicy = SpacePolicy { _, _ -> true },
        installer: PlayerInstaller = installer(InstallerAvailability.REQUIRES_USER_ACTION, InstallationResult.UserActionRequired),
    ) = PlayerUpdateManager(
        source = PlayerUpdateSource { PlayerUpdateSourceResult.Available(update) },
        mediaSources = media,
        installer = installer,
        signatureVerifier = signature,
        directory = directory,
        spacePolicy = space,
    )

    private fun info(versionCode: Long) = PlayerUpdateInfo(
        versionCode = versionCode,
        versionName = "2.0.$versionCode",
        downloadUrl = "https://updates.example/player.apk",
        sizeBytes = apk.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) },
        releaseChannel = "stable",
    )

    private fun installer(availability: InstallerAvailability, result: InstallationResult) = object : PlayerInstaller {
        override fun availability() = availability
        override fun install(apk: java.io.File) = result
    }
}
