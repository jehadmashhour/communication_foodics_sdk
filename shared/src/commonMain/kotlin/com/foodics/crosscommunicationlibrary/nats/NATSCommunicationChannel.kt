package com.foodics.crosscommunicationlibrary.nats

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

/**
 * NATS (Neural Autonomic Transport System) communication channel.
 *
 * NATS is a lightweight, text-based publish-subscribe messaging system widely used
 * in cloud-native, IoT, and microservices architectures.  Its protocol is far
 * simpler than AMQP or MQTT — every message is a plain text command followed by
 * a binary payload.
 *
 * Protocol (TCP port 4222, all control lines CRLF-terminated):
 *   S→C  INFO {json}
 *   C→S  CONNECT {json}        — establish session, verbose=false
 *   C→S  SUB <subject> <sid>   — subscribe to a subject
 *   C→S  PUB <subject> <n>\r\n<bytes>\r\n — publish n bytes
 *   S→C  MSG <subject> <sid> <n>\r\n<bytes>\r\n — deliver a message
 *   Both PING / PONG            — server keepalive; client must reply PONG
 *
 * Subject topology:
 *   Discovery publish : "foodics.nats.discovery"      (fanout, beacon every 2 s)
 *   Server receives   : "foodics.nats.{id}.in"
 *   Client receives   : "foodics.nats.{id}.out"
 *
 * Default broker: [NATS_DEFAULT_BROKER] (public NATS demo server — no credentials needed).
 *
 * Works across Android ↔ Android, iOS ↔ iOS, and Android ↔ iOS.
 */
const val NATS_DEFAULT_BROKER = "nats://demo.nats.io:4222"

expect class NATSCommunicationChannel(brokerUrl: String = NATS_DEFAULT_BROKER) : CommunicationChannel {
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
