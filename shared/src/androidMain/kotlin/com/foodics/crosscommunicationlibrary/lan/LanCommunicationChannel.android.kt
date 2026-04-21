package com.foodics.crosscommunicationlibrary.lan

import client.WriteType
import com.foodics.crosscommunicationlibrary.AndroidAppContextProvider
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import ConnectionType
import scanner.IoTDevice

//android code
actual class LanCommunicationChannel : CommunicationChannel {
    private val context = AndroidAppContextProvider.context
    private val serverHandler = LanServerHandler(context)
    private val clientHandler = LanClientHandler(context)

    actual override val connectionType: ConnectionType = ConnectionType.LAN

    actual override suspend fun startServer(deviceName: String, identifier: String) =
        serverHandler.start(deviceName, identifier)

    actual override fun scan() = clientHandler.scan()
    actual override suspend fun connectToServer(device: IoTDevice) = clientHandler.connect(device)
    actual override suspend fun sendDataToServer(data: ByteArray, writeType: WriteType) =
        clientHandler.sendToServer(data, writeType)

    actual override suspend fun receiveDataFromServer() = clientHandler.receiveFromServer()
    actual override suspend fun sendDataToClient(data: ByteArray) = serverHandler.sendToClient(data)
    actual override suspend fun receiveDataFromClient() = serverHandler.receiveFromClient()
    actual override suspend fun stopServer() = serverHandler.stop()
    actual override suspend fun disconnectClient() = clientHandler.disconnect()
}
