package com.foodics.crosscommunicationlibrary.bluetooth

import client.WriteType
import ClientQuality
import com.foodics.crosscommunicationlibrary.core.ClientMessage
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import com.foodics.crosscommunicationlibrary.core.ConnectedClient
import com.foodics.crosscommunicationlibrary.logger.CommunicationLogger
import com.foodics.crosscommunicationlibrary.logger.info
import com.foodics.crosscommunicationlibrary.logger.warn
import BluetoothConstants.BRIDGE_C2S_PREFIX
import BluetoothConstants.BRIDGE_DISCONNECT_PREFIX
import BluetoothConstants.BRIDGE_INIT_PREFIX
import BluetoothConstants.BRIDGE_S2C_PREFIX
import ConnectionQuality
import ConnectionType
import handler.BluetoothClientHandler
import handler.BluetoothServerHandler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import scanner.IoTDevice

private const val LOG_TITLE = "BLE_CHANNEL"

actual class BluetoothCommunicationChannel actual constructor(
    private val logger: CommunicationLogger?
) : CommunicationChannel {
    private val serverHandler = BluetoothServerHandler(logger)
    private val clientHandler = BluetoothClientHandler(logger)

    // Non-null when iOS is acting as "client" via the server bridge (CBCentralManager cannot
    // connect to a device already connected to the CBPeripheralManager).
    private var serverBridgeId: String? = null

    // Name used when iOS started its server — sent to Android in the bridge-init handshake
    // so Android can display iOS as a connected client with a human-readable name.
    private var myServerName: String = ""

    actual override val connectionType: ConnectionType = ConnectionType.BLUETOOTH

    actual override suspend fun startServer(deviceName: String, identifier: String) {
        myServerName = deviceName
        serverHandler.start(deviceName, identifier)
    }

    actual override fun scan(): Flow<List<IoTDevice>> = clientHandler.scan()

    actual override suspend fun connectToServer(device: IoTDevice) {
        val deviceId = device.id ?: device.address
        val bridgeId = serverHandler.bridgeCentralId(deviceId, device.name)
        if (bridgeId != null) {
            serverBridgeId = bridgeId
            logger?.info(LOG_TITLE, "Bridge mode activated — device already connected to our server", mapOf("device_name" to device.name, "bridge_id" to bridgeId, "server_name" to myServerName))
            val initMsg = "$BRIDGE_INIT_PREFIX$myServerName"
            serverHandler.sendToClients(initMsg.encodeToByteArray(), listOf(bridgeId))
            return
        }
        try {
            clientHandler.connect(device)
        } catch (e: Exception) {
            val retryBridgeId = serverHandler.bridgeCentralId(deviceId, device.name)
            if (retryBridgeId != null) {
                serverBridgeId = retryBridgeId
                logger?.warn(LOG_TITLE, "Direct connect failed, switching to bridge mode", mapOf("device_name" to device.name, "error" to (e.message ?: "unknown")))
                logger?.info(LOG_TITLE, "Bridge mode activated via retry", mapOf("bridge_id" to retryBridgeId, "server_name" to myServerName))
                val initMsg = "$BRIDGE_INIT_PREFIX$myServerName"
                serverHandler.sendToClients(initMsg.encodeToByteArray(), listOf(retryBridgeId))
                return
            }
            throw e
        }
    }

    actual override suspend fun sendDataToServer(data: ByteArray, writeType: WriteType) {
        val bridgeId = serverBridgeId
        if (bridgeId != null) {
            // Tag as client-to-server so Android routes it to its server flow, not client flow.
            val tagged = BRIDGE_C2S_PREFIX.encodeToByteArray() + data
            serverHandler.sendToClients(tagged, listOf(bridgeId))
        } else {
            clientHandler.sendToServer(data, writeType)
        }
    }

    actual override suspend fun receiveDataFromServer(): Flow<ByteArray> {
        val bridgeId = serverBridgeId
        return if (bridgeId != null) {
            // Android server replies arrive as writes to our CHAR_FROM_CLIENT_UUID tagged with BS2C.
            serverHandler.receiveFromClientAsServer(bridgeId)
                .filter { it.decodeToString().startsWith(BRIDGE_S2C_PREFIX) }
                .map { it.copyOfRange(BRIDGE_S2C_PREFIX.length, it.size) }
        } else {
            clientHandler.receiveFromServer()
        }
    }

    actual override suspend fun sendDataToClient(data: ByteArray) = serverHandler.sendToClient(data)
    actual override suspend fun sendDataToClients(data: ByteArray, clientIds: List<String>) = serverHandler.sendToClients(data, clientIds)

    // Always exclude BRIDGE_S2C writes — those are Android-server replies routed to iOS-client
    // via the bridge write channel and must never surface as server-client messages.
    actual override suspend fun receiveDataFromClient(): Flow<ByteArray> =
        serverHandler.receiveMessagesFromClient()
            .filter { !it.data.decodeToString().startsWith(BRIDGE_S2C_PREFIX) }
            .map { it.data }

    actual override suspend fun stopServer() = serverHandler.stop()

    actual override suspend fun disconnectClient() {
        val bridgeId = serverBridgeId
        if (bridgeId != null) {
            logger?.info(LOG_TITLE, "Disconnecting from bridge", mapOf("bridge_id" to bridgeId))
            serverBridgeId = null
            try {
                serverHandler.sendToClients(BRIDGE_DISCONNECT_PREFIX.encodeToByteArray(), listOf(bridgeId))
            } catch (_: Exception) { }
        } else {
            clientHandler.disconnect()
        }
    }

    actual override fun clientConnectionState() = serverHandler.clientConnectionState()

    actual override fun connectedClients(): Flow<List<ConnectedClient>> =
        serverHandler.connectedClients().map { list ->
            list.map { ConnectedClient(it.id, it.name) }
        }

    actual override suspend fun receiveMessagesFromClient(): Flow<ClientMessage> =
        serverHandler.receiveMessagesFromClient()
            .filter { !it.data.decodeToString().startsWith(BRIDGE_S2C_PREFIX) }
            .map { msg -> ClientMessage(ConnectedClient(msg.client.id, msg.client.name), msg.data) }

    actual override fun connectionQuality(): Flow<ConnectionQuality> =
        if (serverBridgeId != null) emptyFlow() else clientHandler.connectionQuality()

    actual override fun serverClientsQuality(): Flow<List<ClientQuality>> = serverHandler.clientsQuality()
}
