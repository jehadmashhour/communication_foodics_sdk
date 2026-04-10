package com.foodics.crosscommunicationlibrary.tcp

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

/**
 * TCP Socket communication channel with 4-byte length-prefix framing.
 *
 * This channel provides raw bidirectional TCP streaming with a minimal framing layer:
 * each message is prefixed by its 4-byte big-endian length, ensuring reliable message
 * boundaries over TCP's byte-stream transport.
 *
 * Wire format: [length: 4 bytes BE] [payload: length bytes]
 *
 * Discovery  : mDNS service type "_foodics_tcpsock._tcp." — server advertises on a
 *              dynamic port.
 * Transport  : Persistent TCP connection. Both sides can push data at any time after
 *              the connection is established.
 *
 * Distinct from other TCP-based channels:
 *   - LAN          : fixed port 8080, no message framing, single client
 *   - WebSocket    : WS handshake + masking overhead
 *   - HTTP REST    : stateless request-response, no push from server
 *   - CoAP         : UDP-based
 *
 * POS use-cases:
 *   - Custom TCP peripherals (payment terminals, pole displays, label printers)
 *   - Any device speaking a simple length-prefixed TCP protocol
 *   - Peer-to-peer SDK communication where WebSocket overhead is undesirable
 *
 * Works across Android ↔ Android, iOS ↔ iOS, and Android ↔ iOS.
 */
expect class TcpCommunicationChannel() : CommunicationChannel {
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
