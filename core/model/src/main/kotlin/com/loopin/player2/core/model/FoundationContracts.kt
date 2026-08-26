package com.loopin.player2.core.model

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

interface PlayerLogger {
    fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null)
}

data class TelemetryEvent(
    val type: String,
    val timestampEpochMs: Long,
    val attributes: Map<String, String> = emptyMap(),
)

interface TelemetrySink {
    fun record(event: TelemetryEvent)
}

data class RemoteCommandEnvelope(
    val id: String,
    val type: String,
    val payload: String?,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long?,
)

sealed interface CommandHandlingResult {
    data object Deferred : CommandHandlingResult
    data class Unsupported(val type: String) : CommandHandlingResult
}

interface RemoteCommandHandler {
    fun handle(command: RemoteCommandEnvelope): CommandHandlingResult
}
