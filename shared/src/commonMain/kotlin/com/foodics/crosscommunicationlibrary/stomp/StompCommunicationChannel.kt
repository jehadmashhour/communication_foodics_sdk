package com.foodics.crosscommunicationlibrary.stomp

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

/**
 * STOMP (Simple Text-Oriented Messaging Protocol) communication channel.
 *
 * STOMP 1.2 is a text-framed protocol over TCP that is natively understood by major
 * message brokers (ActiveMQ, RabbitMQ, Apollo) and many enterprise POS back-ends.
 *
 * In SDK-to-SDK mode this channel acts as a minimal peer-to-peer STOMP broker:
 *   - Server: listens on a random TCP port, advertises via mDNS "_foodics_stomp._tcp.",
 *     performs the STOMP CONNECT→CONNECTED handshake, then exchanges binary SEND/MESSAGE
 *     frames over the persistent connection.
 *   - Client: discovers via mDNS, performs CONNECT + SUBSCRIBE, then sends SEND frames
 *     and receives MESSAGE frames.
 *
 * Broker interop: the client side can also connect to any STOMP-compatible broker
 * (e.g. ActiveMQ at tcp://broker:61613) by supplying the broker's IP and port as
 * the device address; the STOMP protocol framing is identical.
 *
 * POS use-cases:
 *   - Enterprise POS → ActiveMQ/RabbitMQ message routing
 *   - Direct peer-to-peer messaging between SDK instances
 *   - Integration with STOMP-capable gateways or middleware
 *
 * Works across Android ↔ Android, iOS ↔ iOS, and Android ↔ iOS.
 */
expect class StompCommunicationChannel() : CommunicationChannel {
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
