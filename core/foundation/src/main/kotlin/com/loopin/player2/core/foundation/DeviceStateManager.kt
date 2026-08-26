package com.loopin.player2.core.foundation

import com.loopin.player2.core.model.DeviceRuntimeState
import com.loopin.player2.core.model.RuntimePhase
import java.util.concurrent.CopyOnWriteArraySet

class DeviceStateManager {
    @Volatile
    private var current = DeviceRuntimeState()
    private val listeners = CopyOnWriteArraySet<(DeviceRuntimeState) -> Unit>()

    fun snapshot(): DeviceRuntimeState = current

    fun subscribe(listener: (DeviceRuntimeState) -> Unit): AutoCloseable {
        listeners += listener
        listener(current)
        return AutoCloseable { listeners -= listener }
    }

    fun setNetworkAvailable(available: Boolean) = update {
        copy(
            networkAvailable = available,
            phase = if (available) RuntimePhase.READY_ONLINE else RuntimePhase.READY_OFFLINE,
        )
    }

    fun setActivityVisible(visible: Boolean) = update { copy(activityVisible = visible) }

    fun reportFailure(message: String) = update {
        copy(phase = RuntimePhase.DEGRADED, lastFailure = message)
    }

    fun markStopped() = update { copy(phase = RuntimePhase.STOPPED, activityVisible = false) }

    private fun update(transform: DeviceRuntimeState.() -> DeviceRuntimeState) {
        val next = synchronized(this) {
            current.transform().also { current = it }
        }
        listeners.forEach { listener -> runCatching { listener(next) } }
    }
}
