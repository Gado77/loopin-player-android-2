package com.loopin.player2.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceRuntimeStateTest {
    @Test
    fun `offline readiness is a valid runtime state`() {
        val state = DeviceRuntimeState(
            phase = RuntimePhase.READY_OFFLINE,
            networkAvailable = false,
            activityVisible = true,
        )

        assertEquals(RuntimePhase.READY_OFFLINE, state.phase)
        assertEquals(false, state.networkAvailable)
    }
}
