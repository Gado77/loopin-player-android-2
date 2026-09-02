package com.loopin.player2.core.cache

import com.loopin.player2.core.model.LocalAvailability
import com.loopin.player2.core.model.MediaReference
import com.loopin.player2.core.model.PlayableMedia
import com.loopin.player2.core.model.Playlist
import com.loopin.player2.core.model.PlaylistItem
import com.loopin.player2.core.model.DynamicContentType
import com.loopin.player2.core.model.DynamicMediaContent as PlaybackDynamicContent
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun interface SpacePolicy {
    fun hasSpace(root: File, additionalBytes: Long): Boolean
}

class ReservedSpacePolicy(
    private val reserveBytes: Long = 64L * 1024L * 1024L,
) : SpacePolicy {
    override fun hasSpace(root: File, additionalBytes: Long): Boolean =
        additionalBytes >= 0 && root.usableSpace - reserveBytes >= additionalBytes
}

sealed interface PreparationResult {
    data class Ready(val versionRef: String, val reusedObjects: Int, val createdObjects: Int) : PreparationResult
    data class Rejected(val reason: String) : PreparationResult
}

sealed interface PublicationResult {
    data class Committed(val activeVersion: Long, val previousVersion: Long?) : PublicationResult
    data class Rejected(val reason: String) : PublicationResult
}

sealed interface RollbackResult {
    data class RolledBack(val activeVersion: Long, val previousVersion: Long) : RollbackResult
    data class Rejected(val reason: String) : RollbackResult
}

enum class CommitStep { AFTER_BACKUP_BEFORE_ACTIVATE, AFTER_ACTIVATE }

fun interface CommitObserver {
    fun onStep(step: CommitStep)
}

@Serializable
data class PublishedVersionRef(
    val playlistId: String,
    val playlistVersion: Long,
    val manifestSha256: String,
    val versionRef: String,
    val publishedAtEpochMs: Long,
)

@Serializable
data class PlaylistPublicationState(
    val schemaVersion: Int = 1,
    val active: PublishedVersionRef,
    val previous: PublishedVersionRef? = null,
)

@Serializable
private data class StagingRecord(
    val status: StagingStatus,
    val manifestSha256: String,
    val objectIds: List<String>,
)

@Serializable
private enum class StagingStatus { PREPARING, READY, REJECTED }

private data class CachedManifest(
    val playlistId: String,
    val playlistVersion: Long,
    val generatedAtEpochMs: Long,
    val items: List<NormalizedManifestItem>,
    val bytes: ByteArray,
)

/**
 * Transactional publisher. Call prepare on a bounded worker; it performs streaming I/O and SHA-256.
 * Playback only receives immutable objects referenced by the committed publication state.
 */
