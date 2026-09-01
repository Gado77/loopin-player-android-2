package com.loopin.player2.core.cache

import com.loopin.player2.core.model.MediaType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

const val CURRENT_MANIFEST_SCHEMA_VERSION = 1
const val VERSIONED_MANIFEST_SCHEMA_VERSION = 2

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
        require(items.isNotEmpty()) { "items are required" }
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

enum class ManifestContentKind { MEDIA, DYNAMIC }
enum class ManifestDynamicType { WEATHER }

sealed interface NormalizedManifestContent

data class NormalMediaContent(
    val mediaType: MediaType,
    val assetId: String,
    val durationMs: Long?,
    val expectedSizeBytes: Long,
    val sha256: String,
    val mimeType: String,
    val remoteUrl: String? = null,
) : NormalizedManifestContent

data class DynamicMediaContent(
    val dynamicType: ManifestDynamicType,
    val durationMs: Long,
    val configuration: Map<String, String>,
) : NormalizedManifestContent

data class NormalizedManifestItem(
    val id: String,
    val order: Int,
    val content: NormalizedManifestContent,
)

data class VersionedManifest(
    val schemaVersion: Int,
    val playlistId: String,
    val playlistVersion: Long,
    val generatedAtEpochMs: Long,
    val items: List<NormalizedManifestItem>,
) {
    fun validate(): VersionedManifest {
        require(schemaVersion == VERSIONED_MANIFEST_SCHEMA_VERSION) { "Unsupported manifest schema: $schemaVersion" }
        require(playlistId.isNotBlank()) { "playlistId is required" }
        require(playlistVersion >= 0) { "playlistVersion cannot be negative" }
        require(generatedAtEpochMs >= 0) { "generatedAtEpochMs cannot be negative" }
        require(items.map { it.id }.distinct().size == items.size) { "Item ids must be unique" }
        require(items.map { it.order }.distinct().size == items.size) { "Item order must be unique" }
        items.forEach { item ->
            require(item.id.isNotBlank()) { "Item id is required" }
            require(item.order >= 0) { "Item order cannot be negative" }
            when (val content = item.content) {
                is NormalMediaContent -> {
                    require(content.assetId.isNotBlank()) { "assetId is required" }
                    require(content.expectedSizeBytes >= 0) { "Expected size cannot be negative" }
                    require(SHA_256.matches(content.sha256)) { "SHA-256 must contain 64 hexadecimal characters" }
                    require(content.mimeType.isNotBlank()) { "mimeType is required" }
                    require(content.mimeType.startsWith(if (content.mediaType == MediaType.VIDEO) "video/" else "image/")) {
                        "mimeType does not match mediaType"
                    }
                    require(content.remoteUrl == null || content.remoteUrl.startsWith("https://")) { "remoteUrl must use HTTPS" }
                    require(content.mediaType != MediaType.IMAGE || content.durationMs != null && content.durationMs > 0) {
                        "Images require a positive duration"
                    }
                }
                is DynamicMediaContent -> {
                    require(content.durationMs > 0) { "Dynamic content requires a positive duration" }
                    require(content.configuration.keys == setOf("city", "lat", "lon")) { "WEATHER requires city, lat and lon" }
                    require(content.configuration.values.none(String::isBlank)) { "WEATHER configuration cannot be blank" }
                    require(content.configuration.getValue("lat").toDoubleOrNull()?.let { it in -90.0..90.0 } == true) { "Invalid latitude" }
                    require(content.configuration.getValue("lon").toDoubleOrNull()?.let { it in -180.0..180.0 } == true) { "Invalid longitude" }
                }
            }
        }
        return this
    }

    private companion object { val SHA_256 = Regex("[A-Fa-f0-9]{64}") }
}

/** Strict, deterministic schema-2 wire codec. Legacy schema-1 encoding remains owned by MediaManifest. */
object VersionedManifestCodec {
    private val json = Json { ignoreUnknownKeys = false; prettyPrint = true }
    private val rootKeys = setOf("schemaVersion", "playlistId", "playlistVersion", "generatedAtEpochMs", "items")
    private val commonItemKeys = setOf("id", "order", "kind")
    private val mediaKeys = commonItemKeys + setOf(
        "mediaType", "assetId", "durationMs", "expectedSizeBytes", "sha256", "mimeType", "remoteUrl",
    )
    private val dynamicKeys = commonItemKeys + setOf("dynamicType", "durationMs", "configuration")

