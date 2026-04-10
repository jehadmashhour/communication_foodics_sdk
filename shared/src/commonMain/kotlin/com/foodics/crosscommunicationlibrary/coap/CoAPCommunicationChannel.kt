package com.foodics.crosscommunicationlibrary.coap

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

/**
 * CoAP (Constrained Application Protocol — RFC 7252) communication channel.
 *
 * CoAP is the standard lightweight binary protocol for IoT and constrained devices.
 * It uses UDP, carries RESTful semantics (POST/CONTENT), and has minimal overhead
 * (~4-byte fixed header) compared to HTTP or WebSocket.
 *
 * Discovery  : mDNS service type "_foodics_coap._udp." — dynamic port advertised per session.
 * Transport  : UDP datagrams with CoAP NON (non-confirmable) framing.
 * Data flow  : Client sends CoAP POST → server emits payload.
 *              Server sends CoAP CONTENT → client emits payload.
 *
 * POS use-cases:
 *   - Communicating with CoAP-native IoT devices (smart labels, ESL tags, sensors)
 *   - Low-overhead local network messaging between KMP apps
 *   - Integration with CoAP gateways and resource directories
 *
 * Works across Android ↔ Android, iOS ↔ iOS, and Android ↔ iOS.
 */
expect class CoAPCommunicationChannel() : CommunicationChannel {
    override val connectionType: ConnectionType
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
