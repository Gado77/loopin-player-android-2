package com.loopin.player2.core.cache

import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class ManifestStore private constructor(
    directory: File,
    private val json: Json,
) {
    private val lock = DirectoryLocks.forDirectory(directory)
    constructor(directory: File) : this(directory, Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    })
    private val manifestFile = File(directory, "active-manifest.json")
    private val backupFile = File(directory, "active-manifest.json.bak")
    private val temporaryFile = File(directory, "active-manifest.json.part")

    init {
        require(directory.exists() || directory.mkdirs()) { "Cannot create manifest directory" }
    }

    fun save(manifest: MediaManifest) = synchronized(lock) {
        manifest.validate()
        recoverBackupIfNeeded()
        temporaryFile.delete()
        writeDurably(temporaryFile, json.encodeToString(MediaManifest.serializer(), manifest))
        // Parse what reached disk before replacing the last-known-good manifest.
        decode(temporaryFile.readText(Charsets.UTF_8))

        backupFile.delete()
        if (manifestFile.exists() && !manifestFile.renameTo(backupFile)) {
            temporaryFile.delete()
            error("Cannot back up active manifest")
        }
        if (!temporaryFile.renameTo(manifestFile)) {
            backupFile.renameTo(manifestFile)
            temporaryFile.delete()
            error("Cannot activate manifest")
        }
    }

    fun loadLastValid(): MediaManifest? = synchronized(lock) {
        val active = readValid(manifestFile)
        active ?: readValid(backupFile)
    }

    private fun recoverBackupIfNeeded() {
        if (!manifestFile.exists() && backupFile.isFile) {
            check(backupFile.renameTo(manifestFile)) { "Cannot restore manifest backup" }
        }
    }

    private fun readValid(file: File): MediaManifest? =
        if (!file.isFile) null else runCatching { decode(file.readText(Charsets.UTF_8)) }.getOrNull()

    private fun decode(value: String): MediaManifest = try {
        json.decodeFromString(MediaManifest.serializer(), value).validate()
    } catch (error: SerializationException) {
        throw IllegalArgumentException("Invalid manifest JSON", error)
    }

    private fun writeDurably(file: File, value: String) {
        FileOutputStream(file).use { output ->
            output.write(value.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }
}
