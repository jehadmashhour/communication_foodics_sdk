package com.foodics.crosscommunicationlibrary.google_nearby

import client.WriteType
import com.foodics.crosscommunicationlibrary.AppContext
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import ConnectionType
import scanner.IoTDevice

//androidMain
actual class GoogleNearbyCommunicationChannel : CommunicationChannel {

    private val context = AppContext.get()
    private val serverHandler = GoogleNearbyServerHandler(context)
    private val clientHandler = GoogleNearbyClientHandler(context)

    actual override val connectionType: ConnectionType = ConnectionType.GOOGLE_NEARBY

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
