package com.foodics.crosscommunicationlibrary.bluetooth

import client.WriteType
import com.foodics.crosscommunicationlibrary.AndroidAppContextProvider
import com.foodics.crosscommunicationlibrary.core.ClientMessage
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import com.foodics.crosscommunicationlibrary.core.ConnectedClient
import ConnectionType
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

actual class BluetoothCommunicationChannel : CommunicationChannel {
    private val context = AndroidAppContextProvider.context
    private val serverHandler = BluetoothServerHandler(context)
    private val clientHandler = BluetoothClientHandler(context)

    actual override val connectionType: ConnectionType = ConnectionType.BLUETOOTH

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
    actual override fun clientConnectionState() = serverHandler.clientConnectionState()
    actual override fun connectedClients(): Flow<List<ConnectedClient>> = serverHandler.connectedClients()
    actual override suspend fun receiveMessagesFromClient(): Flow<ClientMessage> = serverHandler.receiveMessagesFromClient()
}
