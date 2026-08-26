package com.loopin.player2

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.loopin.player2.core.model.DeviceIdentity
import com.loopin.player2.core.model.LogLevel
import com.loopin.player2.core.model.PlayerLogger
import com.loopin.player2.core.operations.DeviceAssignment
import com.loopin.player2.core.operations.DevicePairingManager
import com.loopin.player2.core.operations.OperationalLogEvent
import com.loopin.player2.core.operations.PairingWindow
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.json.JSONObject

data class PairingDisplayState(
    val code: String? = null,
    val qrPayload: String? = null,
    val secondsRemaining: Int = 0,
    val waitingForNetwork: Boolean = false,
    val error: String? = null,
)

class DeviceCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("loopin_device_credential", Context.MODE_PRIVATE)

    @Synchronized fun loadOrCreateSecret(): String {
        preferences.getString("secret", null)?.takeIf { it.length >= 40 }?.let { return it }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val secret = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        check(preferences.edit().putString("secret", secret).commit()) { "Device credential could not be persisted" }
        return secret
    }

    fun saveDeviceId(deviceId: String) {
        check(preferences.edit().putString("device_id", deviceId).commit()) { "Device id could not be persisted" }
    }

    fun deviceId(): String? = preferences.getString("device_id", null)
}

private data class PairingSession(val token: String, val code: String, val qrPayload: String)

private class PairingHttpApi(private val endpoint: String) {
    fun create(internalIdHash: String, credentialHash: String): PairingSession {
        val json = post(JSONObject().put("action", "create")
            .put("internal_id_hash", internalIdHash).put("credential_hash", credentialHash))
        return PairingSession(json.getString("pairing_token"), json.getString("pairing_code"), json.getString("qr_payload"))
    }

    fun status(token: String): JSONObject = post(JSONObject().put("action", "status").put("pairing_token", token))

    private fun post(body: JSONObject): JSONObject {
        require(endpoint.startsWith("https://")) { "Pairing endpoint is not configured" }
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) error(JSONObject(response.ifBlank { "{}" }).optString("error", "Pairing HTTP $status"))
            JSONObject(response)
        } finally { connection.disconnect() }
    }
}

class PairingCoordinator(
    endpoint: String,
    identity: DeviceIdentity,
    private val credentialStore: DeviceCredentialStore,
    private val pairingManager: DevicePairingManager,
    private val logger: PlayerLogger,
    private val onDisplay: (PairingDisplayState) -> Unit,
    private val onPaired: (DeviceAssignment) -> Unit,
) : AutoCloseable {
    private val api = PairingHttpApi(endpoint)
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "loopin-pairing").apply { isDaemon = true }
    }
    private val internalIdHash = sha256(identity.internalId)
    private val credentialHash = sha256(credentialStore.loadOrCreateSecret())
    @Volatile private var closed = false
    private var task: ScheduledFuture<*>? = null
    private var current: PairingSession? = null
    private var window: PairingWindow? = null

    fun start() {
        if (pairingManager.snapshot().state == com.loopin.player2.core.operations.PairingState.PAIRED) return
        pairingManager.begin()
        logger.log(LogLevel.INFO, TAG, OperationalLogEvent.PAIRING_STARTED)
        schedule(0) { createSession() }
    }

    private fun createSession() {
        if (closed) return
        onDisplay(PairingDisplayState(waitingForNetwork = true))
        try {
            current = api.create(internalIdHash, credentialHash)
            window = current?.let {
                PairingWindow(it.token, it.code, it.qrPayload, android.os.SystemClock.elapsedRealtime() + WINDOW_MS)
            }
            publishCurrent()
            schedule(STATUS_INTERVAL_MS) { checkStatus() }
        } catch (error: Exception) {
            onDisplay(PairingDisplayState(waitingForNetwork = true, error = error.message))
            schedule(RETRY_DELAY_MS) { createSession() }
        }
    }

    private fun checkStatus() {
        if (closed) return
        val session = current ?: return createSession()
        if (window?.isExpired(android.os.SystemClock.elapsedRealtime()) != false) {
            current = null
            window = null
            onDisplay(PairingDisplayState(waitingForNetwork = true))
            return createSession()
        }
        try {
            val status = api.status(session.token)
            if (status.optString("state") == "PAIRED") {
                val deviceId = status.getString("device_id")
                credentialStore.saveDeviceId(deviceId)
                val assignment = DeviceAssignment(
                    establishmentId = status.optString("screen_id").ifBlank { null },
                    screenName = status.optString("screen_name").ifBlank { null },
                    deviceSettings = mapOf("device_id" to deviceId),
                )
                pairingManager.complete(assignment)
                logger.log(LogLevel.INFO, TAG, OperationalLogEvent.PAIRING_SUCCESS)
                onPaired(assignment)
                return
            }
            publishCurrent()
            schedule(STATUS_INTERVAL_MS) { checkStatus() }
        } catch (error: Exception) {
            publishCurrent(error.message)
            schedule(STATUS_INTERVAL_MS) { checkStatus() }
        }
    }

    private fun publishCurrent(error: String? = null) {
        val session = current ?: return
        val remaining = window?.secondsRemaining(android.os.SystemClock.elapsedRealtime()) ?: 0
        onDisplay(PairingDisplayState(session.code, session.qrPayload, remaining, error = error))
    }

    private fun schedule(delayMs: Long, action: () -> Unit) {
        if (closed) return
        task?.cancel(false)
        task = executor.schedule(action, delayMs, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        closed = true
        task?.cancel(true)
        executor.shutdownNow()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "DevicePairing"
        const val WINDOW_MS = 30_000L
        const val STATUS_INTERVAL_MS = 2_500L
        const val RETRY_DELAY_MS = 15_000L
    }
}

fun pairingQrBitmap(payload: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) {
        pixels[y * size + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
}
