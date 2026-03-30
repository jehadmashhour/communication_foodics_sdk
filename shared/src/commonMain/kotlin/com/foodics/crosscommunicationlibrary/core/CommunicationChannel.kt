package com.foodics.crosscommunicationlibrary.core

import ConnectionType
import client.WriteType
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

interface CommunicationChannel {
    val connectionType: ConnectionType
    suspend fun startServer(deviceName:String, identifier:String)
    fun scan(): Flow<List<IoTDevice>>
    suspend fun connectToServer(device: IoTDevice)

    suspend fun sendDataToServer(data: ByteArray, writeType: WriteType = WriteType.DEFAULT)
    suspend fun receiveDateFromServer(): Flow<ByteArray>

    suspend fun sendDataToClient(data: ByteArray)
    suspend fun receiveDataFromClient(): Flow<ByteArray>

    suspend fun stopServer()
    suspend fun disconnectClient()
}