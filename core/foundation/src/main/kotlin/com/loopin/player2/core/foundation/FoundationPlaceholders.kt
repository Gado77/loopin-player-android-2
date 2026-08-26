package com.loopin.player2.core.foundation

import com.loopin.player2.core.model.CommandHandlingResult
import com.loopin.player2.core.model.RemoteCommandEnvelope
import com.loopin.player2.core.model.RemoteCommandHandler
import com.loopin.player2.core.model.TelemetryEvent
import com.loopin.player2.core.model.TelemetrySink

class DeferredTelemetrySink : TelemetrySink {
    override fun record(event: TelemetryEvent) = Unit
}

class DeferredRemoteCommandHandler : RemoteCommandHandler {
    override fun handle(command: RemoteCommandEnvelope): CommandHandlingResult =
        CommandHandlingResult.Deferred
}
