package com.loopin.player2.core.operations

import java.util.concurrent.atomic.AtomicBoolean

data class RemoteCommand(
    val id: String,
    val type: CommandType,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
)

enum class CommandCompletionStatus { SUCCEEDED, FAILED }
data class CommandOutcome(
    val status: CommandCompletionStatus,
    val result: Map<String, Any?>,
)
fun interface CommandExecutor { fun execute(command: RemoteCommand): CommandOutcome }

class SafeCommandExecutor(
    private val status: () -> DeviceHealthSnapshot,
    private val syncNow: () -> String,
    private val reloadPlaylist: () -> String,
    private val checkUpdate: () -> String = { "unsupported" },
) : CommandExecutor {
    override fun execute(command: RemoteCommand): CommandOutcome = when (command.type) {
        CommandType.GET_STATUS -> CommandOutcome(CommandCompletionStatus.SUCCEEDED, DeviceRuntimeSnapshotFactory.create(status()))
        CommandType.SYNC_NOW -> action(syncNow())
        CommandType.RELOAD_PLAYLIST -> action(reloadPlaylist())
        CommandType.CHECK_UPDATE -> action(checkUpdate())
        else -> CommandOutcome(CommandCompletionStatus.FAILED, mapOf("code" to "unsupported"))
    }
    private fun action(code: String) = CommandOutcome(
        if (code.lowercase() in setOf("scheduled", "already_running", "already_checking", "reloaded", "up_to_date", "update_available", "download_started")) CommandCompletionStatus.SUCCEEDED else CommandCompletionStatus.FAILED,
        mapOf("code" to code.take(64)),
    )
}

interface ExecutedCommandStore {
    fun find(commandId: String): CommandOutcome?
    fun remember(commandId: String, outcome: CommandOutcome)
}

class InMemoryExecutedCommandStore(private val limit: Int = 100) : ExecutedCommandStore {
    private val entries = LinkedHashMap<String, CommandOutcome>()
    @Synchronized override fun find(commandId: String) = entries[commandId]
    @Synchronized override fun remember(commandId: String, outcome: CommandOutcome) {
        entries.remove(commandId); entries[commandId] = outcome
        while (entries.size > limit) entries.remove(entries.keys.first())
    }
}

class DeviceCommandRequest private constructor(val endpoint: String, val headers: Map<String,String>) {
    override fun toString() = "DeviceCommandRequest(endpoint=$endpoint, headers=[redacted])"
    companion object {
        fun create(endpoint: String, credential: String): DeviceCommandRequest {
            require(endpoint.startsWith("https://")); require(credential.length in 40..128)
            return DeviceCommandRequest(endpoint, mapOf("Authorization" to "Bearer $credential", "Content-Type" to "application/json", "Accept" to "application/json"))
        }
    }
}

sealed interface CommandFetchResult {
    data class Success(val commands: List<RemoteCommand>) : CommandFetchResult
    data object Unauthorized : CommandFetchResult
    data class Failed(val retryable: Boolean) : CommandFetchResult
}
sealed interface CommandCompletionResult {
    data object Success : CommandCompletionResult
    data object Unauthorized : CommandCompletionResult
    data class Failed(val retryable: Boolean) : CommandCompletionResult
}
interface DeviceCommandTransport {
    fun fetch(request: DeviceCommandRequest): CommandFetchResult
    fun complete(request: DeviceCommandRequest, commandId: String, outcome: CommandOutcome): CommandCompletionResult
}

sealed interface CommandDispatchResult {
    data class Success(val processed: Int, val completionFailures: Int = 0) : CommandDispatchResult
    data object Unauthorized : CommandDispatchResult
    data class Failed(val retryable: Boolean) : CommandDispatchResult
    data object SkippedUnpaired : CommandDispatchResult
    data object SkippedMissingCredential : CommandDispatchResult
    data object AlreadyRunning : CommandDispatchResult
}

class DeviceCommandDispatcher(
    private val endpoint: String,
    private val pairingState: () -> PairingState,
    private val credential: () -> String?,
    private val transport: DeviceCommandTransport,
    private val executor: CommandExecutor,
    private val executed: ExecutedCommandStore,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    private val running = AtomicBoolean(false)
    fun dispatch(): CommandDispatchResult {
        if (pairingState() != PairingState.PAIRED) return CommandDispatchResult.SkippedUnpaired
        val secret = credential()?.takeIf { it.length in 40..128 } ?: return CommandDispatchResult.SkippedMissingCredential
        if (!running.compareAndSet(false, true)) return CommandDispatchResult.AlreadyRunning
        return try {
            val request = DeviceCommandRequest.create(endpoint, secret)
            when (val fetched = transport.fetch(request)) {
                CommandFetchResult.Unauthorized -> CommandDispatchResult.Unauthorized
                is CommandFetchResult.Failed -> CommandDispatchResult.Failed(fetched.retryable)
                is CommandFetchResult.Success -> {
                    var failures = 0
                    var unauthorized = false
                    fetched.commands.take(5).forEach { command ->
                        val prior = executed.find(command.id)
                        val outcome = prior ?: if (command.expiresAtEpochMs <= nowEpochMs())
                            CommandOutcome(CommandCompletionStatus.FAILED, mapOf("code" to "expired"))
                        else executor.execute(command)
                        if (prior == null) executed.remember(command.id, outcome)
                        when (transport.complete(request, command.id, outcome)) {
                            CommandCompletionResult.Success -> Unit
                            CommandCompletionResult.Unauthorized -> { unauthorized = true; failures++ }
                            is CommandCompletionResult.Failed -> failures++
                        }
                    }
                    if (unauthorized) CommandDispatchResult.Unauthorized
                    else CommandDispatchResult.Success(fetched.commands.take(5).size, failures)
                }
            }
        } finally { running.set(false) }
    }
}

class CommandBackoffPolicy(
    private val normalIntervalMs: Long = 60_000L,
    private val authenticationDelayMs: Long = 60L * 60L * 1_000L,
) {
    fun delayAfter(result: CommandDispatchResult, failures: Int) = when (result) {
        CommandDispatchResult.Unauthorized, CommandDispatchResult.SkippedMissingCredential,
        CommandDispatchResult.SkippedUnpaired -> authenticationDelayMs
        is CommandDispatchResult.Failed -> if (!result.retryable) normalIntervalMs else when(failures){1->60_000L;2->300_000L;3->900_000L;else->1_800_000L}
        is CommandDispatchResult.Success -> if (result.completionFailures > 0) 60_000L else normalIntervalMs
        CommandDispatchResult.AlreadyRunning -> normalIntervalMs
    }
}