    fun decode(value: String): VersionedManifest {
        val root = json.parseToJsonElement(value).jsonObject
        root.requireOnly(rootKeys, "manifest")
        require(root.requiredLong("schemaVersion") == VERSIONED_MANIFEST_SCHEMA_VERSION.toLong()) { "Unsupported manifest schema" }
        val items = root.required("items").jsonArray.map { decodeItem(it.jsonObject) }
        return VersionedManifest(
            schemaVersion = VERSIONED_MANIFEST_SCHEMA_VERSION,
            playlistId = root.requiredString("playlistId"),
            playlistVersion = root.requiredLong("playlistVersion"),
            generatedAtEpochMs = root.requiredLong("generatedAtEpochMs"),
            items = items,
        ).validate()
    }

    fun encode(manifest: VersionedManifest): String {
        manifest.validate()
        val items = manifest.items.sortedBy { it.order }.joinToString(",\n") { item ->
            val content = when (val value = item.content) {
                is NormalMediaContent -> listOfNotNull(
                    "\"kind\": \"MEDIA\"", "\"mediaType\": \"${value.mediaType}\"",
                    "\"assetId\": ${quote(value.assetId)}", value.durationMs?.let { "\"durationMs\": $it" },
                    "\"expectedSizeBytes\": ${value.expectedSizeBytes}", "\"sha256\": ${quote(value.sha256.lowercase())}",
                    "\"mimeType\": ${quote(value.mimeType)}", value.remoteUrl?.let { "\"remoteUrl\": ${quote(it)}" },
                )
                is DynamicMediaContent -> listOf(
                    "\"kind\": \"DYNAMIC\"", "\"dynamicType\": \"${value.dynamicType}\"",
                    "\"durationMs\": ${value.durationMs}",
                    "\"configuration\": {${value.configuration.toSortedMap().entries.joinToString(", ") { "${quote(it.key)}: ${quote(it.value)}" }}}",
                )
            }
            "    {\n      \"id\": ${quote(item.id)},\n      \"order\": ${item.order},\n      ${content.joinToString(",\n      ")}\n    }"
        }
        return "{\n  \"schemaVersion\": 2,\n  \"playlistId\": ${quote(manifest.playlistId)},\n  \"playlistVersion\": ${manifest.playlistVersion},\n  \"generatedAtEpochMs\": ${manifest.generatedAtEpochMs},\n  \"items\": [\n$items\n  ]\n}"
    }

    private fun decodeItem(value: JsonObject): NormalizedManifestItem {
        val kind = enumValueOf<ManifestContentKind>(value.requiredString("kind"))
        value.requireOnly(if (kind == ManifestContentKind.MEDIA) mediaKeys else dynamicKeys, "item")
        val content = when (kind) {
            ManifestContentKind.MEDIA -> NormalMediaContent(
                mediaType = enumValueOf(value.requiredString("mediaType")),
                assetId = value.requiredString("assetId"),
                durationMs = value.optionalLong("durationMs"),
                expectedSizeBytes = value.requiredLong("expectedSizeBytes"),
                sha256 = value.requiredString("sha256"),
                mimeType = value.requiredString("mimeType"),
                remoteUrl = value.optionalString("remoteUrl"),
            )
            ManifestContentKind.DYNAMIC -> DynamicMediaContent(
                dynamicType = enumValueOf(value.requiredString("dynamicType")),
                durationMs = value.requiredLong("durationMs"),
                configuration = value.required("configuration").jsonObject.mapValues { it.value.jsonPrimitive.content },
            )
        }
        return NormalizedManifestItem(value.requiredString("id"), value.requiredLong("order").toInt(), content)
    }

    private fun JsonObject.requireOnly(keys: Set<String>, label: String) {
        require(this.keys.all(keys::contains)) { "Unknown $label fields: ${this.keys - keys}" }
    }
    private fun JsonObject.required(name: String): JsonElement = requireNotNull(this[name]) { "$name is required" }
    private fun JsonObject.requiredString(name: String) = required(name).jsonPrimitive.content
    private fun JsonObject.requiredLong(name: String) = required(name).jsonPrimitive.longOrNull ?: error("$name must be an integer")
    private fun JsonObject.optionalLong(name: String) = this[name]?.jsonPrimitive?.longOrNull
    private fun JsonObject.optionalString(name: String) = this[name]?.jsonPrimitive?.contentOrNull
    private fun quote(value: String) = JsonPrimitive(value).toString()
}
