package com.loopin.player2.core.cache

import com.loopin.player2.core.model.LocalAvailability
import com.loopin.player2.core.model.LocalMediaResolver
import com.loopin.player2.core.model.MediaReference
import com.loopin.player2.core.model.PlayableMedia
import com.loopin.player2.core.model.Playlist
import com.loopin.player2.core.model.PlaylistItem
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

fun interface MediaSource {
    fun open(): InputStream
}

class HttpMediaSource(private val url: String) : MediaSource {
    override fun open(): InputStream {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.connect()
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            connection.disconnect()
            error("HTTP $responseCode")
        }
        return object : BufferedInputStream(connection.inputStream) {
            override fun close() {
                super.close()
                connection.disconnect()
            }
        }
    }
}

sealed interface CacheWriteResult {
    data class Ready(val file: File) : CacheWriteResult
    data class Failed(val reason: String, val cause: Throwable? = null) : CacheWriteResult
}

class SafeMediaCache(private val directory: File) : LocalMediaResolver {
    private val lock = DirectoryLocks.forDirectory(directory)
    private val states = CacheStateStore(directory)

    init { synchronized(lock) {
        require(directory.exists() || directory.mkdirs()) { "Cannot create media cache directory" }
        directory.listFiles { file -> file.name.endsWith(PART_SUFFIX) }?.forEach(File::delete)
        directory.listFiles { file -> file.name.endsWith(PREVIOUS_SUFFIX) }?.forEach { previous ->
            val target = File(directory, previous.name.removeSuffix(PREVIOUS_SUFFIX))
            if (!target.exists()) previous.renameTo(target) else previous.delete()
        }
    } }

    fun store(item: ManifestItem, source: MediaSource): CacheWriteResult = synchronized(lock) {
        item.validate()
        val target = File(directory, item.localFileName)
        val temporary = File(directory, item.localFileName + PART_SUFFIX)
        val previous = File(directory, item.localFileName + PREVIOUS_SUFFIX)
        states.set(item.id, CacheState.DOWNLOADING)
        temporary.delete()

        return try {
            source.open().use { input ->
                FileOutputStream(temporary).use { fileOutput ->
                    val output = fileOutput.buffered()
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            validateFile(item, temporary)
            previous.delete()
            if (target.exists() && !target.renameTo(previous)) error("Cannot preserve cached media")
            if (!temporary.renameTo(target)) {
                previous.renameTo(target)
                error("Cannot activate cached media")
            }
            previous.delete()
            states.set(item.id, CacheState.READY)
            CacheWriteResult.Ready(target)
        } catch (error: Exception) {
            temporary.delete()
            states.set(item.id, CacheState.FAILED)
            CacheWriteResult.Failed(error.message ?: error.javaClass.simpleName, error)
        }
    }

    fun inspect(item: ManifestItem): CacheEntry = synchronized(lock) {
        val file = File(directory, item.localFileName)
        if (!file.isFile) {
            val persisted = states.get(item.id)
            return@synchronized CacheEntry(item.id, if (persisted == CacheState.FAILED) CacheState.FAILED else CacheState.MISSING)
        }
        return try {
            validateFile(item, file)
            CacheEntry(item.id, CacheState.READY, file.absolutePath)
        } catch (error: Exception) {
            CacheEntry(item.id, CacheState.INVALID, file.absolutePath, error.message)
        }
    }

    override fun resolveLocalUri(media: PlayableMedia): String? {
        val fileName = media.metadata[LOCAL_FILE_METADATA] ?: return null
        val item = ManifestItem(
            id = media.id,
            type = media.type,
            localFileName = fileName,
            durationMs = media.durationMs,
            order = 0,
            expectedSizeBytes = media.sizeBytes,
            sha256 = media.checksum,
        )
        val entry = inspect(item)
        return entry.localFile?.takeIf { entry.state == CacheState.READY }?.let { File(it).toURI().toString() }
    }

    fun toPlaylist(manifest: MediaManifest): Playlist =
        toPlaylist(manifest, manifest.items.associate { it.id to inspect(it) })

    fun toPlaylist(manifest: MediaManifest, entries: Map<String, CacheEntry>): Playlist = Playlist(
        id = manifest.playlistId,
        version = manifest.playlistVersion,
        updatedAtEpochMs = manifest.generatedAtEpochMs,
        items = manifest.items.sortedBy(ManifestItem::order).map { item ->
            val entry = entries[item.id] ?: CacheEntry(item.id, CacheState.MISSING)
            PlaylistItem(
                id = item.id,
                order = item.order,
                media = PlayableMedia(
                    id = item.id,
                    type = item.type,
                    reference = MediaReference(entry.localFile?.takeIf { entry.state == CacheState.READY }?.let { File(it).toURI().toString() }, item.remoteUrl),
                    durationMs = item.durationMs,
                    checksum = item.sha256,
                    sizeBytes = item.expectedSizeBytes,
                    metadata = item.metadata + (LOCAL_FILE_METADATA to item.localFileName) + listOfNotNull(item.mimeType?.let { MIME_TYPE_METADATA to it }).toMap(),
                    localAvailability = if (entry.state == CacheState.READY) LocalAvailability.AVAILABLE else LocalAvailability.MISSING,
                ),
            )
        },
    )

    private fun validateFile(item: ManifestItem, file: File) {
        require(file.isFile) { "Cached file is missing" }
        item.expectedSizeBytes?.let { require(file.length() == it) { "Size mismatch" } }
        item.sha256?.let { expected ->
            val actual = file.inputStream().buffered().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            require(actual.equals(expected, ignoreCase = true)) { "SHA-256 mismatch" }
        }
    }

    companion object {
        const val LOCAL_FILE_METADATA = "localFileName"
        const val MIME_TYPE_METADATA = "mimeType"
        private const val PART_SUFFIX = ".part"
        private const val PREVIOUS_SUFFIX = ".previous"
    }
}
