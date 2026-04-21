package com.foodics.crosscommunicationlibrary.core

import ConnectionType
import client.WriteType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import scanner.IoTDevice

interface CommunicationChannel {
    val connectionType: ConnectionType
    suspend fun startServer(deviceName:String, identifier:String)
    fun scan(): Flow<List<IoTDevice>>
    suspend fun connectToServer(device: IoTDevice)

    suspend fun sendDataToServer(data: ByteArray, writeType: WriteType = WriteType.DEFAULT)
    suspend fun receiveDataFromServer(): Flow<ByteArray>

    suspend fun sendDataToClient(data: ByteArray)
    suspend fun receiveDataFromClient(): Flow<ByteArray>

    suspend fun stopServer()
    suspend fun disconnectClient()

    fun clientConnectionState(): Flow<Boolean> = flowOf(false)
    fun connectedClients(): Flow<List<ConnectedClient>> = flowOf(emptyList())
    suspend fun receiveMessagesFromClient(): Flow<ClientMessage> =
        receiveDataFromClient().map { ClientMessage(ConnectedClient("", "Device"), it) }
}