package com.foodics.crosscommunicationlibrary.serial

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

actual class SerialCommunicationChannel actual constructor(
    portPath: String,
    private val baudRate: Int
) : CommunicationChannel {

    // If no port specified, auto-select the first available one.
    private val effectivePort = portPath.ifBlank {
        enumerateSerialPorts().firstOrNull() ?: "/dev/ttyS0"
    }

    private val serverHandler = SerialServerHandler(effectivePort, baudRate)
    private val clientHandler = SerialClientHandler(baudRate)

    actual override val connectionType: ConnectionType = ConnectionType.SERIAL

    actual override suspend fun startServer(deviceName: String, identifier: String) =
        serverHandler.start(deviceName, identifier)

    actual override fun scan(): Flow<List<IoTDevice>> = clientHandler.scan()

    actual override suspend fun connectToServer(device: IoTDevice) =
        clientHandler.connect(device)

    actual override suspend fun sendDataToServer(data: ByteArray, writeType: WriteType) =
        clientHandler.sendToServer(data, writeType)

    actual override suspend fun receiveDateFromServer(): Flow<ByteArray> =
        clientHandler.receiveFromServer()

    actual override suspend fun sendDataToClient(data: ByteArray) =
        serverHandler.sendToClient(data)

    actual override suspend fun receiveDataFromClient(): Flow<ByteArray> =
        serverHandler.receiveFromClient()

    actual override suspend fun stopServer() = serverHandler.stop()

    actual override suspend fun disconnectClient() = clientHandler.disconnect()
}
