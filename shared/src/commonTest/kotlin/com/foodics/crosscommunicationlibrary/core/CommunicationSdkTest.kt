package com.foodics.crosscommunicationlibrary.core

import ConnectionType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CommunicationSdkTest {

    private fun sdkWith(vararg channels: FakeCommunicationChannel) =
        CommunicationSDK(channels.toList(), logger = null)

    // ── builder companion ────────────────────────────────────────────────────

    @Test
    fun builder_returnsNewBuilderInstance() {
        val b1 = CommunicationSDK.builder()
        val b2 = CommunicationSDK.builder()
        assertTrue(b1 !== b2, "Each builder() call should return a distinct instance")
    }

    // ── channel routing ──────────────────────────────────────────────────────

    @Test
    fun startServer_delegatesToMatchingChannel() = runTest {
        val channel = FakeCommunicationChannel(ConnectionType.BLUETOOTH)
        val sdk = sdkWith(channel)

        sdk.startServer(ConnectionType.BLUETOOTH, "MyDevice", "id-123")

        assertTrue(channel.startServerCalled)
        assertEquals("MyDevice", channel.lastServerName)
        assertEquals("id-123", channel.lastServerIdentifier)
    }

    @Test
    fun stopServer_delegatesToMatchingChannel() = runTest {
        val channel = FakeCommunicationChannel(ConnectionType.BLUETOOTH)
        val sdk = sdkWith(channel)

        sdk.stopServer(ConnectionType.BLUETOOTH)

        assertTrue(channel.stopServerCalled)
    }

    @Test
    fun disconnectClient_delegatesToMatchingChannel() = runTest {
        val channel = FakeCommunicationChannel(ConnectionType.BLUETOOTH)
        val sdk = sdkWith(channel)

        sdk.disconnectClient(ConnectionType.BLUETOOTH)

        assertTrue(channel.disconnectClientCalled)
    }

    @Test
    fun sendDataToServer_delegatesToMatchingChannel() = runTest {
        val channel = FakeCommunicationChannel(ConnectionType.BLUETOOTH)
        val sdk = sdkWith(channel)
        val payload = "hello".encodeToByteArray()

        sdk.sendDataToServer(ConnectionType.BLUETOOTH, payload)

        assertContentEquals(payload, channel.sendToServerData)
    }

    @Test
    fun sendDataToClient_delegatesToMatchingChannel() = runTest {
        val channel = FakeCommunicationChannel(ConnectionType.BLUETOOTH)
        val sdk = sdkWith(channel)
        val payload = "world".encodeToByteArray()

        sdk.sendDataToClient(ConnectionType.BLUETOOTH, payload)

        assertContentEquals(payload, channel.sendToClientData)
    }

    // ── multi-channel routing ────────────────────────────────────────────────

    @Test
    fun startServer_withMultipleChannels_onlyCallsMatchingChannel() = runTest {
        val bleChannel = FakeCommunicationChannel(ConnectionType.BLUETOOTH)
        val lanChannel = FakeCommunicationChannel(ConnectionType.LAN)
        val sdk = sdkWith(bleChannel, lanChannel)

        sdk.startServer(ConnectionType.BLUETOOTH, "BLE-Server", "ble-id")

        assertTrue(bleChannel.startServerCalled, "BLE channel should be called")
        assertTrue(!lanChannel.startServerCalled, "LAN channel should NOT be called")
    }

    @Test
    fun stopServer_withMultipleChannels_onlyStopsMatchingChannel() = runTest {
        val bleChannel = FakeCommunicationChannel(ConnectionType.BLUETOOTH)
        val lanChannel = FakeCommunicationChannel(ConnectionType.LAN)
        val sdk = sdkWith(bleChannel, lanChannel)

        sdk.stopServer(ConnectionType.LAN)

        assertTrue(lanChannel.stopServerCalled, "LAN channel should be stopped")
        assertTrue(!bleChannel.stopServerCalled, "BLE channel should NOT be stopped")
    }

    // ── error cases ──────────────────────────────────────────────────────────

    @Test
    fun connectToServer_withDeviceNotInCache_throws() = runTest {
        val channel = FakeCommunicationChannel(ConnectionType.BLUETOOTH)
        val sdk = sdkWith(channel)
        val device = DiscoveredDevice(
            id = "unknown-device",
            name = "Ghost",
            addressByType = mapOf(ConnectionType.BLUETOOTH to "00:00:00:00"),
            connectionTypes = setOf(ConnectionType.BLUETOOTH)
        )

        assertFailsWith<IllegalStateException> {
            sdk.connectToServer(device, ConnectionType.BLUETOOTH)
        }
    }

    @Test
    fun startServer_withUnknownConnectionType_throws() = runTest {
        val bleChannel = FakeCommunicationChannel(ConnectionType.BLUETOOTH)
        val sdk = sdkWith(bleChannel)

        assertFailsWith<NoSuchElementException> {
            sdk.startServer(ConnectionType.LAN, "Server", "id")
        }
    }

    @Test
    fun stopServer_withUnknownConnectionType_throws() = runTest {
        val bleChannel = FakeCommunicationChannel(ConnectionType.BLUETOOTH)
        val sdk = sdkWith(bleChannel)

        assertFailsWith<NoSuchElementException> {
            sdk.stopServer(ConnectionType.LAN)
        }
    }

    // ── stopAllServers ───────────────────────────────────────────────────────

    @Test
    fun stopAllServers_stopsAllChannels() = runTest {
        val bleChannel = FakeCommunicationChannel(ConnectionType.BLUETOOTH)
        val lanChannel = FakeCommunicationChannel(ConnectionType.LAN)
        val sdk = sdkWith(bleChannel, lanChannel)

        sdk.stopAllServers()

        assertTrue(bleChannel.stopServerCalled, "BLE channel should be stopped")
        assertTrue(lanChannel.stopServerCalled, "LAN channel should be stopped")
    }

    // ── scan ─────────────────────────────────────────────────────────────────

    @Test
    fun scan_withEmptyChannels_emitsEmptyList() = runTest {
        val channel = FakeCommunicationChannel(ConnectionType.BLUETOOTH)
        val sdk = sdkWith(channel)

        val result = mutableListOf<List<DiscoveredDevice>>()
        sdk.scan().collect { result.add(it) }

        assertTrue(result.isNotEmpty(), "scan should emit at least one value")
        assertTrue(result.all { it.isEmpty() }, "All emissions should be empty since fake channel returns no devices")
    }
}
