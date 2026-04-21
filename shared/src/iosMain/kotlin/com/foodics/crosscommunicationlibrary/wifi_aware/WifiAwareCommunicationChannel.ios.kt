package com.foodics.crosscommunicationlibrary.wifi_aware

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import scanner.IoTDevice

actual class WifiAwareCommunicationChannel actual constructor() :
    CommunicationChannel {
    actual override val connectionType: ConnectionType
        get() = ConnectionType.WIFI_AWARE

    actual override suspend fun startServer(deviceName: String, identifier: String) {
    }

    actual override fun scan(): Flow<List<IoTDevice>> = flowOf(emptyList())

    actual override suspend fun connectToServer(device: IoTDevice) {
    }

    actual override suspend fun sendDataToServer(
        data: ByteArray,
        writeType: WriteType
    ) {
    }

    actual override suspend fun receiveDataFromServer(): Flow<ByteArray> = emptyFlow()

    actual override suspend fun sendDataToClient(data: ByteArray) {
    }

    actual override suspend fun receiveDataFromClient(): Flow<ByteArray> = emptyFlow()

    actual override suspend fun stopServer() {
    }

    actual override suspend fun disconnectClient() {
    }
}