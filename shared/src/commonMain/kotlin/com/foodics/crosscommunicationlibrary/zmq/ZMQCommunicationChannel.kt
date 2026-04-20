package com.foodics.crosscommunicationlibrary.zmq

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

/**
 * ZeroMQ (ZMTP 3.1) communication channel.
 *
 * Implements peer-to-peer messaging using the ZeroMQ Message Transfer Protocol
 * (ZMTP 3.1) with the PAIR socket pattern.  Unlike every other channel in this
 * library, ZMQ requires no broker — two devices connect directly.
 *
 * Wire protocol (all over TCP):
 *   1. Greeting  — 64-byte fixed header exchanged in both directions
 *   2. READY     — command frame carrying socket type ("PAIR") and NULL mechanism
 *   3. Messages  — short frames (1-byte size, ≤255 B) or long frames (8-byte size)
 *
 * Discovery — UDP broadcast on port [ZMQ_DISCOVERY_PORT]:
 *   Server: broadcasts  "name|id|zmq_tcp_port"  every 2 s
 *   Client: receives beacons, builds device list with TTL eviction
 *
 * ConnectionType: [ConnectionType.ZMQ]
 */
const val ZMQ_DISCOVERY_PORT = 5556

expect class ZMQCommunicationChannel() : CommunicationChannel {
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
