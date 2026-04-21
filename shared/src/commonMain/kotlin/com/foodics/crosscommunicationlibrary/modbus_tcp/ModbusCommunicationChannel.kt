package com.foodics.crosscommunicationlibrary.modbus_tcp

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

/**
 * Modbus TCP communication channel.
 *
 * Implements the Modbus Application Protocol (MBAP) framing over TCP, the
 * dominant standard for industrial device communication (scales, printers,
 * kitchen equipment, PLCs).
 *
 * Frame structure (Modbus TCP ADU):
 *   [Transaction ID : 2 bytes BE]   — per-request identifier
 *   [Protocol ID    : 2 bytes]      — always 0x0000
 *   [Length         : 2 bytes BE]   — byte count of Unit ID + PDU
 *   [Unit ID        : 1 byte]       — slave address (0x01 default)
 *   [Function Code  : 1 byte]       — operation type
 *   [Data           : n bytes]      — payload
 *
 * Custom function codes used for generic data transfer:
 *   0x41  FC_UPLOAD  — client → server data
 *   0x42  FC_PUSH    — server → client data (unsolicited push)
 *
 * Discovery — UDP broadcast on port [MODBUS_DISCOVERY_PORT]:
 *   Server: broadcasts "name|id|tcp_port" every 2 s
 *   Client: receives beacons, builds device list with TTL eviction
 */
expect class ModbusCommunicationChannel() : CommunicationChannel {
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
