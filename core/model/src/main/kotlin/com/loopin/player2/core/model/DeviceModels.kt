package com.loopin.player2.core.model

data class DeviceIdentity(
    val internalId: String,
    val friendlyCode: String,
)

data class EssentialDeviceConfig(
    val identity: DeviceIdentity,
    val configuredAtEpochMs: Long,
    val kioskRequested: Boolean,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}

enum class RuntimePhase {
    STARTING,
    READY_OFFLINE,
    READY_ONLINE,
    DEGRADED,
    STOPPED,
}

data class DeviceRuntimeState(
    val phase: RuntimePhase = RuntimePhase.STARTING,
    val networkAvailable: Boolean = false,
    val activityVisible: Boolean = false,
    val lastFailure: String? = null,
)
