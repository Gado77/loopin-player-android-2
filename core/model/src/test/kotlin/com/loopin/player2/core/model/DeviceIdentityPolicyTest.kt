package com.loopin.player2.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeviceIdentityPolicyTest {
    @Test
    fun `internal identity is deterministic and valid`() {
        val first = InternalIdentityPolicy.fromStableDeviceId("device-123")
        val second = InternalIdentityPolicy.fromStableDeviceId("device-123")

        assertEquals(first, second)
        assertTrue(InternalIdentityPolicy.isValid(first))
        assertNotEquals(first, InternalIdentityPolicy.fromStableDeviceId("device-456"))
    }

    @Test
    fun `friendly code is stable and contains exactly six digits`() {
        val internalId = "12345678-1234-1234-1234-123456789abc"
        val code = FriendlyCodePolicy.derive(internalId)

        assertEquals(code, FriendlyCodePolicy.derive(internalId))
        assertTrue(FriendlyCodePolicy.isValid(code))
        assertEquals(6, code.length)
    }

    @Test
    fun `friendly code rejects technical identifiers`() {
        assertFalse(FriendlyCodePolicy.isValid("TELA-12345678"))
        assertFalse(FriendlyCodePolicy.isValid("12345"))
        assertFalse(FriendlyCodePolicy.isValid("12345A"))
    }
}
