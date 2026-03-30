package com.foodics.crosscommunicationlibrary.wifi_aware

import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import ConnectionType
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

//common code
expect class WifiAwareCommunicationChannel() : CommunicationChannel {
    override val connectionType: ConnectionType
    override suspend fun startServer(deviceName: String, identifier: String)
    override fun scan(): Flow<List<IoTDevice>>
    override suspend fun connectToServer(device: IoTDevice)
    override suspend fun sendDataToServer(data: ByteArray, writeType: WriteType)
    override suspend fun receiveDateFromServer(): Flow<ByteArray>
    override suspend fun sendDataToClient(data: ByteArray)
    override suspend fun receiveDataFromClient(): Flow<ByteArray>
    override suspend fun stopServer()
    override suspend fun disconnectClient()
}