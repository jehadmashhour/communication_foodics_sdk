package com.foodics.crosscommunicationlibrary.wifi_direct

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.AndroidAppContextProvider
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import scanner.IoTDevice

actual class WifiDirectCommunicationChannel : CommunicationChannel {

    private val context = AndroidAppContextProvider.context

    private val serverHandler = WifiDirectServerHandler(context)
    private val clientHandler = WifiDirectClientHandler(context)

    actual override val connectionType = ConnectionType.WIFI_DIRECT

    actual override suspend fun startServer(deviceName: String, identifier: String) =
        serverHandler.start(deviceName, identifier)

    actual override fun scan() = clientHandler.scan()

    actual override suspend fun connectToServer(device: IoTDevice) =
        clientHandler.connect(device)

    actual override suspend fun sendDataToServer(
        data: ByteArray,
        writeType: WriteType
    ) = clientHandler.sendToServer(data)

    actual override suspend fun receiveDateFromServer() =
        clientHandler.receiveFromServer()

    actual override suspend fun sendDataToClient(data: ByteArray) =
        serverHandler.sendToClient(data)

    actual override suspend fun receiveDataFromClient() =
        serverHandler.receiveFromClient()

    actual override suspend fun stopServer() =
        serverHandler.stop()

    actual override suspend fun disconnectClient() =
        clientHandler.disconnect()
}
