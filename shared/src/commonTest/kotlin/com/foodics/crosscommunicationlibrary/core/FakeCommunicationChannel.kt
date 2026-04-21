package com.foodics.crosscommunicationlibrary.core

import ConnectionQuality
import ConnectionType
import client.WriteType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import scanner.IoTDevice

class FakeCommunicationChannel(
    override val connectionType: ConnectionType
) : CommunicationChannel {

    var startServerCalled = false
    var lastServerName = ""
    var lastServerIdentifier = ""

    var sendToServerData: ByteArray? = null
    var sendToClientData: ByteArray? = null

    var stopServerCalled = false
    var disconnectClientCalled = false

    override suspend fun startServer(deviceName: String, identifier: String) {
        startServerCalled = true
        lastServerName = deviceName
        lastServerIdentifier = identifier
    }

    override fun scan(): Flow<List<IoTDevice>> = flowOf(emptyList())

    override suspend fun connectToServer(device: IoTDevice) {}

    override suspend fun sendDataToServer(data: ByteArray, writeType: WriteType) {
        sendToServerData = data
    }

    override suspend fun receiveDataFromServer(): Flow<ByteArray> = emptyFlow()

    override suspend fun sendDataToClient(data: ByteArray) {
        sendToClientData = data
    }

    override suspend fun receiveDataFromClient(): Flow<ByteArray> = emptyFlow()

    override suspend fun stopServer() {
        stopServerCalled = true
    }

    override suspend fun disconnectClient() {
        disconnectClientCalled = true
    }

    override fun connectionQuality(): Flow<ConnectionQuality> = emptyFlow()
}
