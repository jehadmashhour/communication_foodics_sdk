package com.foodics.crosscommunicationlibrary.amqp

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

/**
 * AMQP 0-9-1 (Advanced Message Queuing Protocol) communication channel.
 *
 * AMQP is a binary, full-featured messaging protocol widely used in enterprise POS
 * and payment systems (RabbitMQ, ActiveMQ, Azure Service Bus).
 *
 * Architecture (broker-mediated, not peer-to-peer):
 *   Both devices connect to the SAME broker over TCP port 5672.
 *
 * Queue topology:
 *   Server subscribes to  "foodics.amqp.{id}.in"   — receives client → server data.
 *   Server publishes to   "foodics.amqp.{id}.out"  — sends server → client data.
 *   Client subscribes to  "foodics.amqp.{id}.out"  — receives server → client data.
 *   Client publishes to   "foodics.amqp.{id}.in"   — sends client → server data.
 *
 * Discovery:
 *   Server publishes beacon JSON to fanout exchange "foodics.amqp.discovery" every 2 s.
 *   Client binds a temporary exclusive queue to that exchange during scan().
 *
 * Key AMQP 0-9-1 frames used:
 *   Connection.Start/StartOk, Connection.Tune/TuneOk, Connection.Open/OpenOk,
 *   Channel.Open/OpenOk, Exchange.Declare/DeclareOk, Queue.Declare/DeclareOk,
 *   Queue.Bind/BindOk, Basic.Consume/ConsumeOk, Basic.Deliver,
 *   Basic.Publish + Content-Header + Content-Body.
 *
 * Default broker: [AMQP_DEFAULT_BROKER] (local RabbitMQ — change to CloudAMQP etc.).
 *
 * Works across Android ↔ Android, iOS ↔ iOS, and Android ↔ iOS.
 */
const val AMQP_DEFAULT_BROKER = "amqp://guest:guest@localhost:5672"

expect class AMQPCommunicationChannel(brokerUrl: String = AMQP_DEFAULT_BROKER) : CommunicationChannel {
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
