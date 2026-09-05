package com.loopin.player2

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.loopin.player2.core.sync.ApkSignatureVerifier
import com.loopin.player2.core.sync.PlayerUpdateInfo
import java.io.File
import java.security.MessageDigest

/** Verifies package name and signing certificate before any future installer receives the APK. */
class AndroidApkSignatureVerifier(private val context: Context) : ApkSignatureVerifier {
    @Suppress("DEPRECATION")
    override fun isTrusted(apk: File, info: PlayerUpdateInfo): Boolean = runCatching {
        val packageManager = context.packageManager
        val archive = packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES,
        ) ?: return false
        if (archive.packageName != "com.loopin.player2" || archive.packageName != info.packageName) return false
        val archiveVersion = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        if (archiveVersion != info.versionCode) return false
        val installed = packageManager.getPackageInfo(
            context.packageName,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES,
        )
        val archiveSigners = signerDigests(archive)
        archiveSigners == signerDigests(installed) && archiveSigners.size == 1 && archiveSigners.single().equals(info.certificateSha256, true)
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun signerDigests(info: android.content.pm.PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners else signingInfo.signingCertificateHistory
        } else {
            info.signatures.orEmpty()
        }
        return signatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
