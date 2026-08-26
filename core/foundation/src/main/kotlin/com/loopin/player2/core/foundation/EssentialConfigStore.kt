package com.loopin.player2.core.foundation

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.loopin.player2.core.model.DeviceIdentity
import com.loopin.player2.core.model.EssentialDeviceConfig
import com.loopin.player2.core.model.FriendlyCodePolicy
import com.loopin.player2.core.model.InternalIdentityPolicy
import java.util.UUID

class EssentialConfigStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun loadOrCreate(nowEpochMs: Long = System.currentTimeMillis()): EssentialDeviceConfig {
        val internalId = preferences.getString(KEY_INTERNAL_ID, null)
            ?.takeIf(InternalIdentityPolicy::isValid)
            ?: preferences.getString(KEY_LEGACY_INSTALLATION_ID, null)
                ?.takeIf(InternalIdentityPolicy::isValid)
            ?: createInternalId()
        val friendlyCode = preferences.getString(KEY_FRIENDLY_CODE, null)
            ?.takeIf(FriendlyCodePolicy::isValid)
            ?: FriendlyCodePolicy.derive(internalId)
        val configuredAt = preferences.getLong(KEY_CONFIGURED_AT, 0L).takeIf { it > 0L } ?: nowEpochMs
        val kioskRequested = preferences.getBoolean(KEY_KIOSK_REQUESTED, true)

        val config = EssentialDeviceConfig(
            identity = DeviceIdentity(internalId, friendlyCode),
            configuredAtEpochMs = configuredAt,
            kioskRequested = kioskRequested,
        )

        val persisted = preferences.edit()
            .putInt(KEY_SCHEMA_VERSION, EssentialDeviceConfig.CURRENT_SCHEMA_VERSION)
            .putString(KEY_INTERNAL_ID, config.identity.internalId)
            .putString(KEY_FRIENDLY_CODE, config.identity.friendlyCode)
            .putLong(KEY_CONFIGURED_AT, config.configuredAtEpochMs)
            .putBoolean(KEY_KIOSK_REQUESTED, config.kioskRequested)
            .commit()
        check(persisted) { "Essential configuration could not be persisted" }
        return config
    }

    @SuppressLint("HardwareIds") // Local reinstall recovery only; never sent or used for tracking here.
    private fun createInternalId(): String {
        val androidId = Settings.Secure.getString(applicationContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?.trim()
            ?.takeUnless { it.isEmpty() || it == KNOWN_BROKEN_ANDROID_ID }
        return androidId?.let(InternalIdentityPolicy::fromStableDeviceId)
            ?: UUID.randomUUID().toString()
    }

    companion object {
        private const val PREFERENCES_NAME = "loopin_player_essential_config"
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_INTERNAL_ID = "internal_id"
        private const val KEY_FRIENDLY_CODE = "friendly_code"
        private const val KEY_LEGACY_INSTALLATION_ID = "installation_id"
        private const val KEY_CONFIGURED_AT = "configured_at"
        private const val KEY_KIOSK_REQUESTED = "kiosk_requested"
        private const val KNOWN_BROKEN_ANDROID_ID = "9774d56d682e549c"
    }
}
