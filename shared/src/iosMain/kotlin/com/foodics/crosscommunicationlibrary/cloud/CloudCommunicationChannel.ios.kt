package com.foodics.crosscommunicationlibrary.cloud

import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice
import ConnectionType

actual class CloudCommunicationChannel : CommunicationChannel {

    private val serverHandler = CloudServerHandler()
    private val clientHandler = CloudClientHandler()

    actual override val connectionType: ConnectionType = ConnectionType.CLOUD

    actual override suspend fun startServer(deviceName: String, identifier: String) =
        serverHandler.start(deviceName, identifier)

    actual override fun scan(): Flow<List<IoTDevice>> = clientHandler.scan()
    actual override suspend fun connectToServer(device: IoTDevice) =
        clientHandler.connect(device.id ?: "default_channel")
    actual override suspend fun sendDataToServer(data: ByteArray, writeType: WriteType) =
        clientHandler.sendToServer(data, writeType)
    actual override suspend fun receiveDateFromServer(): Flow<ByteArray> =
        clientHandler.receiveFromServer()
    actual override suspend fun sendDataToClient(data: ByteArray) =
        serverHandler.sendToClient(data)
    actual override suspend fun receiveDataFromClient(): Flow<ByteArray> =
        serverHandler.receiveFromClient()
    actual override suspend fun stopServer() = serverHandler.stop()
    actual override suspend fun disconnectClient() = clientHandler.disconnect()
}