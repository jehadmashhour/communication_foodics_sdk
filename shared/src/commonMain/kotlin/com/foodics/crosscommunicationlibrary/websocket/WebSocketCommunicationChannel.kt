package com.foodics.crosscommunicationlibrary.websocket

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

/**
 * WebSocket (RFC 6455) communication channel.
 *
 * Discovery  : mDNS service type "_foodics_ws._tcp." — server advertises on a random port.
 * Transport  : Persistent, full-duplex WebSocket connection over TCP.
 *   - Server sends unmasked binary frames to client.
 *   - Client sends masked binary frames to server (per spec).
 *
 * Distinct from HTTP/REST (stateless) and LAN raw TCP (SDK-only):
 *   - Persistent connection — no per-message handshake overhead.
 *   - Standard protocol — any WebSocket client (browser, app, tool) can connect without the SDK.
 *
 * Works across Android ↔ Android, iOS ↔ iOS, and Android ↔ iOS.
 */
expect class WebSocketCommunicationChannel() : CommunicationChannel {
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
