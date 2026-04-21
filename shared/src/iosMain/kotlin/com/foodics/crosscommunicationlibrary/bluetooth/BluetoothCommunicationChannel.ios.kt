package com.foodics.crosscommunicationlibrary.bluetooth

import client.WriteType
import com.foodics.crosscommunicationlibrary.core.ClientMessage
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import com.foodics.crosscommunicationlibrary.core.ConnectedClient
import ConnectionQuality
import ConnectionType
import handler.BluetoothClientHandler
import handler.BluetoothServerHandler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import scanner.IoTDevice

actual class BluetoothCommunicationChannel : CommunicationChannel {
    private val serverHandler = BluetoothServerHandler()
    private val clientHandler = BluetoothClientHandler()

    actual override val connectionType: ConnectionType = ConnectionType.BLUETOOTH

    actual override suspend fun startServer(deviceName: String, identifier: String) =
        serverHandler.start(deviceName, identifier)

    actual override fun scan(): Flow<List<IoTDevice>> = clientHandler.scan()
    actual override suspend fun connectToServer(device: IoTDevice) = clientHandler.connect(device)
    actual override suspend fun sendDataToServer(data: ByteArray, writeType: WriteType) =
        clientHandler.sendToServer(data, writeType)

    actual override suspend fun receiveDataFromServer(): Flow<ByteArray> = clientHandler.receiveFromServer()
    actual override suspend fun sendDataToClient(data: ByteArray) = serverHandler.sendToClient(data)
    actual override suspend fun receiveDataFromClient(): Flow<ByteArray> = serverHandler.receiveFromClient()
    actual override suspend fun stopServer() = serverHandler.stop()
    actual override suspend fun disconnectClient() = clientHandler.disconnect()
    actual override fun clientConnectionState() = serverHandler.clientConnectionState()

    actual override fun connectedClients(): Flow<List<ConnectedClient>> =
        serverHandler.connectedClients().map { list ->
            list.map { ConnectedClient(it.id, it.name) }
        }

    actual override suspend fun receiveMessagesFromClient(): Flow<ClientMessage> =
        serverHandler.receiveMessagesFromClient().map { msg ->
            ClientMessage(ConnectedClient(msg.client.id, msg.client.name), msg.data)
        }

    actual override fun connectionQuality(): Flow<ConnectionQuality> = clientHandler.connectionQuality()
}