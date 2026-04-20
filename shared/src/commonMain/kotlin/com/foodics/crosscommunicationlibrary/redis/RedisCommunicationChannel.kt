package com.foodics.crosscommunicationlibrary.redis

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

/**
 * Redis Pub/Sub communication channel.
 *
 * Uses the Redis RESP (REdis Serialization Protocol) over TCP to implement
 * device discovery and bidirectional messaging via Redis Pub/Sub.
 *
 * Protocol (TCP port 6379, RESP framing):
 *   Commands : *N\r\n$len\r\narg\r\n...
 *   Pub/Sub  : *3\r\n$7\r\nmessage\r\n$ch\r\nchannel\r\n$n\r\ndata\r\n
 *
 * Two TCP connections are used per side (sub + pub) because Redis disallows
 * PUBLISH on a subscribed connection.
 *
 * Channel topology:
 *   Discovery : "foodics:redis:discovery"       (server publishes beacon every 2 s)
 *   Server in : "foodics:redis.{serverId}.in"   (server subscribes; clients publish)
 *   Client in : "foodics:redis.{clientId}.in"   (client subscribes; server replies)
 *
 * Each payload sent client→server is prefixed with a 4-byte-length header
 * encoding the client's reply channel so the server knows where to respond.
 *
 * Default broker: [REDIS_DEFAULT_BROKER] (local Redis instance).
 */
const val REDIS_DEFAULT_BROKER = "redis://localhost:6379"

expect class RedisCommunicationChannel(
    brokerUrl: String = REDIS_DEFAULT_BROKER
) : CommunicationChannel {
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
