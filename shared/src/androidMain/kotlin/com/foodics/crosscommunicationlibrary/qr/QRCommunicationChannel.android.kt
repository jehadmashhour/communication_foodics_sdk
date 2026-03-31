package com.foodics.crosscommunicationlibrary.qr

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import scanner.IoTDevice

actual class QRCommunicationChannel actual constructor() : CommunicationChannel {

    private val serverHandler = QRServerHandler()
    val clientHandler = QRClientHandler()   // internal — accessed by QRScannerView

    actual override val connectionType: ConnectionType = ConnectionType.QR

    /** PNG-encoded QR code. Non-null after [startServer] completes. */
    actual val qrCodeBytes: StateFlow<ByteArray?> get() = serverHandler.qrCodeBytes

    actual override suspend fun startServer(deviceName: String, identifier: String) =
        serverHandler.start(deviceName, identifier)

    actual override fun scan() = clientHandler.scan()

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