class TransactionalPlaylistStore(
    private val root: File,
    private val spacePolicy: SpacePolicy = ReservedSpacePolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = DirectoryLocks.forDirectory(root)
    private val objectsDirectory = File(root, "objects")
    private val stagingDirectory = File(root, "staging")
    private val versionsDirectory = File(root, "versions")
    private val pointersDirectory = File(root, "pointers")
    private val activeStateFile = File(pointersDirectory, "active-playlist.json")
    private val stateBackupFile = File(pointersDirectory, "active-playlist.json.bak")
    private val statePartFile = File(pointersDirectory, "active-playlist.json.part")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; prettyPrint = true }

    init {
        synchronized(lock) {
            listOf(root, objectsDirectory, stagingDirectory, versionsDirectory, pointersDirectory).forEach {
                require(it.exists() || it.mkdirs()) { "Cannot create transactional cache directory: ${it.name}" }
            }
        }
    }

    fun prepare(
        manifest: MediaManifest,
        sourceFor: (ManifestItem) -> MediaSource?,
    ): PreparationResult = synchronized(lock) {
        recoverAbandonedStagingLocked()
        val validated = runCatching { manifest.validate(); requireTransactionalIdentity(manifest) }
            .getOrElse { return@synchronized PreparationResult.Rejected(it.message ?: "Invalid manifest") }
        @Suppress("UNUSED_VARIABLE") val validationMarker = validated
        val manifestBytes = encodeManifest(manifest)
        val manifestHash = sha256(manifestBytes)
        val versionRef = manifestHash
        val stage = File(stagingDirectory, versionRef)
        val version = File(versionsDirectory, versionRef)

        if (validatePublishedVersion(versionRef) != null) {
            return@synchronized PreparationResult.Ready(versionRef, manifest.items.size, 0)
        }
        if (stage.exists()) stage.deleteRecursively()
        check(stage.mkdirs()) { "Cannot create staging directory" }
        val stageMedia = File(stage, "media").apply { check(mkdirs()) }
        writeDurably(File(stage, MANIFEST_FILE), manifestBytes)
        writeStagingRecord(stage, StagingRecord(StagingStatus.PREPARING, manifestHash, emptyList()))

        val uniqueItems = manifest.items.distinctBy { it.sha256!!.lowercase() }
        val validObjectIds = mutableSetOf<String>()
        val missing = uniqueItems.filter { item ->
            val valid = isValidObject(item)
            if (valid) validObjectIds += objectId(item)
            !valid
        }
        val additionalBytes = missing.sumOf { it.expectedSizeBytes!! } + manifestBytes.size + (manifest.items.size * 256L)
        if (!spacePolicy.hasSpace(root, additionalBytes)) {
            rejectStage(stage, manifestHash, manifest, "Insufficient storage")
            return@synchronized PreparationResult.Rejected("Insufficient storage")
        }

        var created = 0
        for (item in uniqueItems) {
            val objectId = objectId(item)
            if (objectId in validObjectIds) continue
            val existing = File(objectsDirectory, objectId)
            if (existing.exists()) {
                rejectStage(stage, manifestHash, manifest, "Existing immutable object is corrupt: $objectId")
                return@synchronized PreparationResult.Rejected("Existing immutable object is corrupt")
            }
            val source = runCatching { sourceFor(item) }.getOrElse { error ->
                val reason = error.message ?: error.javaClass.simpleName
                rejectStage(stage, manifestHash, manifest, reason)
                return@synchronized PreparationResult.Rejected(reason)
            }
            if (source == null) {
                rejectStage(stage, manifestHash, manifest, "Missing source for ${item.id}")
                return@synchronized PreparationResult.Rejected("Missing source for ${item.id}")
            }
            val part = File(stageMedia, "$objectId.part")
            val write = writeAndValidatePart(item, source, part)
            if (write != null) {
                rejectStage(stage, manifestHash, manifest, write)
                return@synchronized PreparationResult.Rejected(write)
            }
            if (!part.renameTo(existing)) {
                part.delete()
                rejectStage(stage, manifestHash, manifest, "Cannot publish immutable object")
                return@synchronized PreparationResult.Rejected("Cannot publish immutable object")
            }
            validObjectIds += objectId
            created += 1
        }

        val objectIds = manifest.items.map(::objectId)
        if (!objectIds.all(validObjectIds::contains)) {
            rejectStage(stage, manifestHash, manifest, "Staging validation failed")
            return@synchronized PreparationResult.Rejected("Staging validation failed")
        }
        writeStagingRecord(stage, StagingRecord(StagingStatus.READY, manifestHash, objectIds))
        PreparationResult.Ready(versionRef, uniqueItems.size - created, created)
    }

    fun prepare(
        manifest: VersionedManifest,
        sourceFor: (NormalMediaContent) -> MediaSource?,
    ): PreparationResult = synchronized(lock) {
        recoverAbandonedStagingLocked()
        runCatching { manifest.validate() }
            .getOrElse { return@synchronized PreparationResult.Rejected(it.message ?: "Invalid manifest") }
        val manifestBytes = VersionedManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8)
        val manifestHash = sha256(manifestBytes)
        val stage = File(stagingDirectory, manifestHash)
        if (validatePublishedVersion(manifestHash) != null) {
            val count = manifest.items.count { it.content is NormalMediaContent }
            return@synchronized PreparationResult.Ready(manifestHash, count, 0)
        }
        if (stage.exists()) stage.deleteRecursively()
        check(stage.mkdirs()) { "Cannot create staging directory" }
        val stageMedia = File(stage, "media").apply { check(mkdirs()) }
        writeDurably(File(stage, MANIFEST_V2_FILE), manifestBytes)
        writeStagingRecord(stage, StagingRecord(StagingStatus.PREPARING, manifestHash, emptyList()))

        val media = manifest.items.mapNotNull { it.content as? NormalMediaContent }
            .distinctBy { it.sha256.lowercase() }
        val valid = mutableSetOf<String>()
        val missing = media.filter { item ->
            val available = isValidObject(item)
            if (available) valid += objectId(item)
            !available
        }
        val additionalBytes = missing.sumOf { it.expectedSizeBytes } + manifestBytes.size + (manifest.items.size * 256L)
        if (!spacePolicy.hasSpace(root, additionalBytes)) {
            rejectStage(stage, manifestHash, media.map(::objectId), "Insufficient storage")
            return@synchronized PreparationResult.Rejected("Insufficient storage")
        }
        var created = 0
        for (item in media) {
            val objectId = objectId(item)
            if (objectId in valid) continue
            val existing = File(objectsDirectory, objectId)
            if (existing.exists()) {
                rejectStage(stage, manifestHash, media.map(::objectId), "Existing immutable object is corrupt: $objectId")
                return@synchronized PreparationResult.Rejected("Existing immutable object is corrupt")
            }
            val source = runCatching { sourceFor(item) }.getOrElse {
                val reason = it.message ?: it.javaClass.simpleName
                rejectStage(stage, manifestHash, media.map(::objectId), reason)
                return@synchronized PreparationResult.Rejected(reason)
            } ?: run {
                val reason = "Missing source for ${item.assetId}"
                rejectStage(stage, manifestHash, media.map(::objectId), reason)
                return@synchronized PreparationResult.Rejected(reason)
            }
            val part = File(stageMedia, "$objectId.part")
            val error = writeAndValidatePart(item, source, part)
            if (error != null) {
                rejectStage(stage, manifestHash, media.map(::objectId), error)
                return@synchronized PreparationResult.Rejected(error)
            }
            if (!part.renameTo(existing)) {
                part.delete()
                return@synchronized PreparationResult.Rejected("Cannot publish immutable object")
            }
            valid += objectId
            created++
        }
        val objectIds = media.map(::objectId)
        if (!objectIds.all(valid::contains)) return@synchronized PreparationResult.Rejected("Staging validation failed")
        writeStagingRecord(stage, StagingRecord(StagingStatus.READY, manifestHash, objectIds))
        PreparationResult.Ready(manifestHash, media.size - created, created)
    }

    fun commit(
        versionRef: String,
        remoteManifestSha256: String = versionRef,
        observer: CommitObserver = CommitObserver { },
    ): PublicationResult = synchronized(lock) {
        if (!SHA_256.matches(versionRef)) return@synchronized PublicationResult.Rejected("Invalid version reference")
        if (!SHA_256.matches(remoteManifestSha256)) return@synchronized PublicationResult.Rejected("Invalid remote manifest identity")
        val stage = File(stagingDirectory, versionRef)
        val version = File(versionsDirectory, versionRef)
        if (!version.isDirectory) {
            val record = readStagingRecord(stage)
                ?: return@synchronized PublicationResult.Rejected("Staging is missing or invalid")
            if (record.status != StagingStatus.READY || record.manifestSha256 != versionRef) {
                return@synchronized PublicationResult.Rejected("Staging is incomplete")
            }
            val stagedManifest = readCachedManifest(stage)
                ?: return@synchronized PublicationResult.Rejected("Staged manifest is invalid")
            if (sha256(stagedManifest.bytes) != versionRef || !stagedManifest.mediaItems().all(::isValidObject)) {
                return@synchronized PublicationResult.Rejected("Staged content is incomplete or corrupt")
            }
            if (!stage.renameTo(version)) return@synchronized PublicationResult.Rejected("Cannot publish version directory")
        }

        val manifest = validatePublishedVersion(versionRef)
            ?: return@synchronized PublicationResult.Rejected("Published version is invalid")
        val current = recoverPublicationStateLocked()
        if (current?.active?.versionRef == versionRef && current.active.manifestSha256 == remoteManifestSha256) {
            return@synchronized PublicationResult.Committed(manifest.playlistVersion, current.previous?.playlistVersion)
        }
        val newActive = PublishedVersionRef(
            playlistId = manifest.playlistId,
            playlistVersion = manifest.playlistVersion,
            manifestSha256 = remoteManifestSha256,
            versionRef = versionRef,
            publishedAtEpochMs = clock(),
        )
        val next = PlaylistPublicationState(active = newActive, previous = current?.active)
        if (!spacePolicy.hasSpace(root, encodeState(next).size + POINTER_COMMIT_MARGIN_BYTES)) {
            return@synchronized PublicationResult.Rejected("Insufficient storage for atomic pointer commit")
        }
        writePublicationStateLocked(next, observer)
        PublicationResult.Committed(next.active.playlistVersion, next.previous?.playlistVersion)
    }

    fun rollback(): RollbackResult = synchronized(lock) {
        val current = recoverPublicationStateLocked()
            ?: return@synchronized RollbackResult.Rejected("No active playlist")
        val previous = current.previous
            ?: return@synchronized RollbackResult.Rejected("No previous playlist")
        if (validatePublishedVersion(previous.versionRef) == null) {
            return@synchronized RollbackResult.Rejected("Previous playlist is incomplete or corrupt")
        }
        if (validatePublishedVersion(current.active.versionRef) == null) {
            return@synchronized RollbackResult.Rejected("Current playlist is incomplete or corrupt")
        }
        val swapped = PlaylistPublicationState(active = previous, previous = current.active)
        writePublicationStateLocked(swapped, CommitObserver { })
        RollbackResult.RolledBack(swapped.active.playlistVersion, swapped.previous!!.playlistVersion)
    }

    fun loadActivePlaylist(): Playlist? = synchronized(lock) {
        val state = recoverPublicationStateLocked() ?: return@synchronized null
        val active = validatePublishedVersion(state.active.versionRef)
        if (active != null) return@synchronized toPlaylist(active)

        val previousRef = state.previous ?: return@synchronized null
        val previous = validatePublishedVersion(previousRef.versionRef) ?: return@synchronized null
        writePublicationStateLocked(
            PlaylistPublicationState(active = previousRef, previous = state.active),
            CommitObserver { },
        )
        toPlaylist(previous)
    }

    fun publicationState(): PlaylistPublicationState? = synchronized(lock) { recoverPublicationStateLocked() }

    fun recoverAbandonedStaging(): Int = synchronized(lock) { recoverAbandonedStagingLocked() }

    private fun recoverAbandonedStagingLocked(): Int {
        var removed = 0
        stagingDirectory.listFiles()?.filter(File::isDirectory)?.forEach { stage ->
            val record = readStagingRecord(stage)
            if (record?.status != StagingStatus.READY) {
                stage.deleteRecursively()
                removed += 1
            }
        }
        return removed
    }

    private fun recoverPublicationStateLocked(): PlaylistPublicationState? {
        val parsedActive = readPublicationState(activeStateFile)
        if (parsedActive != null && stateReferencesValid(parsedActive)) return parsedActive
        val embeddedPrevious = parsedActive?.previous?.takeIf(::validateReference)
        if (embeddedPrevious != null) {
            val recovered = PlaylistPublicationState(active = embeddedPrevious, previous = parsedActive.active)
            restoreStateFileLocked(recovered)
            return recovered
        }
        val backup = readPublicationState(stateBackupFile)?.takeIf(::stateReferencesValid) ?: return null
        restoreStateFileLocked(backup)
        return backup
    }

    private fun restoreStateFileLocked(state: PlaylistPublicationState) {
        writeDurably(statePartFile, encodeState(state))
        activeStateFile.delete()
        check(statePartFile.renameTo(activeStateFile)) { "Cannot recover active playlist pointer" }
    }

    private fun writePublicationStateLocked(state: PlaylistPublicationState, observer: CommitObserver) {
        require(stateReferencesValid(state)) { "Publication state references invalid versions" }
        writeDurably(statePartFile, encodeState(state))
        check(readPublicationState(statePartFile) == state) { "Cannot verify playlist pointer" }
        stateBackupFile.delete()
        if (activeStateFile.exists() && !activeStateFile.renameTo(stateBackupFile)) {
            statePartFile.delete()
            error("Cannot preserve active playlist pointer")
        }
        observer.onStep(CommitStep.AFTER_BACKUP_BEFORE_ACTIVATE)
        if (!statePartFile.renameTo(activeStateFile)) {
            stateBackupFile.renameTo(activeStateFile)
            statePartFile.delete()
            error("Cannot activate playlist pointer")
        }
        observer.onStep(CommitStep.AFTER_ACTIVATE)
    }

    private fun stateReferencesValid(state: PlaylistPublicationState): Boolean =
        state.schemaVersion == 1 &&
            validateReference(state.active)

    private fun validateReference(reference: PublishedVersionRef): Boolean =
        SHA_256.matches(reference.manifestSha256) &&
            validatePublishedVersion(reference.versionRef)?.let {
                it.playlistId == reference.playlistId && it.playlistVersion == reference.playlistVersion
            } == true

    private fun validatePublishedVersion(versionRef: String): CachedManifest? {
        if (!SHA_256.matches(versionRef)) return null
        val manifest = readCachedManifest(File(versionsDirectory, versionRef)) ?: return null
        if (sha256(manifest.bytes) != versionRef) return null
        return manifest.takeIf { it.mediaItems().all(::isValidObject) }
    }

    private fun isValidObject(item: ManifestItem): Boolean =
        runCatching { validateFile(item, File(objectsDirectory, objectId(item))) }.isSuccess

    private fun isValidObject(item: NormalMediaContent): Boolean =
        runCatching { validateFile(item, File(objectsDirectory, objectId(item))) }.isSuccess

    private fun writeAndValidatePart(item: ManifestItem, source: MediaSource, part: File): String? = try {
        part.delete()
        var count = 0L
        source.open().use { input ->
            FileOutputStream(part).use { fileOutput ->
                val output = fileOutput.buffered()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    if (count > item.expectedSizeBytes!!) error("Media exceeds expected size")
                    output.write(buffer, 0, read)
                }
                output.flush()
                fileOutput.fd.sync()
            }
        }
        validateFile(item, part)
        null
    } catch (error: Exception) {
        part.delete()
        error.message ?: error.javaClass.simpleName
    }

    private fun validateFile(item: ManifestItem, file: File) {
        require(file.isFile) { "Media object is missing" }
        require(file.length() == item.expectedSizeBytes) { "Size mismatch for ${item.id}" }
        require(sha256(file) == item.sha256!!.lowercase()) { "SHA-256 mismatch for ${item.id}" }
    }

    private fun writeAndValidatePart(item: NormalMediaContent, source: MediaSource, part: File): String? = try {
        part.delete()
        var count = 0L
        source.open().use { input ->
            FileOutputStream(part).use { fileOutput ->
                val output = fileOutput.buffered()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    if (count > item.expectedSizeBytes) error("Media exceeds expected size")
                    output.write(buffer, 0, read)
                }
                output.flush(); fileOutput.fd.sync()
            }
        }
        validateFile(item, part); null
    } catch (error: Exception) {
        part.delete(); error.message ?: error.javaClass.simpleName
    }

    private fun validateFile(item: NormalMediaContent, file: File) {
        require(file.isFile) { "Media object is missing" }
        require(file.length() == item.expectedSizeBytes) { "Size mismatch for ${item.assetId}" }
        require(sha256(file) == item.sha256.lowercase()) { "SHA-256 mismatch for ${item.assetId}" }
    }

    private fun requireTransactionalIdentity(manifest: MediaManifest) {
        manifest.items.forEach {
            require(it.sha256 != null) { "SHA-256 is required for transactional media: ${it.id}" }
            require(it.expectedSizeBytes != null) { "Expected size is required for transactional media: ${it.id}" }
        }
    }

    private fun rejectStage(stage: File, manifestHash: String, manifest: MediaManifest, reason: String) {
        runCatching {
            writeStagingRecord(stage, StagingRecord(StagingStatus.REJECTED, manifestHash, manifest.items.map(::objectId)))
            writeDurably(File(stage, "rejection.txt"), reason.toByteArray(Charsets.UTF_8))
        }
    }

    private fun rejectStage(stage: File, manifestHash: String, objectIds: List<String>, reason: String) {
        runCatching {
            writeStagingRecord(stage, StagingRecord(StagingStatus.REJECTED, manifestHash, objectIds))
            writeDurably(File(stage, "rejection.txt"), reason.toByteArray(Charsets.UTF_8))
        }
    }

    private fun writeStagingRecord(stage: File, record: StagingRecord) {
        val file = File(stage, STAGING_STATE_FILE)
        val part = File(stage, "$STAGING_STATE_FILE.part")
        writeDurably(part, json.encodeToString(StagingRecord.serializer(), record).toByteArray(Charsets.UTF_8))
        file.delete()
        check(part.renameTo(file)) { "Cannot persist staging state" }
    }

    private fun readStagingRecord(stage: File): StagingRecord? = runCatching {
        json.decodeFromString(StagingRecord.serializer(), File(stage, STAGING_STATE_FILE).readText(Charsets.UTF_8))
    }.getOrNull()

    private fun toPlaylist(manifest: CachedManifest): Playlist = Playlist(
        id = manifest.playlistId,
        version = manifest.playlistVersion,
        updatedAtEpochMs = manifest.generatedAtEpochMs,
        items = manifest.items.sortedBy { it.order }.map { item ->
            when (val content = item.content) {
                is NormalMediaContent -> {
                    val objectFile = File(objectsDirectory, objectId(content))
                    PlaylistItem(item.id, item.order, PlayableMedia(
                        id = item.id, type = content.mediaType,
                        reference = MediaReference(objectFile.toURI().toString()),
                        durationMs = content.durationMs, checksum = content.sha256,
                        sizeBytes = content.expectedSizeBytes,
                        metadata = mapOf(SafeMediaCache.LOCAL_FILE_METADATA to objectId(content), "immutableObject" to "true", SafeMediaCache.MIME_TYPE_METADATA to content.mimeType),
                        localAvailability = LocalAvailability.AVAILABLE,
                    ))
                }
                is DynamicMediaContent -> PlaylistItem(
                    item.id, item.order,
                    PlaybackDynamicContent(DynamicContentType.WEATHER, content.durationMs, content.configuration),
                )
            }
        },
    )

    private fun readManifest(file: File): MediaManifest? = runCatching {
        json.decodeFromString(MediaManifest.serializer(), file.readText(Charsets.UTF_8)).validate()
    }.getOrNull()

    private fun readCachedManifest(directory: File): CachedManifest? {
        val v2File = File(directory, MANIFEST_V2_FILE)
        if (v2File.isFile) return runCatching {
            val bytes = v2File.readBytes()
            val value = VersionedManifestCodec.decode(bytes.toString(Charsets.UTF_8))
            CachedManifest(value.playlistId, value.playlistVersion, value.generatedAtEpochMs, value.items, bytes)
        }.getOrNull()
        val v1File = File(directory, MANIFEST_FILE)
        val legacy = readManifest(v1File) ?: return null
        val bytes = v1File.readBytes()
        return CachedManifest(
            legacy.playlistId, legacy.playlistVersion, legacy.generatedAtEpochMs,
            legacy.items.map { item -> NormalizedManifestItem(item.id, item.order, NormalMediaContent(
                item.type, item.id, item.durationMs, item.expectedSizeBytes!!, item.sha256!!,
                item.mimeType ?: "application/octet-stream",
            )) }, bytes,
        )
    }

    private fun CachedManifest.mediaItems(): List<NormalMediaContent> = items.mapNotNull { it.content as? NormalMediaContent }

    private fun readPublicationState(file: File): PlaylistPublicationState? = runCatching {
        json.decodeFromString(PlaylistPublicationState.serializer(), file.readText(Charsets.UTF_8))
    }.getOrNull()

    private fun encodeManifest(manifest: MediaManifest): ByteArray =
        json.encodeToString(MediaManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)

    private fun encodeState(state: PlaylistPublicationState): ByteArray =
        json.encodeToString(PlaylistPublicationState.serializer(), state).toByteArray(Charsets.UTF_8)

    private fun manifestHash(manifest: MediaManifest): String = sha256(encodeManifest(manifest))

    private fun objectId(item: ManifestItem): String = item.sha256!!.lowercase()
    private fun objectId(item: NormalMediaContent): String = item.sha256.lowercase()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun sha256(file: File): String = file.inputStream().buffered().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun writeDurably(file: File, bytes: ByteArray) {
        file.parentFile?.let { require(it.exists() || it.mkdirs()) }
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    private companion object {
        const val MANIFEST_FILE = "manifest.json"
        const val MANIFEST_V2_FILE = "manifest-v2.json"
        const val STAGING_STATE_FILE = "preparation.json"
        const val POINTER_COMMIT_MARGIN_BYTES = 4_096L
        val SHA_256 = Regex("[a-f0-9]{64}")
    }
}
