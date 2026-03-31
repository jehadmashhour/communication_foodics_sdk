package com.foodics.crosscommunicationlibrary.uwb

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

actual class UWBCommunicationChannel : CommunicationChannel {

    private val serverHandler = UWBServerHandler()
    private val clientHandler = UWBClientHandler()

    actual override val connectionType: ConnectionType = ConnectionType.UWB

    actual override suspend fun startServer(deviceName: String, identifier: String) =
        serverHandler.start(deviceName, identifier)

    actual override fun scan(): Flow<List<IoTDevice>> =
        clientHandler.scan()

    actual override suspend fun connectToServer(device: IoTDevice) =
        clientHandler.connect(device)

    actual override suspend fun sendDataToServer(data: ByteArray, writeType: WriteType) =
        clientHandler.sendToServer(data, writeType)

    actual override suspend fun receiveDateFromServer(): Flow<ByteArray> =
        clientHandler.receiveFromServer()

    actual override suspend fun sendDataToClient(data: ByteArray) {
        // UWB is a ranging-only channel; data sending is not supported.
    }

    actual override suspend fun receiveDataFromClient(): Flow<ByteArray> =
        serverHandler.receiveFromClient()

    actual override suspend fun stopServer() =
        serverHandler.stop()

    actual override suspend fun disconnectClient() =
        clientHandler.disconnect()
}
