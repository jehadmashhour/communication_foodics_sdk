package com.foodics.crosscommunicationlibrary.grpc

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

/**
 * gRPC-framed TCP communication channel.
 *
 * Uses the standard **gRPC Length-Prefixed Message** wire format over a plain
 * TCP connection discovered via mDNS (`_grpc._tcp.`).
 *
 * Wire format (per message):
 *   [0x00 : 1 byte]          — compression flag (0 = uncompressed)
 *   [length : 4 bytes BE]    — payload byte count
 *   [payload : length bytes] — raw message bytes (Protobuf, JSON, or any binary)
 *
 * This is the same 5-byte framing used by gRPC-Web and by gRPC over HTTP/2,
 * without the HTTP/2 multiplexing layer. Advantages:
 *   - Zero-overhead framing compatible with gRPC tooling
 *   - Compression flag reserved for future LZ4/gzip support
 *   - Can carry Protobuf, FlatBuffers, or raw bytes without modification
 *
 * Discovery  : mDNS service type `_grpc._tcp.` (standard gRPC service advertisement)
 * Transport  : persistent full-duplex TCP
 *
 * POS use-cases:
 *   - Efficient binary RPC between SDK devices (Protobuf payloads)
 *   - Replacing REST for high-frequency inter-device calls
 *   - Structured order/inventory sync between POS terminal and kitchen display
 *   - Zero-config service discovery via mDNS + familiar gRPC framing
 */
const val GRPC_SERVICE_TYPE = "_grpc._tcp."

expect class GrpcCommunicationChannel() : CommunicationChannel {
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
