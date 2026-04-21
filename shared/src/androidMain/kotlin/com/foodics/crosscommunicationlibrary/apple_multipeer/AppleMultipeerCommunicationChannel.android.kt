package com.foodics.crosscommunicationlibrary.apple_multipeer

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import scanner.IoTDevice

actual class AppleMultipeerCommunicationChannel actual constructor() : CommunicationChannel {
    actual override val connectionType: ConnectionType = ConnectionType.APPLE_MULTIPEER
    actual override suspend fun startServer(deviceName: String, identifier: String) {}
    actual override fun scan(): Flow<List<IoTDevice>> = flowOf(emptyList())
    actual override suspend fun connectToServer(device: IoTDevice) {}
    actual override suspend fun sendDataToServer(data: ByteArray, writeType: WriteType) {}
    actual override suspend fun receiveDataFromServer(): Flow<ByteArray> = emptyFlow()
    actual override suspend fun sendDataToClient(data: ByteArray) {}
    actual override suspend fun receiveDataFromClient(): Flow<ByteArray> = emptyFlow()
    actual override suspend fun stopServer() {}
    actual override suspend fun disconnectClient() {}
}
