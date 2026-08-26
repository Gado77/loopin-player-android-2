package com.loopin.player2.core.cache

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Process-local serialization for repositories that point at the same private directory. */
internal object DirectoryLocks {
    private val locks = ConcurrentHashMap<String, Any>()

    fun forDirectory(directory: File): Any = locks.computeIfAbsent(directory.canonicalPath) { Any() }
}
