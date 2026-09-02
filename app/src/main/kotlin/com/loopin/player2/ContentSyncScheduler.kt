package com.loopin.player2

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.loopin.player2.core.model.LogLevel
import com.loopin.player2.core.model.PlayerLogger
import com.loopin.player2.core.sync.SyncResult
import com.loopin.player2.core.sync.SyncRetryPolicy

data class RemoteSyncConfig(
    val manifestUrl: String,
    val intervalMs: Long,
) {
    val enabled: Boolean get() = manifestUrl.startsWith("https://")
}

class RemoteSyncConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): RemoteSyncConfig = RemoteSyncConfig(
        manifestUrl = BuildConfig.MANIFEST_ENDPOINT,
        intervalMs = preferences.getLong(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS).coerceAtLeast(MINIMUM_INTERVAL_MS),
    )

    private companion object {
        const val PREFERENCES = "loopin_remote_sync"
        const val KEY_MANIFEST_URL = "manifest_url"
        const val KEY_INTERVAL_MS = "interval_ms"
        const val DEFAULT_INTERVAL_MS = 5L * 60L * 1_000L
        const val MINIMUM_INTERVAL_MS = 60_000L
    }
}

class ContentSyncScheduler(
    private val context: Context,
    private val config: RemoteSyncConfig,
    private val logger: PlayerLogger,
    private val retryPolicy: SyncRetryPolicy = SyncRetryPolicy(regularIntervalMs = config.intervalMs),
) {
    private val preferences = context.getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE)

    fun schedule(delayMs: Long = INITIAL_DELAY_MS): Boolean {
        if (!config.enabled) return false
        val builder = JobInfo.Builder(JOB_ID, ComponentName(context, ContentSyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setMinimumLatency(delayMs.coerceAtLeast(0L))
            .setOverrideDeadline((delayMs + DEADLINE_FLEX_MS).coerceAtLeast(DEADLINE_FLEX_MS))
        if (Build.VERSION.SDK_INT >= 26) builder.setRequiresStorageNotLow(true)
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val result = scheduler.schedule(builder.build())
        logger.log(LogLevel.DEBUG, TAG, "Content sync scheduled delayMs=$delayMs result=$result")
        return result == JobScheduler.RESULT_SUCCESS
    }

    fun scheduleAfter(result: SyncResult) {
        if (result is SyncResult.AuthenticationFailed) { schedule(retryPolicy.authDelayMs); return }
        val retryable = result is SyncResult.Offline || result is SyncResult.Failed && result.retryable
        val failures = if (retryable) preferences.getInt(KEY_FAILURES, 0) + 1 else 0
        preferences.edit().putInt(KEY_FAILURES, failures).apply()
        val delay = if (retryable) retryPolicy.delayAfterFailure(failures) else config.intervalMs
        schedule(delay)
    }

    companion object {
        private const val TAG = "ContentSyncScheduler"
        private const val JOB_ID = 0x4C50_0004
        private const val STATE_PREFERENCES = "loopin_sync_schedule_state"
        private const val KEY_FAILURES = "consecutive_failures"
        private const val INITIAL_DELAY_MS = 5L * 60L * 1_000L
        private const val DEADLINE_FLEX_MS = 15L * 60L * 1_000L
    }
}

class ContentSyncJobService : JobService() {
    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as LoopinApplication).container
        if (container.pairingManager.snapshot().state != com.loopin.player2.core.operations.PairingState.PAIRED) return false
        val manager = container.syncManager ?: return false
        worker = Thread({
            val result = runCatching { manager.syncOnce() }
                .getOrElse { SyncResult.Failed(it.message ?: it.javaClass.simpleName, true) }
            container.syncScheduler?.scheduleAfter(result)
            jobFinished(params, false)
            worker = null
        }, "loopin-content-sync").apply { start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        worker = null
        return true
    }
}
