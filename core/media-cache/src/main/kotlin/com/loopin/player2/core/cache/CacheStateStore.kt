package com.loopin.player2.core.cache

import java.io.File
import java.io.FileOutputStream
import java.util.Properties

class CacheStateStore(directory: File) {
    private val lock = DirectoryLocks.forDirectory(directory)
    private val stateFile = File(directory, "cache-state.properties")
    private val temporaryFile = File(directory, "cache-state.properties.part")

    init {
        require(directory.exists() || directory.mkdirs()) { "Cannot create cache state directory" }
    }

    fun get(mediaId: String): CacheState? = synchronized(lock) {
        load().getProperty(mediaId)?.let { runCatching { CacheState.valueOf(it) }.getOrNull() }
    }

    fun set(mediaId: String, state: CacheState) = synchronized(lock) {
        val values = load().apply { setProperty(mediaId, state.name) }
        FileOutputStream(temporaryFile).use { output ->
            values.store(output, "Loopin Player media cache")
            output.flush()
            output.fd.sync()
        }
        if (stateFile.exists() && !stateFile.delete()) {
            temporaryFile.delete()
            error("Cannot replace cache state")
        }
        if (!temporaryFile.renameTo(stateFile)) {
            temporaryFile.delete()
            error("Cannot persist cache state")
        }
    }

    private fun load() = Properties().apply {
        if (stateFile.isFile) stateFile.inputStream().use(::load)
    }
}
