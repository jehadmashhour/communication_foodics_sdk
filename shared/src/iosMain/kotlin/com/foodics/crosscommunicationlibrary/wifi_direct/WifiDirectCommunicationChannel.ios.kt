package com.foodics.crosscommunicationlibrary.wifi_direct

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

actual class WifiDirectCommunicationChannel actual constructor() : CommunicationChannel {

    private val serverHandler = WifiDirectServerHandler()
    private val clientHandler = WifiDirectClientHandler()

    actual override val connectionType: ConnectionType = ConnectionType.WIFI_DIRECT

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
