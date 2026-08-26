package com.loopin.player2.core.cache

import com.loopin.player2.core.model.MediaType
import kotlinx.serialization.Serializable

const val CURRENT_MANIFEST_SCHEMA_VERSION = 1

@Serializable
data class MediaManifest(
    val schemaVersion: Int = CURRENT_MANIFEST_SCHEMA_VERSION,
    val playlistId: String,
    val playlistVersion: Long,
    val generatedAtEpochMs: Long,
    val items: List<ManifestItem>,
) {
    fun validate(): MediaManifest {
        require(schemaVersion == CURRENT_MANIFEST_SCHEMA_VERSION) { "Unsupported manifest schema: $schemaVersion" }
        require(playlistId.isNotBlank()) { "playlistId is required" }
        require(playlistVersion >= 0) { "playlistVersion cannot be negative" }
        require(generatedAtEpochMs >= 0) { "generatedAtEpochMs cannot be negative" }
        require(items.map(ManifestItem::id).distinct().size == items.size) { "Media ids must be unique" }
        require(items.map(ManifestItem::order).distinct().size == items.size) { "Media order must be unique" }
        items.forEach(ManifestItem::validate)
        return this
    }
}

@Serializable
data class ManifestItem(
    val id: String,
    val type: MediaType,
    val remoteUrl: String? = null,
    val localFileName: String,
    val durationMs: Long? = null,
    val order: Int,
    val expectedSizeBytes: Long? = null,
    val sha256: String? = null,
    val mimeType: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    fun validate() {
        require(id.isNotBlank()) { "Media id is required" }
        require(LOCAL_FILE_NAME.matches(localFileName)) { "Unsafe local filename: $localFileName" }
        require(order >= 0) { "Media order cannot be negative" }
        require(expectedSizeBytes == null || expectedSizeBytes >= 0) { "Expected size cannot be negative" }
        require(type != MediaType.IMAGE || durationMs != null && durationMs > 0) {
            "Images require a positive duration"
        }
        require(sha256 == null || SHA_256.matches(sha256)) { "SHA-256 must contain 64 hexadecimal characters" }
    }

    private companion object {
        val LOCAL_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,126}")
        val SHA_256 = Regex("[A-Fa-f0-9]{64}")
    }
}

enum class CacheState { MISSING, DOWNLOADING, READY, INVALID, FAILED }

data class CacheEntry(
    val mediaId: String,
    val state: CacheState,
    val localFile: String? = null,
    val detail: String? = null,
)
