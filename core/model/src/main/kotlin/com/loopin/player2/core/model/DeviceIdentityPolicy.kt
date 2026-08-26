package com.loopin.player2.core.model

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

object InternalIdentityPolicy {
    private const val NAMESPACE = "loopin-player2:"

    fun fromStableDeviceId(stableDeviceId: String): String = UUID.nameUUIDFromBytes(
        "$NAMESPACE$stableDeviceId".toByteArray(StandardCharsets.UTF_8),
    ).toString()

    fun isValid(value: String): Boolean = runCatching {
        UUID.fromString(value).toString() == value.lowercase()
    }.getOrDefault(false)
}

object FriendlyCodePolicy {
    private val validPattern = Regex("^[0-9]{6}$")
    private const val RANGE = 900_000L
    private const val OFFSET = 100_000L

    /**
     * Produces a stable local candidate. The future backend must reserve it atomically
     * and issue another candidate if the six-digit namespace collides.
     */
    fun derive(internalId: String): String {
        require(InternalIdentityPolicy.isValid(internalId)) { "Invalid internal identity" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(internalId.toByteArray(StandardCharsets.UTF_8))
        val positivePrefix = ByteBuffer.wrap(digest).int.toLong() and 0xffffffffL
        return ((positivePrefix % RANGE) + OFFSET).toString()
    }

    fun isValid(value: String): Boolean = validPattern.matches(value)
}
