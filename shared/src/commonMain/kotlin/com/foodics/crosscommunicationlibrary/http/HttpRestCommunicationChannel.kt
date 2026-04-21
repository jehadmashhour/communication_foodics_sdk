package com.foodics.crosscommunicationlibrary.http

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

/**
 * HTTP/REST communication channel.
 *
 * Discovery : mDNS service type "_foodics_http._tcp." — server advertises on a random port.
 * Data transport : HTTP/1.1 POST /message with binary body.
 *   - Client sends POST → body emitted to [receiveDataFromClient] flow on server.
 *   - Server queues a response via [sendDataToClient] → returned as HTTP response body to client.
 *   - Client receives response body via [receiveDataFromServer] flow.
 *
 * Unique value: any HTTP-capable device (printer, kiosk, third-party app) can talk to this
 * channel without the SDK — standard HTTP is universally understood.
 *
 * Works across Android ↔ Android, iOS ↔ iOS, and Android ↔ iOS.
 */
expect class HttpRestCommunicationChannel() : CommunicationChannel {
    override val connectionType: ConnectionType
    override suspend fun startServer(deviceName: String, identifier: String)
    override fun scan(): Flow<List<IoTDevice>>
    override suspend fun connectToServer(device: IoTDevice)
    override suspend fun sendDataToServer(data: ByteArray, writeType: WriteType)
    override suspend fun receiveDataFromServer(): Flow<ByteArray>
    override suspend fun sendDataToClient(data: ByteArray)
    override suspend fun receiveDataFromClient(): Flow<ByteArray>
    override suspend fun stopServer()
    override suspend fun disconnectClient()
}
