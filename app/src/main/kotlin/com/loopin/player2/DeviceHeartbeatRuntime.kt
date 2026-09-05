package com.loopin.player2

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.loopin.player2.core.model.LogLevel
import com.loopin.player2.core.model.PlayerLogger
import com.loopin.player2.core.operations.DeviceHeartbeatDispatcher
import com.loopin.player2.core.operations.DeviceHeartbeatRequest
import com.loopin.player2.core.operations.DeviceHeartbeatTransport
import com.loopin.player2.core.operations.HeartbeatBackoffPolicy
import com.loopin.player2.core.operations.HeartbeatDispatchResult
import com.loopin.player2.core.operations.HeartbeatTransportResult
import com.loopin.player2.core.operations.heartbeatTransportResultForStatus
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class DeviceHeartbeatHttpApi : DeviceHeartbeatTransport {
    override fun send(request: DeviceHeartbeatRequest): HeartbeatTransportResult {
        val connection = URL(request.endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            for ((name, value) in request.headers) connection.setRequestProperty(name, value)
            val body = JSONObject()
                .put("action", request.payload.action)
                .put("app_version", request.payload.appVersion)
                .put("session_id", request.payload.sessionId)
                .put("runtime", JSONObject(request.payload.runtime))
                .put("last_error", request.payload.lastError?.let(::JSONObject) ?: JSONObject.NULL)
                .toString()
                .toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(body) }
            heartbeatTransportResultForStatus(connection.responseCode)
        } catch (_: Exception) {
            HeartbeatTransportResult.Failed(retryable = true)
        } finally {
            connection.disconnect()
        }
    }
}

class DeviceHeartbeatScheduler(
    private val context: Context,
    private val logger: PlayerLogger,
    private val backoff: HeartbeatBackoffPolicy = HeartbeatBackoffPolicy(),
) {
    private val preferences = context.getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE)

    fun schedule(delayMs: Long = 0L): Boolean {
        val delay = delayMs.coerceAtLeast(0L)
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, DeviceHeartbeatJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setMinimumLatency(delay)
            .setOverrideDeadline(delay + DEADLINE_FLEX_MS)
            .build()
        val result = (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).schedule(job)
        if (result != JobScheduler.RESULT_SUCCESS) {
            logger.log(LogLevel.WARN, TAG, "Heartbeat scheduling failed")
        }
        return result == JobScheduler.RESULT_SUCCESS
    }

    fun scheduleAfter(result: HeartbeatDispatchResult) {
        val previous = preferences.getInt(KEY_FAILURES, 0)
        val failures = when (result) {
            is HeartbeatDispatchResult.Failed -> if (result.retryable) previous + 1 else 0
            HeartbeatDispatchResult.Unauthorized -> previous + 1
            else -> 0
        }
        preferences.edit().putInt(KEY_FAILURES, failures).apply()
        schedule(backoff.delayAfter(result, failures))
    }

    companion object {
        private const val TAG = "DeviceHeartbeat"
        private const val JOB_ID = 0x4C50_0008
        private const val STATE_PREFERENCES = "loopin_heartbeat_schedule_state"
        private const val KEY_FAILURES = "consecutive_failures"
        private const val DEADLINE_FLEX_MS = 2L * 60L * 1_000L
    }
}

class DeviceHeartbeatJobService : JobService() {
    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as LoopinApplication).container
        worker = Thread({
            val result = runCatching { container.heartbeatDispatcher.dispatch() }
                .getOrElse { HeartbeatDispatchResult.Failed(retryable = true) }
            when (result) {
                HeartbeatDispatchResult.Success -> container.logger.log(LogLevel.DEBUG, TAG, "Heartbeat accepted")
                HeartbeatDispatchResult.Unauthorized -> container.logger.log(LogLevel.WARN, TAG, "Heartbeat authentication failed")
                is HeartbeatDispatchResult.Failed -> container.logger.log(LogLevel.WARN, TAG, "Heartbeat delivery failed")
                else -> Unit
            }
            jobFinished(params, false)
            Handler(Looper.getMainLooper()).post {
                container.heartbeatScheduler.scheduleAfter(result)
            }
            worker = null
        }, "loopin-heartbeat").apply { start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        worker = null
        (application as? LoopinApplication)?.container?.heartbeatScheduler?.schedule(60_000L)
        return false
    }

    private companion object { const val TAG = "DeviceHeartbeat" }
}
