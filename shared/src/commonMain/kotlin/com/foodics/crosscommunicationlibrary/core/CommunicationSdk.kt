package com.foodics.crosscommunicationlibrary.core

import ConnectionQuality
import ConnectionType
import client.WriteType
import com.foodics.crosscommunicationlibrary.logger.CommunicationLogger
import com.foodics.crosscommunicationlibrary.logger.debug
import com.foodics.crosscommunicationlibrary.logger.error
import com.foodics.crosscommunicationlibrary.logger.info
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import scanner.IoTDevice

private const val LOG_TITLE = "COMMUNICATION_SDK"

class CommunicationSDK(
    private val channels: List<CommunicationChannel>,
    private val logger: CommunicationLogger?
) {
    private val deviceCache = mutableMapOf<Pair<ConnectionType, String>, IoTDevice>()

    private fun IoTDevice.toDiscoveredDevice(): DiscoveredDevice =
        DiscoveredDevice(
            id = id!!,
            name = name,
            addressByType = mapOf(connectionType!! to address),
            connectionTypes = setOf(connectionType!!)
        )

    private fun DiscoveredDevice.mergeWith(other: DiscoveredDevice): DiscoveredDevice =
        copy(
            addressByType = addressByType + other.addressByType,
            connectionTypes = connectionTypes + other.connectionTypes
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    fun scan(): Flow<List<DiscoveredDevice>> {
        logger?.info(LOG_TITLE, "Scan started", mapOf("channel_count" to channels.size))
        return channels
            .asFlow()
            .flatMapMerge { channel ->
                channel.scan()
                    .onEach { devices ->
                        val newDevices = devices.filter { it.id != null && it.connectionType != null }
                        if (newDevices.isNotEmpty()) {
                            logger?.debug(
                                LOG_TITLE, "Devices discovered",
                                mapOf(
                                    "connection_type" to channel.connectionType.name,
                                    "count" to newDevices.size,
                                    "names" to newDevices.joinToString { it.name }
                                )
                            )
                        }
                    }
                    .map { devices ->
                        val filtered = devices.filter { it.id != null && it.connectionType != null }
                        filtered.forEach { device ->
                            deviceCache[device.connectionType!! to device.id!!] = device
                        }
                        channel.connectionType to filtered.map { it.toDiscoveredDevice() }
                    }
            }
            .scan(emptyMap<ConnectionType, List<DiscoveredDevice>>()) { acc, (type, devices) ->
                acc + (type to devices)
            }
            .map { channelDevices ->
                channelDevices.values.flatten()
                    .groupBy { it.id }
                    .map { (_, devices) -> devices.reduce { a, b -> a.mergeWith(b) } }
            }
            .flowOn(Dispatchers.IO)
    }

    suspend fun startServers(deviceName: String, identifier: String) = coroutineScope {
        logger?.info(LOG_TITLE, "Starting all servers", mapOf("device_name" to deviceName))
        channels.forEach { channel ->
            launch(Dispatchers.IO) { channel.startServer(deviceName, identifier) }
        }
    }

    suspend fun startServer(connectionType: ConnectionType, deviceName: String, identifier: String) {
        logger?.info(LOG_TITLE, "Starting server", mapOf("connection_type" to connectionType.name, "device_name" to deviceName))
        channels.first { it.connectionType == connectionType }.startServer(deviceName, identifier)
    }

    suspend fun connectToServer(device: DiscoveredDevice, connectionType: ConnectionType) {
        logger?.info(LOG_TITLE, "Connecting to server", mapOf("connection_type" to connectionType.name, "device_name" to device.name, "device_id" to device.id))
        val iotDevice = deviceCache[connectionType to device.id]
            ?: run {
                logger?.error(LOG_TITLE, "Device not found in cache", extra = mapOf("connection_type" to connectionType.name, "device_name" to device.name))
                error("Device '${device.name}' not found in scan cache for $connectionType. Scan before connecting.")
            }
        channels.first { it.connectionType == connectionType }.connectToServer(iotDevice)
        logger?.info(LOG_TITLE, "Connected to server", mapOf("connection_type" to connectionType.name, "device_name" to device.name))
    }

    suspend fun sendDataToServer(connectionType: ConnectionType, data: ByteArray, writeType: WriteType = WriteType.DEFAULT) {
        logger?.debug(LOG_TITLE, "Sending data to server", mapOf("connection_type" to connectionType.name, "bytes" to data.size))
        channels.first { it.connectionType == connectionType }.sendDataToServer(data, writeType)
    }

    suspend fun receiveFromServer(connectionType: ConnectionType): Flow<ByteArray> =
        channels.first { it.connectionType == connectionType }.receiveDataFromServer()

    suspend fun sendDataToClient(connectionType: ConnectionType, data: ByteArray) {
        logger?.debug(LOG_TITLE, "Sending data to client", mapOf("connection_type" to connectionType.name, "bytes" to data.size))
        channels.first { it.connectionType == connectionType }.sendDataToClient(data)
    }

    suspend fun sendDataToClients(connectionType: ConnectionType, data: ByteArray, clientIds: List<String>) {
        logger?.debug(LOG_TITLE, "Sending data to clients", mapOf("connection_type" to connectionType.name, "bytes" to data.size, "target_count" to clientIds.size))
        channels.first { it.connectionType == connectionType }.sendDataToClients(data, clientIds)
    }

    suspend fun receiveDataFromClient(connectionType: ConnectionType): Flow<ByteArray> =
        channels.first { it.connectionType == connectionType }.receiveDataFromClient()

    suspend fun stopServer(connectionType: ConnectionType) {
        logger?.info(LOG_TITLE, "Stopping server", mapOf("connection_type" to connectionType.name))
        channels.first { it.connectionType == connectionType }.stopServer()
    }

    suspend fun stopAllServers() {
        logger?.info(LOG_TITLE, "Stopping all servers")
        channels.forEach { it.stopServer() }
    }

    suspend fun disconnectClient(connectionType: ConnectionType) {
        logger?.info(LOG_TITLE, "Disconnecting client", mapOf("connection_type" to connectionType.name))
        channels.first { it.connectionType == connectionType }.disconnectClient()
    }

    fun clientConnectionState(connectionType: ConnectionType): Flow<Boolean> =
        channels.first { it.connectionType == connectionType }.clientConnectionState()

    fun connectedClients(connectionType: ConnectionType): Flow<List<ConnectedClient>> =
        channels.first { it.connectionType == connectionType }.connectedClients()

    suspend fun receiveMessagesFromClient(connectionType: ConnectionType): Flow<ClientMessage> =
        channels.first { it.connectionType == connectionType }.receiveMessagesFromClient()

    fun scanDevices(connectionType: ConnectionType): Flow<List<IoTDevice>> =
        channels.first { it.connectionType == connectionType }.scan()

    fun connectionQuality(connectionType: ConnectionType): Flow<ConnectionQuality> =
        channels.first { it.connectionType == connectionType }.connectionQuality()

    companion object {
        fun builder(): CommunicationSdkBuilder = CommunicationSdkBuilder()
    }
}
