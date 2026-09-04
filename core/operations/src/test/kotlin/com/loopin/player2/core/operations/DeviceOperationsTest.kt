package com.loopin.player2.core.operations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DeviceOperationsTest {
    private class MemoryPairingStore(var value: PairingSnapshot = PairingSnapshot()) : PairingStore {
        override fun load() = value
        override fun save(snapshot: PairingSnapshot) { value = snapshot }
    }
    private class MemoryUpdateStore(var channel: UpdateChannel = UpdateChannel.STABLE) : UpdateSettingsStore {
        override fun loadChannel() = channel
        override fun saveChannel(channel: UpdateChannel) { this.channel = channel }
    }

    @Test fun `first initialization is unpaired`() = assertEquals(PairingState.UNPAIRED, DevicePairingManager(MemoryPairingStore()).snapshot().state)
    @Test fun `offline initialization is representable`() = assertEquals(ConnectionStatus.OFFLINE, health(connection = ConnectionStatus.OFFLINE).connection)
    @Test fun `internal identity remains separate`() = assertTrue(health().internalId != health().friendlyCode)
    @Test fun `friendly code survives manager recreation`() {
        val first = health().friendlyCode
        assertEquals(first, health().friendlyCode)
    }
    @Test fun `online state is reported`() = assertEquals(ConnectionStatus.ONLINE, health().connection)
    @Test fun `offline state is reported`() = assertEquals(ConnectionStatus.OFFLINE, health(connection = ConnectionStatus.OFFLINE).connection)
    @Test fun `ready cache is healthy`() = assertEquals(CacheHealth.OK, health().cacheState)
    @Test fun `invalid cache is not healthy`() = assertEquals(CacheHealth.ERROR, health(cache = CacheHealth.ERROR).cacheState)
    @Test fun `update available is exposed`() {
        val manager = OperationalUpdateManager(MemoryUpdateStore(), "2.0")
        manager.update(OperationalUpdateState.UPDATE_AVAILABLE, "2.1")
        assertEquals("2.1", manager.snapshot().availableVersion)
    }
    @Test fun `missing update is up to date`() = assertEquals(OperationalUpdateState.UP_TO_DATE, OperationalUpdateManager(MemoryUpdateStore(), "2.0").snapshot().state)
    @Test fun `invalid update is explicit`() {
        val manager = OperationalUpdateManager(MemoryUpdateStore(), "2.0")
        manager.update(OperationalUpdateState.INVALID, error = "signature")
        assertEquals("signature", manager.snapshot().lastError)
    }
    @Test fun `pairing persists assignment`() {
        val store = MemoryPairingStore(); DevicePairingManager(store).complete(DeviceAssignment(screenName = "Lobby"))
        assertEquals("Lobby", DevicePairingManager(store).snapshot().assignment?.screenName)
    }
    @Test fun `pairing failure is explicit`() = assertEquals(PairingState.PAIRING_ERROR, DevicePairingManager(MemoryPairingStore()).fail("denied").state)
    @Test fun `heartbeat is produced on demand`() {
        val heartbeat = LocalHeartbeatSource(DeviceHealthManager { health() }).create()
        assertEquals("582731", heartbeat.deviceCode)
    }
    @Test fun `heartbeat payload maps app version and limited health metadata`() {
        val payload = DeviceHeartbeatPayloadFactory.create(heartbeat())
        assertEquals("heartbeat", payload.action)
        assertEquals("2.0.0", payload.appVersion)
        assertEquals(
            setOf("connection", "playback_state", "cache_state", "health_state", "free_storage_bytes", "last_sync_epoch_ms"),
            payload.metadata.keys,
        )
        assertEquals("ONLINE", payload.metadata["connection"])
        assertEquals("PLAYING", payload.metadata["playback_state"])
    }
    @Test fun `heartbeat request uses bearer credential and redacts it from diagnostics`() {
        val secret = "s".repeat(43)
        val request = DeviceHeartbeatRequest.create("https://example.com/heartbeat", secret, heartbeat())
        assertEquals("Bearer $secret", request.headers["Authorization"])
        assertTrue(secret !in request.toString())
    }
    @Test fun `unpaired device does not execute heartbeat`() {
        var calls = 0
        val dispatcher = dispatcher(pairing = PairingState.UNPAIRED) { calls++; HeartbeatTransportResult.Success }
        assertEquals(HeartbeatDispatchResult.SkippedUnpaired, dispatcher.dispatch())
        assertEquals(0, calls)
    }
    @Test fun `successful heartbeat maps transport success`() =
        assertEquals(HeartbeatDispatchResult.Success, dispatcher { HeartbeatTransportResult.Success }.dispatch())
    @Test fun `http failure remains explicit`() =
        assertEquals(HeartbeatDispatchResult.Failed(true), dispatcher { HeartbeatTransportResult.Failed(503, true) }.dispatch())
    @Test fun `unauthorized credential maps to authentication failure`() =
        assertEquals(HeartbeatDispatchResult.Unauthorized, dispatcher { HeartbeatTransportResult.Unauthorized }.dispatch())
    @Test fun `http statuses map success failure and unauthorized`() {
        assertEquals(HeartbeatTransportResult.Success, heartbeatTransportResultForStatus(200))
        assertEquals(HeartbeatTransportResult.Unauthorized, heartbeatTransportResultForStatus(401))
        assertEquals(HeartbeatTransportResult.Failed(503, true), heartbeatTransportResultForStatus(503))
        assertEquals(HeartbeatTransportResult.Failed(400, false), heartbeatTransportResultForStatus(400))
    }
    @Test fun `heartbeat backoff is bounded and resets after success`() {
        val policy = HeartbeatBackoffPolicy()
        assertEquals(60_000L, policy.delayAfter(HeartbeatDispatchResult.Failed(true), 1))
        assertEquals(5L * 60_000L, policy.delayAfter(HeartbeatDispatchResult.Failed(true), 2))
        assertEquals(15L * 60_000L, policy.delayAfter(HeartbeatDispatchResult.Failed(true), 3))
        assertEquals(30L * 60_000L, policy.delayAfter(HeartbeatDispatchResult.Failed(true), 20))
        assertEquals(5L * 60_000L, policy.delayAfter(HeartbeatDispatchResult.Success, 0))
        assertEquals(60L * 60_000L, policy.delayAfter(HeartbeatDispatchResult.Unauthorized, 1))
    }
    @Test fun `concurrent heartbeat is rejected until active execution finishes`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dispatcher = dispatcher {
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            HeartbeatTransportResult.Success
        }
        val first = Thread { dispatcher.dispatch() }.apply { start() }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertEquals(HeartbeatDispatchResult.AlreadyRunning, dispatcher.dispatch())
        release.countDown()
        first.join(1_000)
        assertEquals(HeartbeatDispatchResult.Success, dispatcher.dispatch())
    }
    @Test fun `unknown command is unsupported`() {
        val result = SafeCommandExecutor({ health() }, { "scheduled" }, { "reloaded" })
            .execute(RemoteCommand("1", CommandType.UNKNOWN, 0, Long.MAX_VALUE))
        assertEquals(CommandCompletionStatus.FAILED, result.status)
        assertEquals("unsupported", result.result["code"])
    }
    @Test fun `pairing recovers after force stop`() {
        val store = MemoryPairingStore(); DevicePairingManager(store).complete(DeviceAssignment(playlistId = "p1"))
        assertEquals(PairingState.PAIRED, DevicePairingManager(store).snapshot().state)
    }
    @Test fun `temporary pairing code expires at backend window boundary`() {
        val window = PairingWindow("t".repeat(43), "582731", "loopin://pair?token=${"t".repeat(43)}", 30_000)
        assertEquals(1, window.secondsRemaining(29_001))
        assertTrue(window.isExpired(30_000))
        assertEquals(0, window.secondsRemaining(30_000))
    }
    @Test fun `pairing manager does not regenerate challenge after permanent pairing`() {
        val store = MemoryPairingStore()
        DevicePairingManager(store).complete(DeviceAssignment(screenName = "Loja", deviceSettings = mapOf("device_id" to "uuid")))
        assertEquals(PairingState.PAIRED, DevicePairingManager(store).snapshot().state)
        assertEquals("uuid", store.value.assignment?.deviceSettings?.get("device_id"))
    }
    @Test fun `update channel recovers after reboot`() {
        val store = MemoryUpdateStore(); OperationalUpdateManager(store, "2.0").setChannel(UpdateChannel.BETA)
        assertEquals(UpdateChannel.BETA, OperationalUpdateManager(store, "2.0").snapshot().channel)
    }
    @Test fun `insufficient storage degrades health`() {
        val low = health().copy(freeStorageBytes = 0, healthState = HealthState.DEGRADED, lastError = "Insufficient storage")
        assertEquals(HealthState.DEGRADED, low.healthState)
    }

    private fun health(
        connection: ConnectionStatus = ConnectionStatus.ONLINE,
        cache: CacheHealth = CacheHealth.OK,
    ) = DeviceHealthSnapshot("internal-uuid", "582731", 100, 10, 1_000, 2_000, 4_000,
        connection, "2.0.0", PlayerOperationalState.PLAYING, cache, SyncHealth.OK, 90, null, HealthState.HEALTHY)

    private fun heartbeat() = LocalHeartbeatSource(DeviceHealthManager { health() }).create()

    private fun dispatcher(
        pairing: PairingState = PairingState.PAIRED,
        send: (DeviceHeartbeatRequest) -> HeartbeatTransportResult,
    ) = DeviceHeartbeatDispatcher(
        endpoint = "https://example.com/heartbeat",
        pairingState = { pairing },
        credential = { "s".repeat(43) },
        heartbeatSource = HeartbeatSource(::heartbeat),
        transport = DeviceHeartbeatTransport(send),
    )
}
