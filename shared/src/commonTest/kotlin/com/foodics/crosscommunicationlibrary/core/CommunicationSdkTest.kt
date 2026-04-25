package com.foodics.crosscommunicationlibrary.core

import ConnectionType
import kotlinx.coroutines.test.runTest
import scanner.IoTDevice
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    @Test
    fun scan_singleChannel_emitsDiscoveredDevices() = runTest {
        val device = IoTDevice(
            name = "POS Terminal",
            address = "AA:BB:CC:DD:EE:FF",
            connectionType = ConnectionType.BLUETOOTH,
            id = "device-1"
        )
        val channel = FakeCommunicationChannel(ConnectionType.BLUETOOTH, scanDevices = listOf(device))
        val sdk = sdkWith(channel)

        val result = mutableListOf<List<DiscoveredDevice>>()
        sdk.scan().collect { result.add(it) }

        val lastEmission = result.last()
        assertEquals(1, lastEmission.size)
        assertEquals("device-1", lastEmission.first().id)
        assertEquals("POS Terminal", lastEmission.first().name)
    }

    @Test
    fun scan_twoChannels_sameDeviceId_mergesIntoOneEntry() = runTest {
        val bleDevice = IoTDevice(
            name = "Shared Device",
            address = "AA:BB:CC:DD:EE:FF",
            connectionType = ConnectionType.BLUETOOTH,
            id = "shared-id"
        )
        val lanDevice = IoTDevice(
            name = "Shared Device",
            address = "192.168.1.1",
            connectionType = ConnectionType.LAN,
            id = "shared-id"
        )
        val bleChannel = FakeCommunicationChannel(ConnectionType.BLUETOOTH, scanDevices = listOf(bleDevice))
        val lanChannel = FakeCommunicationChannel(ConnectionType.LAN, scanDevices = listOf(lanDevice))
        val sdk = sdkWith(bleChannel, lanChannel)

        val result = mutableListOf<List<DiscoveredDevice>>()
        sdk.scan().collect { result.add(it) }

        val lastEmission = result.last()
        assertEquals(1, lastEmission.size, "Same device ID from two channels should be merged into one entry")
        val merged = lastEmission.first()
        assertEquals("shared-id", merged.id)
        assertTrue(ConnectionType.BLUETOOTH in merged.connectionTypes)
        assertTrue(ConnectionType.LAN in merged.connectionTypes)
    }

    @Test
    fun scan_twoChannels_differentDeviceIds_emitsBothDevices() = runTest {
        val bleDevice = IoTDevice(
            name = "BLE Device",
            address = "AA:BB:CC:DD:EE:FF",
            connectionType = ConnectionType.BLUETOOTH,
            id = "ble-id"
        )
        val lanDevice = IoTDevice(
            name = "LAN Device",
            address = "192.168.1.1",
            connectionType = ConnectionType.LAN,
            id = "lan-id"
        )
        val bleChannel = FakeCommunicationChannel(ConnectionType.BLUETOOTH, scanDevices = listOf(bleDevice))
        val lanChannel = FakeCommunicationChannel(ConnectionType.LAN, scanDevices = listOf(lanDevice))
        val sdk = sdkWith(bleChannel, lanChannel)

        val result = mutableListOf<List<DiscoveredDevice>>()
        sdk.scan().collect { result.add(it) }

        val lastEmission = result.last()
        assertEquals(2, lastEmission.size, "Two different device IDs should produce two entries")
        val ids = lastEmission.map { it.id }.toSet()
        assertTrue("ble-id" in ids)
        assertTrue("lan-id" in ids)
    }

    @Test
    fun scan_deviceWithNullId_isExcluded() = runTest {
        val validDevice = IoTDevice(
            name = "Valid Device",
            address = "AA:BB:CC:DD:EE:FF",
            connectionType = ConnectionType.BLUETOOTH,
            id = "valid-id"
        )
        val nullIdDevice = IoTDevice(
            name = "No-ID Device",
            address = "11:22:33:44:55:66",
            connectionType = ConnectionType.BLUETOOTH,
            id = null
        )
        val channel = FakeCommunicationChannel(
            ConnectionType.BLUETOOTH,
            scanDevices = listOf(validDevice, nullIdDevice)
        )
        val sdk = sdkWith(channel)

        val result = mutableListOf<List<DiscoveredDevice>>()
        sdk.scan().collect { result.add(it) }

        val lastEmission = result.last()
        assertEquals(1, lastEmission.size, "Device with null id should be excluded from scan results")
        assertEquals("valid-id", lastEmission.first().id)
    }

    // ── channel-not-found ────────────────────────────────────────────────────

    @Test
    fun sendDataToServer_withUnknownConnectionType_throws() = runTest {
        val bleChannel = FakeCommunicationChannel(ConnectionType.BLUETOOTH)
        val sdk = sdkWith(bleChannel)

        assertFailsWith<NoSuchElementException> {
            sdk.sendDataToServer(ConnectionType.LAN, "data".encodeToByteArray())
        }
    }

    @Test
    fun disconnectClient_withUnknownConnectionType_throws() = runTest {
        val bleChannel = FakeCommunicationChannel(ConnectionType.BLUETOOTH)
        val sdk = sdkWith(bleChannel)

        assertFailsWith<NoSuchElementException> {
            sdk.disconnectClient(ConnectionType.LAN)
        }
    }

    @Test
    fun sendDataToClient_withUnknownConnectionType_throws() = runTest {
        val bleChannel = FakeCommunicationChannel(ConnectionType.BLUETOOTH)
        val sdk = sdkWith(bleChannel)

        assertFailsWith<NoSuchElementException> {
            sdk.sendDataToClient(ConnectionType.LAN, "data".encodeToByteArray())
        }
    }

    // ── stopAllServers exception isolation ───────────────────────────────────

    @Test
    fun stopAllServers_whenOneChannelThrows_stillStopsOtherChannels() = runTest {
        val throwingChannel = FakeCommunicationChannel(ConnectionType.BLUETOOTH, stopThrows = true)
        val normalChannel  = FakeCommunicationChannel(ConnectionType.LAN)
        val sdk = sdkWith(throwingChannel, normalChannel)

        runCatching { sdk.stopAllServers() }

        assertTrue(normalChannel.stopServerCalled, "Normal channel should still be stopped even if another throws")
        assertFalse(throwingChannel.stopServerCalled, "Throwing channel records false because it threw before setting the flag")
    }
}
