package com.foodics.crosscommunicationlibrary.mqtt

import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.core.CommunicationChannel
import kotlinx.coroutines.flow.Flow
import scanner.IoTDevice

/**
 * MQTT Discovery communication channel.
 *
 * Both server and client connect to a shared MQTT broker.
 *
 * Discovery : Server publishes a retained message to
 *             `crosscomm/discovery/{serverId}` carrying a JSON payload
 *             with the device name and ID. Clients subscribe to
 *             `crosscomm/discovery/+` to receive retained messages from
 *             all active servers on the broker.
 *
 * Transport : All data is exchanged via MQTT topics:
 *             - Client → Server : `crosscomm/data/{serverId}/toServer`
 *             - Server → Client : `crosscomm/data/{serverId}/toClient`
 *
 * @param brokerUrl  Full broker URL, e.g. `tcp://broker.hivemq.com:1883` or
 *                   `ssl://your-broker.com:8883`.
 */
expect class MQTTCommunicationChannel(brokerUrl: String) : CommunicationChannel {
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
