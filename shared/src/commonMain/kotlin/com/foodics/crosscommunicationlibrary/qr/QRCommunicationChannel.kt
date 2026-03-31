package com.foodics.crosscommunicationlibrary.qr

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import scanner.IoTDevice

/**
 * QR Code bootstrapping channel.
 *
 * Server side: generates a QR code containing {id, name, ip, port} and opens a
 * TCP server socket. Expose [qrCodeBytes] (PNG bytes) for the UI to display.
 *
 * Client side: [scan] returns a flow that emits discovered devices once the user
 * scans a QR code via the platform camera (Android: embed [QRScannerView] composable;
 * iOS: the bridge auto-presents the camera overlay). After discovery, call
 * [connectToServer] to open a TCP connection and exchange data.
 */
expect class QRCommunicationChannel() : CommunicationChannel {
    override val connectionType: ConnectionType

    /** PNG-encoded QR code bytes, non-null after [startServer] succeeds. */
    val qrCodeBytes: StateFlow<ByteArray?>

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
