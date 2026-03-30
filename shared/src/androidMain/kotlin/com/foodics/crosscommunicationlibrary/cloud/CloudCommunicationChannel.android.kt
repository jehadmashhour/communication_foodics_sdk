package com.foodics.crosscommunicationlibrary.cloud

import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import ConnectionType
import scanner.IoTDevice

actual class CloudCommunicationChannel : CommunicationChannel {

    private val serverHandler = CloudServerHandler()
    private val clientHandler = CloudClientHandler()

    actual override val connectionType: ConnectionType = ConnectionType.CLOUD

    actual override suspend fun startServer(deviceName: String, identifier: String) =
        serverHandler.start(deviceName, identifier)

    actual override fun scan() = clientHandler.scan()
    actual override suspend fun connectToServer(device: IoTDevice) =
        clientHandler.connect(device.id ?: "default_channel")

    actual override suspend fun sendDataToServer(data: ByteArray, writeType: WriteType) =
        clientHandler.sendToServer(data, writeType)

    actual override suspend fun receiveDateFromServer() = clientHandler.receiveFromServer()
    actual override suspend fun sendDataToClient(data: ByteArray) = serverHandler.sendToClient(data)
    actual override suspend fun receiveDataFromClient() = serverHandler.receiveFromClient()
    actual override suspend fun stopServer() = serverHandler.stop()
    actual override suspend fun disconnectClient() = clientHandler.disconnect()
}
