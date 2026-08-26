package com.loopin.player2.core.cache

import com.loopin.player2.core.model.LocalAvailability
import com.loopin.player2.core.model.MediaReference
import com.loopin.player2.core.model.PlayableMedia
import com.loopin.player2.core.model.Playlist
import com.loopin.player2.core.model.PlaylistItem
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

    fun commit(
        versionRef: String,
        observer: CommitObserver = CommitObserver { },
    ): PublicationResult = synchronized(lock) {
        if (!SHA_256.matches(versionRef)) return@synchronized PublicationResult.Rejected("Invalid version reference")
        val stage = File(stagingDirectory, versionRef)
        val version = File(versionsDirectory, versionRef)
        if (!version.isDirectory) {
            val record = readStagingRecord(stage)
                ?: return@synchronized PublicationResult.Rejected("Staging is missing or invalid")
            if (record.status != StagingStatus.READY || record.manifestSha256 != versionRef) {
                return@synchronized PublicationResult.Rejected("Staging is incomplete")
            }
            val stagedManifest = readManifest(File(stage, MANIFEST_FILE))
                ?: return@synchronized PublicationResult.Rejected("Staged manifest is invalid")
            if (manifestHash(stagedManifest) != versionRef || !stagedManifest.items.all(::isValidObject)) {
                return@synchronized PublicationResult.Rejected("Staged content is incomplete or corrupt")
            }
            if (!stage.renameTo(version)) return@synchronized PublicationResult.Rejected("Cannot publish version directory")
        }

        val manifest = validatePublishedVersion(versionRef)
            ?: return@synchronized PublicationResult.Rejected("Published version is invalid")
        val current = recoverPublicationStateLocked()
        if (current?.active?.versionRef == versionRef) {
            return@synchronized PublicationResult.Committed(manifest.playlistVersion, current.previous?.playlistVersion)
        }
        val newActive = PublishedVersionRef(
            playlistId = manifest.playlistId,
            playlistVersion = manifest.playlistVersion,
            manifestSha256 = versionRef,
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
        reference.versionRef == reference.manifestSha256 &&
            validatePublishedVersion(reference.versionRef)?.let {
                it.playlistId == reference.playlistId && it.playlistVersion == reference.playlistVersion
            } == true

    private fun validatePublishedVersion(versionRef: String): MediaManifest? {
        if (!SHA_256.matches(versionRef)) return null
        val manifest = readManifest(File(File(versionsDirectory, versionRef), MANIFEST_FILE)) ?: return null
        if (manifestHash(manifest) != versionRef) return null
        return manifest.takeIf { it.items.all(::isValidObject) }
    }

    private fun isValidObject(item: ManifestItem): Boolean =
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

    private fun toPlaylist(manifest: MediaManifest): Playlist = Playlist(
        id = manifest.playlistId,
        version = manifest.playlistVersion,
        updatedAtEpochMs = manifest.generatedAtEpochMs,
        items = manifest.items.sortedBy(ManifestItem::order).map { item ->
            val objectFile = File(objectsDirectory, objectId(item))
            PlaylistItem(
                id = item.id,
                order = item.order,
                media = PlayableMedia(
                    id = item.id,
                    type = item.type,
                    reference = MediaReference(objectFile.toURI().toString(), item.remoteUrl),
                    durationMs = item.durationMs,
                    checksum = item.sha256,
                    sizeBytes = item.expectedSizeBytes,
                    metadata = item.metadata + mapOf(
                        SafeMediaCache.LOCAL_FILE_METADATA to objectId(item),
                        "immutableObject" to "true",
                    ) + listOfNotNull(item.mimeType?.let { SafeMediaCache.MIME_TYPE_METADATA to it }).toMap(),
                    localAvailability = LocalAvailability.AVAILABLE,
                ),
            )
        },
    )

    private fun readManifest(file: File): MediaManifest? = runCatching {
        json.decodeFromString(MediaManifest.serializer(), file.readText(Charsets.UTF_8)).validate()
    }.getOrNull()

    private fun readPublicationState(file: File): PlaylistPublicationState? = runCatching {
        json.decodeFromString(PlaylistPublicationState.serializer(), file.readText(Charsets.UTF_8))
    }.getOrNull()

    private fun encodeManifest(manifest: MediaManifest): ByteArray =
        json.encodeToString(MediaManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)

    private fun encodeState(state: PlaylistPublicationState): ByteArray =
        json.encodeToString(PlaylistPublicationState.serializer(), state).toByteArray(Charsets.UTF_8)

    private fun manifestHash(manifest: MediaManifest): String = sha256(encodeManifest(manifest))

    private fun objectId(item: ManifestItem): String = item.sha256!!.lowercase()

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
        const val STAGING_STATE_FILE = "preparation.json"
        const val POINTER_COMMIT_MARGIN_BYTES = 4_096L
        val SHA_256 = Regex("[a-f0-9]{64}")
    }
}
