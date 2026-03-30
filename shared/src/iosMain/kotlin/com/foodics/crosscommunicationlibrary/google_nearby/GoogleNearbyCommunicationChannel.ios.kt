package com.foodics.crosscommunicationlibrary.google_nearby

import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import ConnectionType
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

/**
 * iosMain actual implementation of GoogleNearbyCommunicationChannel.
 *
 * Delegates server-side work to GoogleNearbyServerHandler (iosMain)
 * and client-side work to GoogleNearbyClientHandler (iosMain).
 *
 * Both handlers bridge to ObjC-compatible Swift classes
 * (NearbyServerBridge / NearbyClientBridge) that wrap the
 * NearbyConnections SPM SDK.
 *
 * ⚠️  iOS supports Wi-Fi LAN medium only.
 *     iOS<->Android requires both devices on the same Wi-Fi network.
 */
actual class GoogleNearbyCommunicationChannel : CommunicationChannel {

    private val serverHandler = GoogleNearbyServerHandler()
    private val clientHandler = GoogleNearbyClientHandler()

    actual override val connectionType: ConnectionType = ConnectionType.GOOGLE_NEARBY

    actual override suspend fun startServer(deviceName: String, identifier: String) =
        serverHandler.start(deviceName, identifier)

    actual override fun scan(): Flow<List<IoTDevice>> =
        clientHandler.scan()

    actual override suspend fun connectToServer(device: IoTDevice) =
        clientHandler.connect(device)

    actual override suspend fun sendDataToServer(data: ByteArray, writeType: WriteType) =
        clientHandler.sendToServer(data, writeType)

    actual override suspend fun receiveDateFromServer(): Flow<ByteArray> =
        clientHandler.receiveFromServer()

    actual override suspend fun sendDataToClient(data: ByteArray) =
        serverHandler.sendToClient(data)

    actual override suspend fun receiveDataFromClient(): Flow<ByteArray> =
        serverHandler.receiveFromClient()

    actual override suspend fun stopServer() =
        serverHandler.stop()

    actual override suspend fun disconnectClient() =
        clientHandler.disconnect()
}