package com.foodics.crosscommunicationlibrary.bluetooth

import client.WriteType
import com.foodics.crosscommunicationlibrary.core.ClientMessage
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import com.foodics.crosscommunicationlibrary.core.ConnectedClient
import com.foodics.crosscommunicationlibrary.logger.CommunicationLogger
import ConnectionQuality
import ConnectionType
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

expect class BluetoothCommunicationChannel(logger: CommunicationLogger?) : CommunicationChannel {
    override val connectionType: ConnectionType
    override suspend fun startServer(deviceName: String, identifier: String)
    override fun scan(): Flow<List<IoTDevice>>
    override suspend fun connectToServer(device: IoTDevice)
    override suspend fun sendDataToServer(data: ByteArray, writeType: WriteType)
    override suspend fun receiveDataFromServer(): Flow<ByteArray>
    override suspend fun sendDataToClient(data: ByteArray)
    override suspend fun sendDataToClients(data: ByteArray, clientIds: List<String>)
    override suspend fun receiveDataFromClient(): Flow<ByteArray>
    override suspend fun stopServer()
    override suspend fun disconnectClient()
    override fun clientConnectionState(): Flow<Boolean>
    override fun connectedClients(): Flow<List<ConnectedClient>>
    override suspend fun receiveMessagesFromClient(): Flow<ClientMessage>
    override fun connectionQuality(): Flow<ConnectionQuality>
}