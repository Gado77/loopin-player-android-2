package com.loopin.player2.core.sync

import com.loopin.player2.core.cache.ManifestItem
import com.loopin.player2.core.cache.MediaManifest
import com.loopin.player2.core.cache.MediaSource
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

sealed interface RemoteManifestResult {
    data class Available(val manifest: MediaManifest) : RemoteManifestResult
    data object Unchanged : RemoteManifestResult
    data class Offline(val reason: String) : RemoteManifestResult
    data class Failed(val reason: String, val retryable: Boolean) : RemoteManifestResult
}

fun interface RemoteManifestSource {
    fun fetch(currentVersion: Long?): RemoteManifestResult
}

fun interface LocalManifestSource {
    fun activeVersion(): Long?
}

fun interface RemoteMediaSourceFactory {
    fun sourceFor(item: ManifestItem): MediaSource?
}

class HttpRemoteMediaSourceFactory(
    private val cancellation: CancellationSignal = CancellationSignal(),
) : RemoteMediaSourceFactory {
    override fun sourceFor(item: ManifestItem): MediaSource? =
        item.remoteUrl?.takeIf(::isHttpUrl)?.let { CancellableHttpMediaSource(it, cancellation) }
}

class CancellationSignal {
    private val cancelled = AtomicBoolean(false)
    private val callbacks = CopyOnWriteArrayList<() -> Unit>()
    fun cancel() {
        if (cancelled.compareAndSet(false, true)) callbacks.forEach { runCatching(it) }
    }
    fun throwIfCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw IOException("HTTP request cancelled")
    }
    fun onCancel(callback: () -> Unit): AutoCloseable {
        callbacks += callback
        if (cancelled.get()) callback()
        return AutoCloseable { callbacks -= callback }
    }
}

private class CancellableHttpMediaSource(
    private val url: String,
    private val cancellation: CancellationSignal,
) : MediaSource {
    override fun open(): java.io.InputStream {
        cancellation.throwIfCancelled()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        val registration = cancellation.onCancel(connection::disconnect)
        try {
            connection.connect()
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Media HTTP $status")
            return object : FilterInputStream(connection.inputStream) {
                override fun read(): Int {
                    cancellation.throwIfCancelled()
                    return super.read()
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    cancellation.throwIfCancelled()
                    return super.read(buffer, offset, length)
                }
                override fun close() {
                    runCatching { super.close() }
                    registration.close()
                    connection.disconnect()
                }
            }
        } catch (error: Exception) {
            registration.close()
            connection.disconnect()
            throw error
        }
    }
}

class HttpRemoteManifestSource private constructor(
    private val manifestUrl: String,
    private val cancellation: CancellationSignal,
    private val json: Json,
) : RemoteManifestSource {
    constructor(manifestUrl: String) : this(manifestUrl, CancellationSignal(), Json { ignoreUnknownKeys = false })
    constructor(manifestUrl: String, cancellation: CancellationSignal) : this(
        manifestUrl,
        cancellation,
        Json { ignoreUnknownKeys = false },
    )
    override fun fetch(currentVersion: Long?): RemoteManifestResult {
        if (!isHttpUrl(manifestUrl)) return RemoteManifestResult.Failed("Invalid manifest URL", false)
        var connection: HttpURLConnection? = null
        var cancellationRegistration: AutoCloseable? = null
        return try {
            cancellation.throwIfCancelled()
            connection = (URL(manifestUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                currentVersion?.let { setRequestProperty("X-Loopin-Playlist-Version", it.toString()) }
            }
            cancellationRegistration = cancellation.onCancel(connection::disconnect)
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) return RemoteManifestResult.Unchanged
            if (status !in 200..299) {
                return RemoteManifestResult.Failed("Manifest HTTP $status", status == 408 || status == 429 || status >= 500)
            }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    cancellation.throwIfCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_MANIFEST_BYTES) throw IOException("Manifest exceeds size limit")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val manifest = json.decodeFromString(MediaManifest.serializer(), bytes.toString(Charsets.UTF_8)).validate()
            RemoteManifestResult.Available(manifest)
        } catch (error: SerializationException) {
            RemoteManifestResult.Failed("Invalid manifest JSON", false)
        } catch (error: IllegalArgumentException) {
            RemoteManifestResult.Failed(error.message ?: "Invalid manifest", false)
        } catch (error: IOException) {
            RemoteManifestResult.Offline(error.message ?: "Network unavailable")
        } finally {
            cancellationRegistration?.close()
            connection?.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_MANIFEST_BYTES = 1_048_576
    }
}

internal fun isHttpUrl(value: String): Boolean = runCatching {
    val protocol = URL(value).protocol.lowercase()
    protocol == "http" || protocol == "https"
}.getOrDefault(false)
