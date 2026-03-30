package com.foodics.crosscommunicationlibrary.udp

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

actual class UDPCommunicationChannel actual constructor() :
    CommunicationChannel {
    actual override val connectionType: ConnectionType
        get() = TODO("Not yet implemented")

    actual override suspend fun startServer(deviceName: String, identifier: String) {
    }

    actual override fun scan(): Flow<List<IoTDevice>> {
        TODO("Not yet implemented")
    }

    actual override suspend fun connectToServer(device: IoTDevice) {
    }

    actual override suspend fun sendDataToServer(
        data: ByteArray,
        writeType: WriteType
    ) {
    }

    actual override suspend fun receiveDateFromServer(): Flow<ByteArray> {
        TODO("Not yet implemented")
    }

    actual override suspend fun sendDataToClient(data: ByteArray) {
    }

    actual override suspend fun receiveDataFromClient(): Flow<ByteArray> {
        TODO("Not yet implemented")
    }

    actual override suspend fun stopServer() {
    }

    actual override suspend fun disconnectClient() {
    }
}